package com.jmj.trade.broker.connection;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.toss.TossCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(classes = TradingBackendApplication.class)
class DatabaseTossCredentialProviderIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-27T04:00:00Z");
    private static final String CANARY_ID = "canary-client-id";
    private static final String CANARY_SECRET = "canary-client-secret";

    @Autowired
    private BrokerConnectionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanConnections() {
        jdbcTemplate.execute("TRUNCATE broker_connections, users CASCADE");
    }

    @Test
    void currentReturnsRevisionWithoutDecryptingCredentials() {
        var userId = insertUser();
        var connectionId = UUID.randomUUID();
        repository.saveAndFlush(BrokerConnection.create(connectionId, userId, storedCredentials(1), NOW));
        var cipher = spy(cipher(1));
        var provider = new DatabaseTossCredentialProvider(repository, cipher);

        var metadata = provider.current(connectionId);

        assertThat(metadata.credentialRevision()).isEqualTo(1);
        verifyNoInteractions(cipher);
    }

    @Test
    void decryptReturnsCredentialsOnlyForExactCurrentRevision() {
        var userId = insertUser();
        var connectionId = UUID.randomUUID();
        var cipher = cipher(1);
        repository.saveAndFlush(BrokerConnection.create(
                connectionId,
                userId,
                cipher.encrypt(connectionId, userId, BrokerType.TOSS_INVEST, 1, canaryCredentials()),
                NOW));
        var provider = new DatabaseTossCredentialProvider(repository, cipher);

        assertThat(provider.decrypt(connectionId, 1))
                .isEqualTo(canaryCredentials());
        assertUnavailableWithoutCanary(() -> provider.decrypt(connectionId, 2));
    }

    @Test
    void replacementDeletionAndMissingConnectionFailClosed() {
        var userId = insertUser();
        var replacedId = UUID.randomUUID();
        var deletedId = UUID.randomUUID();
        var cipher = cipher(1);
        var replaced = BrokerConnection.create(
                replacedId,
                userId,
                cipher.encrypt(replacedId, userId, BrokerType.TOSS_INVEST, 1, canaryCredentials()),
                NOW);
        replaced.replaceCredentials(
                cipher.encrypt(replacedId, userId, BrokerType.TOSS_INVEST, 2, new TossCredentials("new-id", "new-secret")),
                NOW.plusSeconds(1));
        repository.saveAndFlush(replaced);
        var deleted = BrokerConnection.create(
                deletedId,
                userId,
                cipher.encrypt(deletedId, userId, BrokerType.TOSS_INVEST, 1, canaryCredentials()),
                NOW);
        deleted.delete(NOW.plusSeconds(2));
        repository.saveAndFlush(deleted);
        var provider = new DatabaseTossCredentialProvider(repository, cipher);

        assertUnavailableWithoutCanary(() -> provider.decrypt(replacedId, 1));
        assertUnavailableWithoutCanary(() -> provider.current(deletedId));
        assertUnavailableWithoutCanary(() -> provider.decrypt(deletedId, 1));
        assertUnavailableWithoutCanary(() -> provider.current(UUID.randomUUID()));
    }

    @Test
    void missingKeyAndCorruptedCiphertextFailClosedWithoutCanary() {
        var missingKeyUserId = insertUser();
        var corruptUserId = insertUser();
        var missingKeyId = UUID.randomUUID();
        var corruptId = UUID.randomUUID();
        var missingKeyWriterCipher = cipher(2);
        repository.saveAndFlush(BrokerConnection.create(
                missingKeyId,
                missingKeyUserId,
                missingKeyWriterCipher.encrypt(missingKeyId, missingKeyUserId, BrokerType.TOSS_INVEST, 1, canaryCredentials()),
                NOW));
        var corruptWriterCipher = cipher(1);
        var encrypted = corruptWriterCipher.encrypt(corruptId, corruptUserId, BrokerType.TOSS_INVEST, 1, canaryCredentials());
        repository.saveAndFlush(BrokerConnection.create(
                corruptId,
                corruptUserId,
                new EncryptedCredentials(tamper(encrypted.ciphertext()), encrypted.nonce(), encrypted.keyVersion()),
                NOW));
        var provider = new DatabaseTossCredentialProvider(repository, cipherWithOnlyKey(1));

        assertUnavailableWithoutCanary(() -> provider.decrypt(missingKeyId, 1));
        assertUnavailableWithoutCanary(() -> provider.decrypt(corruptId, 1));
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id) VALUES (?)", id);
        return id;
    }

    private static CredentialCipher cipher(int activeKeyVersion) {
        return new CredentialCipher(
                new CredentialKeyring(CredentialKeyringTest.properties(activeKeyVersion, Map.of(
                        1, CredentialKeyringTest.key(11, 32),
                        2, CredentialKeyringTest.key(22, 32)))),
                new SecureRandom());
    }

    private static CredentialCipher cipherWithOnlyKey(int activeKeyVersion) {
        return new CredentialCipher(
                new CredentialKeyring(CredentialKeyringTest.properties(activeKeyVersion, Map.of(
                        activeKeyVersion, CredentialKeyringTest.key(11, 32)))),
                new SecureRandom());
    }

    private static EncryptedCredentials storedCredentials(int keyVersion) {
        return new EncryptedCredentials(bytes(1, 17), bytes(2, 12), keyVersion);
    }

    private static TossCredentials canaryCredentials() {
        return new TossCredentials(CANARY_ID, CANARY_SECRET);
    }

    private static byte[] tamper(byte[] bytes) {
        var tampered = bytes.clone();
        tampered[0] ^= 1;
        return tampered;
    }

    private static byte[] bytes(int value, int size) {
        var bytes = new byte[size];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static void assertUnavailableWithoutCanary(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(CredentialUnavailableException.class)
                .hasMessageNotContaining(CANARY_ID)
                .hasMessageNotContaining(CANARY_SECRET);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
