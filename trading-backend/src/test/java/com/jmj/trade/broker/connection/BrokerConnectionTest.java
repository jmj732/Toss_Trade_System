package com.jmj.trade.broker.connection;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrokerConnectionTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant NOW = Instant.parse("2026-07-27T01:00:00Z");

    @Test
    void createStartsUnverifiedAtFirstCredentialRevision() {
        var connection = BrokerConnection.create(ID, USER_ID, credentials(1), NOW);

        assertThat(connection.getId()).isEqualTo(ID);
        assertThat(connection.getUserId()).isEqualTo(USER_ID);
        assertThat(connection.getBrokerType()).isEqualTo(BrokerType.TOSS_INVEST);
        assertThat(connection.getStatus()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
        assertThat(connection.getCredentialRevision()).isEqualTo(1);
        assertThat(connection.getVersion()).isZero();
        assertThat(connection.getCreatedAt()).isEqualTo(NOW);
        assertThat(connection.getUpdatedAt()).isEqualTo(NOW);
        assertThat(connection.getLastValidatedAt()).isNull();
        assertThat(connection.getDeletedAt()).isNull();
    }

    @Test
    void encryptedCredentialsAreDefensivelyCopied() {
        var ciphertext = bytes(1, 17);
        var nonce = bytes(2, 12);
        var encrypted = new EncryptedCredentials(ciphertext, nonce, 3);

        ciphertext[0] = 99;
        nonce[0] = 99;
        var connection = BrokerConnection.create(ID, USER_ID, encrypted, NOW);

        encrypted.ciphertext()[0] = 88;
        encrypted.nonce()[0] = 88;
        assertThat(connection.getEncryptedCredentials().ciphertext()[0]).isEqualTo((byte) 1);
        assertThat(connection.getEncryptedCredentials().nonce()[0]).isEqualTo((byte) 2);

        var loaded = connection.getEncryptedCredentials();
        loaded.ciphertext()[0] = 77;
        loaded.nonce()[0] = 77;
        assertThat(connection.getEncryptedCredentials().ciphertext()[0]).isEqualTo((byte) 1);
        assertThat(connection.getEncryptedCredentials().nonce()[0]).isEqualTo((byte) 2);
    }

    @Test
    void replaceCredentialsIncrementsRevisionAndResetsValidation() {
        var connection = BrokerConnection.create(ID, USER_ID, credentials(1), NOW);
        var validatedAt = NOW.plusSeconds(60);
        connection.markValidated(1, validatedAt);

        var replacedAt = NOW.plusSeconds(120);
        connection.replaceCredentials(credentials(7), replacedAt);

        assertThat(connection.getCredentialRevision()).isEqualTo(2);
        assertThat(connection.getStatus()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
        assertThat(connection.getLastValidatedAt()).isNull();
        assertThat(connection.getUpdatedAt()).isEqualTo(replacedAt);
        assertThat(connection.getEncryptedCredentials().keyVersion()).isEqualTo(7);
    }

    @Test
    void validationRequiresExpectedCredentialRevision() {
        var connection = BrokerConnection.create(ID, USER_ID, credentials(1), NOW);

        assertThatThrownBy(() -> connection.markValidated(2, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> connection.markInvalid(2, NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class);

        connection.markValidated(1, NOW.plusSeconds(2));
        assertThat(connection.getStatus()).isEqualTo(BrokerConnectionStatus.ACTIVE);
        assertThat(connection.getLastValidatedAt()).isEqualTo(NOW.plusSeconds(2));

        connection.markInvalid(1, NOW.plusSeconds(3));
        assertThat(connection.getStatus()).isEqualTo(BrokerConnectionStatus.INVALID);
        assertThat(connection.getLastValidatedAt()).isEqualTo(NOW.plusSeconds(3));
    }

    @Test
    void deleteScrubsCredentialsIncrementsRevisionAndIsTerminal() {
        var connection = BrokerConnection.create(ID, USER_ID, credentials(1), NOW);
        connection.markValidated(1, NOW.plusSeconds(1));

        var deletedAt = NOW.plusSeconds(2);
        connection.delete(deletedAt);

        assertThat(connection.getStatus()).isEqualTo(BrokerConnectionStatus.DELETED);
        assertThat(connection.getCredentialRevision()).isEqualTo(2);
        assertThat(connection.getEncryptedCredentials()).isNull();
        assertThat(connection.getLastValidatedAt()).isNull();
        assertThat(connection.getDeletedAt()).isEqualTo(deletedAt);
        assertThat(connection.getUpdatedAt()).isEqualTo(deletedAt);

        assertThatThrownBy(() -> connection.replaceCredentials(credentials(2), NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> connection.markValidated(2, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> connection.markInvalid(2, NOW.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commandsRejectNullNowWithoutMutation() {
        var replace = BrokerConnection.create(ID, USER_ID, credentials(1), NOW);
        replace.markValidated(1, NOW.plusSeconds(1));
        assertNullNowDoesNotMutate(replace, () -> replace.replaceCredentials(credentials(9), null));

        var validated = BrokerConnection.create(ID, USER_ID, credentials(1), NOW);
        assertNullNowDoesNotMutate(validated, () -> validated.markValidated(1, null));

        var invalid = BrokerConnection.create(ID, USER_ID, credentials(1), NOW);
        assertNullNowDoesNotMutate(invalid, () -> invalid.markInvalid(1, null));

        var deleted = BrokerConnection.create(ID, USER_ID, credentials(1), NOW);
        deleted.markValidated(1, NOW.plusSeconds(1));
        assertNullNowDoesNotMutate(deleted, () -> deleted.delete(null));
    }

    private static EncryptedCredentials credentials(int keyVersion) {
        return new EncryptedCredentials(bytes(1, 17), bytes(2, 12), keyVersion);
    }

    private static void assertNullNowDoesNotMutate(BrokerConnection connection, Runnable command) {
        var before = snapshot(connection);

        assertThatThrownBy(command::run).isInstanceOf(IllegalArgumentException.class);

        assertThat(snapshot(connection)).isEqualTo(before);
    }

    private static Snapshot snapshot(BrokerConnection connection) {
        var encrypted = connection.getEncryptedCredentials();
        return new Snapshot(
                connection.getStatus(),
                connection.getCredentialRevision(),
                encrypted == null ? null : Arrays.toString(encrypted.ciphertext()),
                encrypted == null ? null : Arrays.toString(encrypted.nonce()),
                encrypted == null ? null : encrypted.keyVersion(),
                connection.getLastValidatedAt(),
                connection.getUpdatedAt(),
                connection.getDeletedAt()
        );
    }

    private record Snapshot(
            BrokerConnectionStatus status,
            long credentialRevision,
            String ciphertext,
            String nonce,
            Integer keyVersion,
            Instant lastValidatedAt,
            Instant updatedAt,
            Instant deletedAt
    ) {
    }

    private static byte[] bytes(int value, int size) {
        var bytes = new byte[size];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) value;
        }
        return bytes;
    }
}
