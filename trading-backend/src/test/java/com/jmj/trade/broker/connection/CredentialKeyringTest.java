package com.jmj.trade.broker.connection;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialKeyringTest {

    @Test
    void decodesBase64KeysThatAreExactly32Bytes() {
        var keyring = new CredentialKeyring(properties(2, Map.of(
                1, key(11, 32),
                2, key(22, 32)
        )));

        assertThat(keyring.activeVersion()).isEqualTo(2);
        assertThat(keyring.activeKey().getEncoded()).containsOnly((byte) 22);
        assertThat(keyring.key(1).getEncoded()).containsOnly((byte) 11);
    }

    @Test
    void rejectsMissingActiveVersion() {
        assertThatThrownBy(() -> new CredentialKeyring(properties(2, Map.of(1, key(11, 32)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("canary");
    }

    @Test
    void rejectsKeysThatAreNot32Bytes() {
        assertThatThrownBy(() -> new CredentialKeyring(properties(1, Map.of(1, key(11, 31)))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CredentialKeyring(properties(1, Map.of(1, key(11, 33)))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingStoredVersionFailsClosedWithoutSecretText() {
        var keyring = new CredentialKeyring(properties(1, Map.of(1, key(11, 32))));

        assertThatThrownBy(() -> keyring.key(9))
                .isInstanceOf(CredentialUnavailableException.class)
                .hasMessageNotContaining("canary");
    }

    static CredentialVaultProperties properties(int activeVersion, Map<Integer, String> keys) {
        return new CredentialVaultProperties(true, activeVersion, keys);
    }

    static String key(int fill, int size) {
        var bytes = new byte[size];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) fill;
        }
        return Base64.getEncoder().encodeToString(bytes);
    }
}
