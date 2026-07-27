package com.jmj.trade.broker.connection;

import com.jmj.trade.broker.toss.TossCredentials;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialCipherTest {

    private static final UUID CONNECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final long REVISION = 7;
    private static final String CANARY_ID = "client-canary-secret";
    private static final String CANARY_SECRET = "secret-canary-value";

    @Test
    void encryptsAndDecryptsTossCredentials() {
        var cipher = cipher(activeKeyring());

        var encrypted = cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new TossCredentials(CANARY_ID, CANARY_SECRET));

        assertThat(encrypted.keyVersion()).isEqualTo(2);
        assertThat(encrypted.nonce()).hasSize(12);
        assertThat(cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION, encrypted))
                .isEqualTo(new TossCredentials(CANARY_ID, CANARY_SECRET));
    }

    @Test
    void repeatedEncryptionsUseDifferentNoncesAndCiphertexts() {
        var cipher = cipher(activeKeyring());
        var credentials = new TossCredentials(CANARY_ID, CANARY_SECRET);

        var first = cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION, credentials);
        var second = cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION, credentials);

        assertThat(first.nonce()).hasSize(12).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    @Test
    void tamperingFailsClosedWithoutCanarySecrets() {
        var cipher = cipher(activeKeyring());
        var encrypted = cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new TossCredentials(CANARY_ID, CANARY_SECRET));

        assertUnavailable(() -> cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new EncryptedCredentials(flip(encrypted.ciphertext()), encrypted.nonce(), encrypted.keyVersion())));
        assertUnavailable(() -> cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new EncryptedCredentials(encrypted.ciphertext(), flip(encrypted.nonce()), encrypted.keyVersion())));
        assertUnavailable(() -> cipher.decrypt(UUID.randomUUID(), USER_ID, BrokerType.TOSS_INVEST, REVISION, encrypted));
        assertUnavailable(() -> cipher.decrypt(CONNECTION_ID, UUID.randomUUID(), BrokerType.TOSS_INVEST, REVISION, encrypted));
        assertUnavailable(() -> cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION + 1, encrypted));
        assertUnavailable(() -> cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new EncryptedCredentials(encrypted.ciphertext(), encrypted.nonce(), 99)));
    }

    @Test
    void malformedPayloadFailsClosed() throws Exception {
        var keyring = activeKeyring();
        var encrypted = encryptRaw(keyring, new byte[]{99, 0, 0, 0, 0}, REVISION);
        var cipher = cipher(keyring);

        assertUnavailable(() -> cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION, encrypted));
    }

    @Test
    void malformedUtf8CredentialFieldFailsClosed() throws Exception {
        var keyring = activeKeyring();
        var encrypted = encryptRaw(keyring, rawPayload(new byte[]{(byte) 0xC3, 0x28},
                CANARY_SECRET.getBytes(StandardCharsets.UTF_8)), REVISION);
        var cipher = cipher(keyring);

        assertUnavailable(() -> cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION, encrypted));
    }

    @Test
    void oldKeyDecryptsWhileNewEncryptionUsesActiveKey() {
        var keyring = activeKeyring();
        var cipher = cipher(keyring);
        var oldEncrypted = cipher(keyringWithActive(1)).encrypt(
                CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new TossCredentials(CANARY_ID, CANARY_SECRET));

        assertThat(oldEncrypted.keyVersion()).isEqualTo(1);
        assertThat(cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION, oldEncrypted))
                .isEqualTo(new TossCredentials(CANARY_ID, CANARY_SECRET));

        var newEncrypted = cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new TossCredentials(CANARY_ID, CANARY_SECRET));
        assertThat(newEncrypted.keyVersion()).isEqualTo(2);
    }

    @Test
    void credentialsMustBeNonblankAndNoMoreThan4KiBUtf8Each() {
        var cipher = cipher(activeKeyring());
        var ok = "가".repeat(1365) + "a";
        var tooLong = ok + "b";

        assertThat(ok.getBytes(StandardCharsets.UTF_8)).hasSize(4096);
        assertThatThrownBy(() -> cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new TossCredentials(" ", CANARY_SECRET)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new TossCredentials(CANARY_ID, " ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(cipher.decrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION, new TossCredentials(ok, ok))))
                .isEqualTo(new TossCredentials(ok, ok));
        assertThatThrownBy(() -> cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new TossCredentials(tooLong, CANARY_SECRET)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cipher.encrypt(CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, REVISION,
                new TossCredentials(CANARY_ID, tooLong)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CredentialCipher cipher(CredentialKeyring keyring) {
        return new CredentialCipher(keyring, new SecureRandom());
    }

    private static CredentialKeyring activeKeyring() {
        return keyringWithActive(2);
    }

    private static CredentialKeyring keyringWithActive(int activeVersion) {
        return new CredentialKeyring(CredentialKeyringTest.properties(activeVersion, Map.of(
                1, CredentialKeyringTest.key(11, 32),
                2, CredentialKeyringTest.key(22, 32)
        )));
    }

    private static EncryptedCredentials encryptRaw(CredentialKeyring keyring, byte[] plaintext, long revision)
            throws Exception {
        var nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keyring.activeKey(), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(CredentialCipher.aad(
                CONNECTION_ID, USER_ID, BrokerType.TOSS_INVEST, keyring.activeVersion(), revision, 1));
        return new EncryptedCredentials(cipher.doFinal(plaintext), nonce, keyring.activeVersion());
    }

    private static byte[] rawPayload(byte[] clientId, byte[] clientSecret) throws Exception {
        var out = new ByteArrayOutputStream();
        var data = new DataOutputStream(out);
        data.writeByte(1);
        data.writeInt(clientId.length);
        data.write(clientId);
        data.writeInt(clientSecret.length);
        data.write(clientSecret);
        data.flush();
        return out.toByteArray();
    }

    private static byte[] flip(byte[] bytes) {
        var changed = bytes.clone();
        changed[0] = (byte) (changed[0] ^ 1);
        return changed;
    }

    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CredentialUnavailableException.class)
                .hasMessageNotContaining(CANARY_ID)
                .hasMessageNotContaining(CANARY_SECRET)
                .satisfies(error -> assertThat(error.toString())
                        .doesNotContain(CANARY_ID)
                        .doesNotContain(CANARY_SECRET));
    }
}
