package com.jmj.trade.broker.connection;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TradingBackendApplication.class)
@Import(BrokerConnectionServiceIntegrationTest.TestCipherConfiguration.class)
class BrokerConnectionServiceIntegrationTest extends PostgresIntegrationTest {

    private static final String CANARY_ID = "service-canary-client-id";
    private static final String CANARY_SECRET = "service-canary-client-secret";

    @Autowired
    private BrokerConnectionService service;

    @Autowired
    private BrokerConnectionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanConnections() {
        jdbcTemplate.execute("TRUNCATE broker_connections, users");
    }

    @Test
    void createAnchorsUserEncryptsCredentialsAndReturnsNoSecret() {
        var userId = UUID.randomUUID();

        var view = service.createToss(userId, CANARY_ID, CANARY_SECRET);

        assertThat(userRows(userId)).isOne();
        assertThat(view.userId()).isEqualTo(userId);
        assertThat(view.brokerType()).isEqualTo(BrokerType.TOSS_INVEST);
        assertThat(view.status()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
        assertThat(view.credentialRevision()).isEqualTo(1);
        assertThat(view.toString()).doesNotContain(CANARY_ID, CANARY_SECRET, "ciphertext", "nonce");
        var row = connectionRow(view.id());
        assertThat(dbSecretText()).doesNotContain(CANARY_ID, CANARY_SECRET);
        assertThat(row.credentialNonce()).hasSize(12);
    }

    @Test
    void duplicateActiveTossConnectionIsStableSecretFreeConflictWithoutPartialRow() {
        var userId = UUID.randomUUID();
        service.createToss(userId, "first-client", "first-secret");

        assertBrokerException(
                () -> service.createToss(userId, CANARY_ID, CANARY_SECRET),
                BrokerConnectionException.Code.ALREADY_EXISTS);

        assertThat(activeConnectionCount(userId)).isOne();
        assertThat(dbSecretText()).doesNotContain(CANARY_ID, CANARY_SECRET);
    }

    @Test
    void replaceIsOwnerScopedIncrementsRevisionAndResetsValidation() {
        var userId = UUID.randomUUID();
        var created = service.createToss(userId, "old-client", "old-secret");
        var connection = repository.findByIdAndUserId(created.id(), userId).orElseThrow();
        connection.markValidated(1, Instant.parse("2026-07-27T05:00:00Z"));
        repository.saveAndFlush(connection);

        var replaced = service.replaceCredentials(userId, created.id(), CANARY_ID, CANARY_SECRET);

        assertThat(replaced.credentialRevision()).isEqualTo(2);
        assertThat(replaced.status()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
        assertThat(replaced.lastValidatedAt()).isNull();
        assertThat(replaced.toString()).doesNotContain(CANARY_ID, CANARY_SECRET);
    }

    @Test
    void deleteIsOwnerScopedAndAtomicallyScrubsEncryptedColumns() {
        var userId = UUID.randomUUID();
        var created = service.createToss(userId, CANARY_ID, CANARY_SECRET);

        service.delete(userId, created.id());

        var row = connectionRow(created.id());
        assertThat(row.status()).isEqualTo(BrokerConnectionStatus.DELETED.name());
        assertThat(row.credentialRevision()).isEqualTo(2);
        assertThat(row.credentialCiphertext()).isNull();
        assertThat(row.credentialNonce()).isNull();
        assertThat(row.credentialKeyVersion()).isNull();
        assertThat(row.lastValidatedAt()).isNull();
        assertThat(row.deletedAt()).isNotNull();
    }

    @Test
    void otherUsersIdAndAbsentIdAreIndistinguishableNotFound() {
        var ownerId = UUID.randomUUID();
        var otherUserId = UUID.randomUUID();
        var created = service.createToss(ownerId, "owner-client", "owner-secret");

        assertBrokerException(
                () -> service.replaceCredentials(otherUserId, created.id(), CANARY_ID, CANARY_SECRET),
                BrokerConnectionException.Code.NOT_FOUND);
        assertBrokerException(
                () -> service.replaceCredentials(ownerId, UUID.randomUUID(), CANARY_ID, CANARY_SECRET),
                BrokerConnectionException.Code.NOT_FOUND);
        assertBrokerException(
                () -> service.delete(otherUserId, created.id()),
                BrokerConnectionException.Code.NOT_FOUND);
        assertBrokerException(
                () -> service.delete(ownerId, UUID.randomUUID()),
                BrokerConnectionException.Code.NOT_FOUND);
    }

    @Test
    void cryptoAndFlushFailuresRollBackWholeMutation() {
        var userId = UUID.randomUUID();
        assertThatThrownBy(() -> service.createToss(userId, "", CANARY_SECRET))
                .hasMessageNotContaining(CANARY_SECRET);
        assertThat(userRows(userId)).isZero();

        var created = service.createToss(userId, "old-client", "old-secret");
        var before = connectionRow(created.id());

        assertThatThrownBy(() -> service.replaceCredentials(userId, created.id(), "", CANARY_SECRET))
                .hasMessageNotContaining(CANARY_SECRET);

        var after = connectionRow(created.id());
        assertThat(after.credentialRevision()).isEqualTo(before.credentialRevision());
        assertThat(after.credentialCiphertext()).isEqualTo(before.credentialCiphertext());
        assertThat(after.status()).isEqualTo(before.status());
    }

    @Test
    void concurrentReplacesAllowOneCommitAndOneConflict() throws Exception {
        var userId = UUID.randomUUID();
        var created = service.createToss(userId, "old-client", "old-secret");
        TestCipherConfiguration.blockEncryptions(2);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt(() ->
                    service.replaceCredentials(userId, created.id(), "client-a", "secret-a")));
            var second = executor.submit(attempt(() ->
                    service.replaceCredentials(userId, created.id(), "client-b", "secret-b")));
            TestCipherConfiguration.awaitBlockedEncryptions();
            TestCipherConfiguration.releaseEncryptions();

            assertThat(List.of(result(first), result(second))).containsExactlyInAnyOrder(
                    AttemptResult.COMMITTED,
                    AttemptResult.CONFLICT);
        } finally {
            TestCipherConfiguration.releaseEncryptions();
        }
        assertThat(connectionRow(created.id()).credentialRevision()).isEqualTo(2);
    }

    @Test
    void replaceDeleteRaceAppliesExactlyOneCommand() throws Exception {
        var userId = UUID.randomUUID();
        var created = service.createToss(userId, "old-client", "old-secret");
        TestCipherConfiguration.blockEncryptions(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var replace = executor.submit(attempt(() ->
                    service.replaceCredentials(userId, created.id(), CANARY_ID, CANARY_SECRET)));
            TestCipherConfiguration.awaitBlockedEncryptions();

            var delete = executor.submit(attempt(() -> service.delete(userId, created.id())));
            assertThat(result(delete)).isEqualTo(AttemptResult.COMMITTED);
            TestCipherConfiguration.releaseEncryptions();
            assertThat(result(replace)).isEqualTo(AttemptResult.CONFLICT);
        } finally {
            TestCipherConfiguration.releaseEncryptions();
        }
        var row = connectionRow(created.id());
        assertThat(row.credentialRevision()).isEqualTo(2);
        assertThat(row.status()).isEqualTo(BrokerConnectionStatus.DELETED.name());
    }

    private Callable<AttemptResult> attempt(ThrowingRunnable command) {
        return () -> {
            try {
                command.run();
                return AttemptResult.COMMITTED;
            } catch (BrokerConnectionException exception) {
                if (exception.code() == BrokerConnectionException.Code.CONFLICT) {
                    return AttemptResult.CONFLICT;
                }
                throw exception;
            }
        };
    }

    private AttemptResult result(java.util.concurrent.Future<AttemptResult> future) throws Exception {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private void assertBrokerException(ThrowingRunnable action, BrokerConnectionException.Code code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BrokerConnectionException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(code);
                    assertThat(exception).hasMessageNotContaining(CANARY_ID);
                    assertThat(exception).hasMessageNotContaining(CANARY_SECRET);
                });
    }

    private int userRows(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId);
    }

    private int activeConnectionCount(UUID userId) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM broker_connections
                WHERE user_id = ? AND broker_type = 'TOSS_INVEST' AND deleted_at IS NULL
                """, Integer.class, userId);
    }

    private String dbSecretText() {
        return jdbcTemplate.queryForObject("""
                SELECT coalesce(string_agg(
                    coalesce(encode(credential_ciphertext, 'escape'), '') || ' ' ||
                    coalesce(encode(credential_nonce, 'escape'), '') || ' ' ||
                    coalesce(credential_key_version::text, ''),
                    ' '
                ), '')
                FROM broker_connections
                """, String.class);
    }

    private ConnectionRow connectionRow(UUID id) {
        return jdbcTemplate.queryForObject("""
                SELECT status, credential_ciphertext, credential_nonce, credential_key_version,
                       credential_revision, last_validated_at, deleted_at
                FROM broker_connections
                WHERE id = ?
                """, (rs, rowNum) -> new ConnectionRow(
                rs.getString("status"),
                rs.getBytes("credential_ciphertext"),
                rs.getBytes("credential_nonce"),
                (Integer) rs.getObject("credential_key_version"),
                rs.getLong("credential_revision"),
                instant(rs.getObject("last_validated_at", OffsetDateTime.class)),
                instant(rs.getObject("deleted_at", OffsetDateTime.class))), id);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    @TestConfiguration
    static class TestCipherConfiguration {
        private static final BlockingSecureRandom SECURE_RANDOM = new BlockingSecureRandom();

        @Bean
        CredentialCipher credentialCipher() {
            return new CredentialCipher(
                    new CredentialKeyring(CredentialKeyringTest.properties(1, Map.of(
                            1, CredentialKeyringTest.key(11, 32)))),
                    SECURE_RANDOM);
        }

        @Bean
        BrokerConnectionService brokerConnectionService(
                UserAnchorRepository userAnchorRepository,
                BrokerConnectionRepository brokerConnectionRepository,
                CredentialCipher credentialCipher
        ) {
            return new BrokerConnectionService(userAnchorRepository, brokerConnectionRepository, credentialCipher);
        }

        static void blockEncryptions(int count) {
            SECURE_RANDOM.block(count);
        }

        static void awaitBlockedEncryptions() throws InterruptedException {
            SECURE_RANDOM.awaitBlocked();
        }

        static void releaseEncryptions() {
            SECURE_RANDOM.release();
        }
    }

    private static final class BlockingSecureRandom extends SecureRandom {

        private final AtomicReference<CountDownLatch> blocked = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> release = new AtomicReference<>();

        void block(int count) {
            blocked.set(new CountDownLatch(count));
            release.set(new CountDownLatch(1));
        }

        void awaitBlocked() throws InterruptedException {
            var latch = blocked.get();
            if (latch != null && !latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("encryptions did not block");
            }
        }

        void release() {
            var latch = release.getAndSet(null);
            if (latch != null) {
                latch.countDown();
            }
            blocked.set(null);
        }

        @Override
        public void nextBytes(byte[] bytes) {
            super.nextBytes(bytes);
            var blockedLatch = blocked.get();
            var releaseLatch = release.get();
            if (blockedLatch == null || releaseLatch == null) {
                return;
            }
            blockedLatch.countDown();
            try {
                if (!releaseLatch.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("encryption release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("encryption interrupted");
            }
        }
    }

    private record ConnectionRow(
            String status,
            byte[] credentialCiphertext,
            byte[] credentialNonce,
            Integer credentialKeyVersion,
            long credentialRevision,
            Instant lastValidatedAt,
            Instant deletedAt
    ) {
    }

    enum AttemptResult {
        COMMITTED,
        CONFLICT
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
