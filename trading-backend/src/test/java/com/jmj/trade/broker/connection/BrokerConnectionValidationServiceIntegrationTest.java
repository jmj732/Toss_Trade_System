package com.jmj.trade.broker.connection;

import com.jmj.trade.PostgresIntegrationTest;
import com.jmj.trade.TradingBackendApplication;
import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TradingBackendApplication.class)
@Import({
        BrokerConnectionServiceIntegrationTest.TestCipherConfiguration.class,
        BrokerConnectionValidationServiceIntegrationTest.TestValidationConfiguration.class
})
class BrokerConnectionValidationServiceIntegrationTest extends PostgresIntegrationTest {

    private static final String CLIENT_ID = "validation-client-id";
    private static final String CLIENT_SECRET = "validation-client-secret";

    @Autowired
    private BrokerConnectionService connectionService;

    @Autowired
    private BrokerConnectionValidationService validationService;

    @Autowired
    private RecordingBrokerAdapter brokerAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanConnections() {
        jdbcTemplate.execute("TRUNCATE broker_connections, users");
        brokerAdapter.reset();
    }

    @Test
    void successCallsBrokerOutsideTransactionAndMarksSameRevisionActive() {
        var userId = UUID.randomUUID();
        var created = connectionService.createToss(userId, CLIENT_ID, CLIENT_SECRET);

        var validated = validationService.validateToss(userId, created.id());

        assertThat(brokerAdapter.connectionRefs()).containsExactly(new BrokerConnectionRef(created.id()));
        assertThat(brokerAdapter.transactionActiveDuringCalls()).containsExactly(false);
        assertThat(validated.status()).isEqualTo(BrokerConnectionStatus.ACTIVE);
        assertThat(validated.credentialRevision()).isEqualTo(1);
        assertThat(connectionRow(created.id()).status()).isEqualTo(BrokerConnectionStatus.ACTIVE);
        assertThat(connectionRow(created.id()).credentialRevision()).isEqualTo(1);
    }

    @Test
    void brokerAuthenticationAndAuthorizationFailuresMarkInvalidAndExposeStableValidationFailure() {
        for (var category : List.of(BrokerErrorCategory.AUTHENTICATION, BrokerErrorCategory.AUTHORIZATION)) {
            cleanConnections();
            var userId = UUID.randomUUID();
            var created = connectionService.createToss(userId, CLIENT_ID, CLIENT_SECRET);
            brokerAdapter.respondWith(() -> {
                throw brokerFailure(category);
            });

            assertThatThrownBy(() -> validationService.validateToss(userId, created.id()))
                    .isInstanceOfSatisfying(BrokerConnectionException.class, exception -> {
                        assertThat(exception.code()).isEqualTo(BrokerConnectionException.Code.VALIDATION_FAILED);
                        assertThat(exception).hasMessage(BrokerConnectionException.Code.VALIDATION_FAILED.publicCode());
                        assertThat(exception).hasMessageNotContaining(CLIENT_ID);
                        assertThat(exception).hasMessageNotContaining(CLIENT_SECRET);
                    });

            var row = connectionRow(created.id());
            assertThat(row.status()).isEqualTo(BrokerConnectionStatus.INVALID);
            assertThat(row.credentialRevision()).isEqualTo(1);
            assertThat(brokerAdapter.connectionRefs()).containsExactly(new BrokerConnectionRef(created.id()));
        }
    }

    @Test
    void temporaryBrokerAndCredentialFailuresDoNotMutateConnection() {
        for (var category : List.of(
                BrokerErrorCategory.NETWORK,
                BrokerErrorCategory.RATE_LIMITED,
                BrokerErrorCategory.BROKER_UNAVAILABLE,
                BrokerErrorCategory.TEMPORARY)) {
            cleanConnections();
            var userId = UUID.randomUUID();
            var created = connectionService.createToss(userId, CLIENT_ID, CLIENT_SECRET);
            brokerAdapter.respondWith(() -> {
                throw brokerFailure(category);
            });

            assertThatThrownBy(() -> validationService.validateToss(userId, created.id()))
                    .isInstanceOf(BrokerException.class)
                    .isInstanceOfSatisfying(BrokerException.class, exception ->
                            assertThat(exception.category()).isEqualTo(category));

            assertUnverifiedRevision(created.id(), 1);
        }

        cleanConnections();
        var userId = UUID.randomUUID();
        var created = connectionService.createToss(userId, CLIENT_ID, CLIENT_SECRET);
        brokerAdapter.respondWith(() -> {
            throw new CredentialUnavailableException();
        });

        assertThatThrownBy(() -> validationService.validateToss(userId, created.id()))
                .isInstanceOf(CredentialUnavailableException.class);
        assertUnverifiedRevision(created.id(), 1);
    }

    @Test
    void replacementDuringExternalCallConflictsAndLeavesNewRevisionUnverified() {
        var userId = UUID.randomUUID();
        var created = connectionService.createToss(userId, "old-client", "old-secret");
        brokerAdapter.respondWith(() -> {
            connectionService.replaceCredentials(userId, created.id(), CLIENT_ID, CLIENT_SECRET);
            return success();
        });

        assertThatThrownBy(() -> validationService.validateToss(userId, created.id()))
                .isInstanceOfSatisfying(BrokerConnectionException.class, exception ->
                        assertThat(exception.code()).isEqualTo(BrokerConnectionException.Code.CONFLICT));

        assertUnverifiedRevision(created.id(), 2);
    }

    @Test
    void otherUsersConnectionAndMissingIdAreNotFoundWithNoBrokerCall() {
        var ownerId = UUID.randomUUID();
        var otherUserId = UUID.randomUUID();
        var created = connectionService.createToss(ownerId, CLIENT_ID, CLIENT_SECRET);

        assertNotFound(() -> validationService.validateToss(otherUserId, created.id()));
        assertNotFound(() -> validationService.validateToss(ownerId, UUID.randomUUID()));
        assertThat(brokerAdapter.connectionRefs()).isEmpty();
    }

    private void assertNotFound(ThrowingRunnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BrokerConnectionException.class, exception ->
                        assertThat(exception.code()).isEqualTo(BrokerConnectionException.Code.NOT_FOUND));
    }

    private void assertUnverifiedRevision(UUID id, long revision) {
        var row = connectionRow(id);
        assertThat(row.status()).isEqualTo(BrokerConnectionStatus.UNVERIFIED);
        assertThat(row.credentialRevision()).isEqualTo(revision);
    }

    private ConnectionRow connectionRow(UUID id) {
        return jdbcTemplate.queryForObject("""
                SELECT status, credential_revision, last_validated_at
                FROM broker_connections
                WHERE id = ?
                """, (rs, rowNum) -> new ConnectionRow(
                BrokerConnectionStatus.valueOf(rs.getString("status")),
                rs.getLong("credential_revision"),
                instant(rs.getObject("last_validated_at", OffsetDateTime.class))), id);
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static BrokerResponse<List<BrokerAccountView>> success() {
        return new BrokerResponse<>(List.of(), new BrokerCallMetadata("validation-request", Instant.now(), Optional.empty()));
    }

    private static BrokerException brokerFailure(BrokerErrorCategory category) {
        return new BrokerException(
                category,
                category == BrokerErrorCategory.BROKER_UNAVAILABLE ? 503 : null,
                "broker-code",
                "request-id",
                category == BrokerErrorCategory.RATE_LIMITED ? Duration.ofSeconds(1) : null,
                category != BrokerErrorCategory.AUTHENTICATION && category != BrokerErrorCategory.AUTHORIZATION,
                "broker validation failed");
    }

    @TestConfiguration
    static class TestValidationConfiguration {

        @Bean
        RecordingBrokerAdapter brokerAdapter() {
            return new RecordingBrokerAdapter();
        }

        @Bean
        BrokerConnectionTransactions brokerConnectionTransactions(BrokerConnectionRepository repository) {
            return new BrokerConnectionTransactions(repository);
        }

        @Bean
        BrokerConnectionValidationService brokerConnectionValidationService(
                BrokerConnectionTransactions transactions,
                BrokerAdapter brokerAdapter
        ) {
            return new BrokerConnectionValidationService(transactions, brokerAdapter);
        }
    }

    private static final class RecordingBrokerAdapter implements BrokerAdapter {

        private final java.util.ArrayList<BrokerConnectionRef> connectionRefs = new java.util.ArrayList<>();
        private final java.util.ArrayList<Boolean> transactionActiveDuringCalls = new java.util.ArrayList<>();
        private Supplier<BrokerResponse<List<BrokerAccountView>>> response = BrokerConnectionValidationServiceIntegrationTest::success;

        void respondWith(Supplier<BrokerResponse<List<BrokerAccountView>>> response) {
            this.response = response;
        }

        List<BrokerConnectionRef> connectionRefs() {
            return List.copyOf(connectionRefs);
        }

        List<Boolean> transactionActiveDuringCalls() {
            return List.copyOf(transactionActiveDuringCalls);
        }

        void reset() {
            connectionRefs.clear();
            transactionActiveDuringCalls.clear();
            response = BrokerConnectionValidationServiceIntegrationTest::success;
        }

        @Override
        public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
            connectionRefs.add(connection);
            transactionActiveDuringCalls.add(TransactionSynchronizationManager.isActualTransactionActive());
            return response.get();
        }

        @Override
        public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(BrokerAccountRef account, Currency currency) {
            throw new UnsupportedOperationException();
        }
    }

    private interface ThrowingRunnable {
        void run();
    }

    private record ConnectionRow(BrokerConnectionStatus status, long credentialRevision, Instant lastValidatedAt) {
    }
}
