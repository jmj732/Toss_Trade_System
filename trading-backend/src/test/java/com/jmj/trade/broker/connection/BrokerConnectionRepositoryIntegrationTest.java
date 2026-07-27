package com.jmj.trade.broker.connection;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TradingBackendApplication.class)
class BrokerConnectionRepositoryIntegrationTest extends PostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-27T01:00:00Z");

    @Autowired
    private BrokerConnectionRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void cleanConnections() {
        jdbcTemplate.execute("TRUNCATE broker_connections, users");
    }

    @Test
    void saveAndLoadConnectionWithInitialStateAndVersion() {
        var userId = insertUser();
        var connectionId = UUID.randomUUID();

        repository.saveAndFlush(BrokerConnection.create(connectionId, userId, credentials(1), NOW));
        entityManager.clear();

        var stored = repository.findByIdAndUserId(connectionId, userId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
        assertThat(stored.getCredentialRevision()).isEqualTo(1);
        assertThat(stored.getVersion()).isZero();
        assertThat(stored.getEncryptedCredentials().ciphertext()).hasSize(17);
        assertThat(stored.getEncryptedCredentials().nonce()).hasSize(12);
    }

    @Test
    void findByIdAndUserIdHidesOtherUsers() {
        var ownerId = insertUser();
        var otherUserId = insertUser();
        var connectionId = UUID.randomUUID();
        repository.saveAndFlush(BrokerConnection.create(connectionId, ownerId, credentials(1), NOW));

        assertThat(repository.findByIdAndUserId(connectionId, otherUserId)).isEmpty();
        assertThat(repository.findByIdAndUserId(connectionId, ownerId)).isPresent();
    }

    @Test
    void metadataProjectionReadsRevisionWithoutCredentials() {
        var userId = insertUser();
        var connectionId = UUID.randomUUID();
        var connection = BrokerConnection.create(connectionId, userId, credentials(1), NOW);
        connection.markValidated(1, NOW.plusSeconds(1));
        connection.replaceCredentials(credentials(2), NOW.plusSeconds(2));
        repository.saveAndFlush(connection);
        entityManager.clear();

        var metadata = repository.findMetadataByIdAndUserId(connectionId, userId).orElseThrow();

        assertThat(metadata.id()).isEqualTo(connectionId);
        assertThat(metadata.userId()).isEqualTo(userId);
        assertThat(metadata.brokerType()).isEqualTo(BrokerType.TOSS_INVEST);
        assertThat(metadata.status()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
        assertThat(metadata.credentialRevision()).isEqualTo(2);
        assertThat(metadata.lastValidatedAt()).isNull();
        assertThat(metadata.deletedAt()).isNull();
    }

    @Test
    void exactRevisionProviderQueryUsesConnectionIdAndFailsAfterReplacementAndDeletion() {
        var userId = insertUser();
        var connectionId = UUID.randomUUID();
        var connection = BrokerConnection.create(connectionId, userId, credentials(1), NOW);
        repository.saveAndFlush(connection);

        assertThat(repository.findByIdAndBrokerTypeAndCredentialRevision(
                connectionId, BrokerType.TOSS_INVEST, 1)).isPresent();

        connection.replaceCredentials(credentials(2), NOW.plusSeconds(1));
        repository.saveAndFlush(connection);
        entityManager.clear();

        assertThat(repository.findByIdAndBrokerTypeAndCredentialRevision(
                connectionId, BrokerType.TOSS_INVEST, 1)).isEmpty();
        assertThat(repository.findByIdAndBrokerTypeAndCredentialRevision(
                connectionId, BrokerType.TOSS_INVEST, 2)).isPresent();

        var reloaded = repository.findById(connectionId).orElseThrow();
        reloaded.delete(NOW.plusSeconds(2));
        repository.saveAndFlush(reloaded);
        entityManager.clear();

        assertThat(repository.findByIdAndBrokerTypeAndCredentialRevision(
                connectionId, BrokerType.TOSS_INVEST, 2)).isEmpty();
    }

    @Test
    void exactRevisionProviderQueryDoesNotConflateRecreatedSameUserConnection() {
        var userId = insertUser();
        var deletedConnectionId = UUID.randomUUID();
        var deletedConnection = BrokerConnection.create(deletedConnectionId, userId, credentials(1), NOW);
        deletedConnection.delete(NOW.plusSeconds(1));
        repository.saveAndFlush(deletedConnection);
        entityManager.clear();

        var recreatedConnectionId = UUID.randomUUID();
        repository.saveAndFlush(BrokerConnection.create(recreatedConnectionId, userId, credentials(2), NOW.plusSeconds(2)));
        entityManager.clear();

        assertThat(repository.findByIdAndBrokerTypeAndCredentialRevision(
                deletedConnectionId, BrokerType.TOSS_INVEST, 1)).isEmpty();
        assertThat(repository.findByIdAndBrokerTypeAndCredentialRevision(
                recreatedConnectionId, BrokerType.TOSS_INVEST, 1)).isPresent();
    }

    @Test
    void concurrentSaveOfSameEntityRejectsOneUpdateWithOptimisticLocking() throws Exception {
        var userId = insertUser();
        var connectionId = UUID.randomUUID();
        persist(BrokerConnection.create(connectionId, userId, credentials(1), NOW));

        var loaded = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<AttemptResult> update = () -> validate(connectionId, loaded, start);
            var first = executor.submit(update);
            var second = executor.submit(update);

            assertThat(loaded.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(
                            AttemptResult.COMMITTED,
                            AttemptResult.OPTIMISTIC_LOCK_REJECTED);
        }
    }

    private AttemptResult validate(
            UUID connectionId,
            CountDownLatch loaded,
            CountDownLatch start
    ) throws InterruptedException {
        try {
            var connection = repository.findById(connectionId).orElseThrow();
            loaded.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent update start timed out");
            }
            connection.markValidated(1, NOW.plusSeconds(1));
            repository.saveAndFlush(connection);
            return AttemptResult.COMMITTED;
        } catch (RuntimeException exception) {
            if (hasOptimisticLockCause(exception)) {
                return AttemptResult.OPTIMISTIC_LOCK_REJECTED;
            }
            throw exception;
        }
    }

    private void persist(BrokerConnection connection) {
        try (var localEntityManager = entityManagerFactory.createEntityManager()) {
            var transaction = localEntityManager.getTransaction();
            transaction.begin();
            localEntityManager.persist(connection);
            transaction.commit();
        }
    }

    private boolean hasOptimisticLockCause(Throwable throwable) {
        for (var current = throwable; current != null; current = current.getCause()) {
            if (current instanceof ObjectOptimisticLockingFailureException) {
                return true;
            }
        }
        return false;
    }

    private UUID insertUser() {
        var id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id) VALUES (?)", id);
        return id;
    }

    private static EncryptedCredentials credentials(int keyVersion) {
        return new EncryptedCredentials(bytes(1, 17), bytes(2, 12), keyVersion);
    }

    private static byte[] bytes(int value, int size) {
        var bytes = new byte[size];
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    enum AttemptResult {
        COMMITTED,
        OPTIMISTIC_LOCK_REJECTED
    }
}
