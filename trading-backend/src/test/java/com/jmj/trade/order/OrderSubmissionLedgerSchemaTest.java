package com.jmj.trade.order;

import com.jmj.trade.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderSubmissionLedgerSchemaTest extends PostgresIntegrationTest {

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(2026, 7, 27, 9, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime EXPIRES_AT = CREATED_AT.plusMinutes(10);

    private Flyway flyway;

    @BeforeEach
    void migrateFreshSchema() {
        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();

        flyway.clean();
        flyway.migrate();
    }

    @Test
    void flywayCreatesOrderSubmissionLedgerSchema() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("32");
    }

    @Test
    void accountScopedClientOrderIdBelongsToOneIntentAndBodyHash() throws SQLException {
        var accountId = insertAccount();
        var firstIntentId = insertIntent(accountId);
        var otherIntentId = insertIntent(accountId);
        insertCanonicalKey(accountId, firstIntentId, "client-1", "hash-1", EXPIRES_AT);

        assertThatThrownBy(() -> insertCanonicalKey(
                accountId,
                otherIntentId,
                "client-1",
                "hash-1",
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertAttempt(
                UUID.randomUUID(),
                otherIntentId,
                accountId,
                1,
                "key-2",
                "client-1",
                "hash-1",
                null,
                CREATED_AT,
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertAttempt(
                UUID.randomUUID(),
                firstIntentId,
                accountId,
                1,
                "key-3",
                "client-1",
                "hash-2",
                null,
                CREATED_AT,
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void submissionAttemptInsertMustStartAtCreatedState() throws SQLException {
        var accountId = insertAccount();
        var createdIntentId = insertIntent(accountId);
        insertCanonicalKey(accountId, createdIntentId, "client-1", "hash-1", EXPIRES_AT);

        assertThatCode(() -> insertAttemptWithLifecycleColumns(
                UUID.randomUUID(),
                createdIntentId,
                accountId,
                "key-1",
                "client-1",
                "hash-1",
                null,
                "CREATED",
                null,
                null,
                null,
                0))
                .doesNotThrowAnyException();

        var ackIntentId = insertIntent(accountId);
        insertCanonicalKey(accountId, ackIntentId, "client-2", "hash-2", EXPIRES_AT);
        var mismatchedBrokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                ackIntentId,
                accountId,
                "broker-1",
                "other-client",
                "PENDING");

        assertThatThrownBy(() -> insertAttemptWithLifecycleColumns(
                UUID.randomUUID(),
                ackIntentId,
                accountId,
                "key-2",
                "client-2",
                "hash-2",
                mismatchedBrokerOrderId,
                "ACKNOWLEDGED",
                "{}",
                CREATED_AT.plusSeconds(1),
                CREATED_AT.plusSeconds(2),
                1))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void differentAccountsMayReuseClientOrderId() throws SQLException {
        var firstAccountId = insertAccount();
        var secondAccountId = insertAccount();
        var firstIntentId = insertIntent(firstAccountId);
        var secondIntentId = insertIntent(secondAccountId);

        insertCanonicalKey(firstAccountId, firstIntentId, "client-1", "hash-1", EXPIRES_AT);
        insertCanonicalKey(secondAccountId, secondIntentId, "client-1", "hash-2", EXPIRES_AT);

        insertAttempt(
                UUID.randomUUID(),
                firstIntentId,
                firstAccountId,
                1,
                "key-1",
                "client-1",
                "hash-1",
                null,
                CREATED_AT,
                EXPIRES_AT);
        insertAttempt(
                UUID.randomUUID(),
                secondIntentId,
                secondAccountId,
                1,
                "key-2",
                "client-1",
                "hash-2",
                null,
                CREATED_AT,
                EXPIRES_AT);
    }

    @Test
    void canonicalKeysAttemptsAndBrokerOrdersMustMatchIntentAccount() throws SQLException {
        var accountId = insertAccount();
        var otherAccountId = insertAccount();
        var intentId = insertIntent(accountId);

        assertThatThrownBy(() -> insertCanonicalKey(
                otherAccountId,
                intentId,
                "client-1",
                "hash-1",
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);

        assertThatThrownBy(() -> insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                otherAccountId,
                "broker-1",
                "PENDING"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void retryRequiresSameIdentityNoLaterThanExpiryAndLatestRetryDecision() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var parentId = UUID.randomUUID();
        insertCanonicalKey(accountId, intentId, "client-1", "hash-1", EXPIRES_AT);
        createRetryAllowedParent(accountId, intentId, parentId, "client-1", "hash-1", "key-1");

        assertThatThrownBy(() -> insertAttempt(
                UUID.randomUUID(),
                intentId,
                accountId,
                2,
                "key-2",
                "client-1",
                "hash-1",
                parentId,
                CREATED_AT.plusSeconds(1),
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);

        insertAttempt(
                UUID.randomUUID(),
                intentId,
                accountId,
                2,
                "key-2",
                "client-1",
                "hash-1",
                parentId,
                CREATED_AT.plusMinutes(9),
                EXPIRES_AT);

        assertThatThrownBy(() -> insertAttempt(
                UUID.randomUUID(),
                intentId,
                accountId,
                3,
                "key-3",
                "client-1",
                "hash-1",
                parentId,
                CREATED_AT.plusMinutes(9),
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void retryRequiresRetryAllowedParentStatusNotOnlyForgedRetryDecision() throws SQLException {
        var accountId = insertAccount();

        assertRetryRejectedForForgedDecisionParent(
                accountId,
                insertIntent(accountId),
                "ack-parent",
                "ack-hash",
                "ack-key",
                "ACKNOWLEDGED");
        assertRetryRejectedForForgedDecisionParent(
                accountId,
                insertIntent(accountId),
                "rejected-parent",
                "rejected-hash",
                "rejected-key",
                "BROKER_REJECTED");
        assertRetryRejectedForForgedDecisionParent(
                accountId,
                insertIntent(accountId),
                "failed-parent",
                "failed-hash",
                "failed-key",
                "RECONCILIATION_FAILED");
    }

    @Test
    void submissionIdempotencyKeysAreImmutableAndExpireExactlyTenMinutesAfterCreation()
            throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        insertCanonicalKey(accountId, intentId, "client-1", "hash-1", EXPIRES_AT);

        assertThatThrownBy(() -> execute("""
                UPDATE submission_idempotency_keys
                   SET request_body_hash = 'changed'
                 WHERE broker_account_id = ? AND client_order_id = 'client-1'
                """, accountId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
                DELETE FROM submission_idempotency_keys
                 WHERE broker_account_id = ? AND client_order_id = 'client-1'
                """, accountId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertCanonicalKey(
                accountId,
                intentId,
                "client-2",
                "hash-2",
                CREATED_AT.plusMinutes(10).plusSeconds(1)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void brokerOrderIdentityFieldsAreImmutableButStatusProjectionMayChange()
            throws SQLException {
        var accountId = insertAccount();
        var otherAccountId = insertAccount();
        var intentId = insertIntent(accountId);
        var otherIntentId = insertIntent(otherAccountId);
        var brokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "broker-1",
                "client-1",
                "PENDING");

        execute("UPDATE broker_orders SET status = 'PARTIALLY_FILLED' WHERE id = ?", brokerOrderId);
        assertThat(queryBrokerOrderStatus(brokerOrderId)).isEqualTo("PARTIALLY_FILLED");

        assertThatThrownBy(() -> execute(
                "UPDATE broker_orders SET order_intent_id = ? WHERE id = ?",
                otherIntentId,
                brokerOrderId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE broker_orders SET broker_account_id = ? WHERE id = ?",
                otherAccountId,
                brokerOrderId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE broker_orders SET broker_order_id = 'changed' WHERE id = ?",
                brokerOrderId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE broker_orders SET client_order_id = 'changed' WHERE id = ?",
                brokerOrderId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void brokerOrdersRequireClientOrderIdForNewRowsButLegacyNullSurvives()
            throws SQLException {
        flyway.clean();
        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .target("1")
                .load();
        flyway.migrate();
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var legacyBrokerOrderId = insertLegacyBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "legacy-null-client",
                "PENDING");

        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.migrate();

        assertThat(queryBrokerOrderClientOrderId(legacyBrokerOrderId)).isNull();
        assertThatThrownBy(() -> insertLegacyBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "new-null-client",
                "PENDING"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void brokerOrderReplacementCannotPointToItselfOrChangeAfterInsert()
            throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var brokerOrderId = UUID.randomUUID();

        assertThatThrownBy(() -> insertBrokerOrder(
                brokerOrderId,
                intentId,
                accountId,
                "broker-1",
                "client-1",
                "PENDING",
                brokerOrderId))
                .isInstanceOf(SQLException.class);

        var first = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "broker-2",
                "client-2",
                "PENDING");
        var replacement = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "broker-3",
                "client-3",
                "PENDING",
                first);

        assertThatThrownBy(() -> execute(
                "UPDATE broker_orders SET replaces_broker_order_id = NULL WHERE id = ?",
                replacement))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void reconciliationDecisionInsertDefensesValidateMatchedBrokerOrderAndCompleteness()
            throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");
        var matchingBrokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "broker-1",
                "client-1",
                "PENDING");
        var mismatchedBrokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "broker-2",
                "other-client",
                "PENDING");

        assertThatThrownBy(() -> recordReconciliationCheck(
                attemptId,
                intentId,
                1,
                "BROKER_ORDER_FOUND",
                mismatchedBrokerOrderId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> recordIncompleteReconciliationCheck(
                attemptId,
                intentId,
                1,
                "RETRY_SAME_KEY_ALLOWED",
                false,
                true,
                true,
                null))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> recordIncompleteReconciliationCheck(
                attemptId,
                intentId,
                1,
                "RETRY_SAME_KEY_ALLOWED",
                true,
                true,
                true,
                matchingBrokerOrderId))
                .isInstanceOf(SQLException.class);

        recordReconciliationCheck(
                attemptId,
                intentId,
                1,
                "BROKER_ORDER_FOUND",
                matchingBrokerOrderId);
    }

    @Test
    void retryAtExactlyTheExpiryInstantAndAfterItFails() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var parentId = UUID.randomUUID();
        insertCanonicalKey(accountId, intentId, "client-1", "hash-1", EXPIRES_AT);
        createRetryAllowedParent(accountId, intentId, parentId, "client-1", "hash-1", "key-1");

        assertThatThrownBy(() -> insertAttempt(
                UUID.randomUUID(),
                intentId,
                accountId,
                2,
                "key-2",
                "client-1",
                "hash-1",
                parentId,
                EXPIRES_AT,
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertAttempt(
                UUID.randomUUID(),
                intentId,
                accountId,
                2,
                "key-3",
                "client-1",
                "hash-1",
                parentId,
                EXPIRES_AT.plusNanos(1),
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void retrySameKeyReconciliationMustBeCheckedBeforeIdempotencyExpiry() throws SQLException {
        var accountId = insertAccount();
        var beforeExpiryIntentId = insertIntent(accountId);
        var atExpiryIntentId = insertIntent(accountId);
        var afterExpiryIntentId = insertIntent(accountId);
        var beforeExpiryAttemptId = createInitialAttempt(
                accountId,
                beforeExpiryIntentId,
                "client-1",
                "hash-1",
                "key-1");
        var atExpiryAttemptId = createInitialAttempt(
                accountId,
                atExpiryIntentId,
                "client-2",
                "hash-2",
                "key-2");
        var afterExpiryAttemptId = createInitialAttempt(
                accountId,
                afterExpiryIntentId,
                "client-3",
                "hash-3",
                "key-3");

        recordReconciliationCheck(
                beforeExpiryAttemptId,
                beforeExpiryIntentId,
                1,
                "RETRY_SAME_KEY_ALLOWED",
                null,
                EXPIRES_AT.minusSeconds(1));
        assertThatThrownBy(() -> recordReconciliationCheck(
                atExpiryAttemptId,
                atExpiryIntentId,
                1,
                "RETRY_SAME_KEY_ALLOWED",
                null,
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> recordReconciliationCheck(
                afterExpiryAttemptId,
                afterExpiryIntentId,
                1,
                "RETRY_SAME_KEY_ALLOWED",
                null,
                EXPIRES_AT.plusSeconds(1)))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void retryAttemptMayConfirmBrokerOrderButNotCrossIntentBrokerOrder() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var otherIntentId = insertIntent(accountId);
        var firstAttemptId = UUID.randomUUID();
        var secondAttemptId = UUID.randomUUID();
        insertCanonicalKey(accountId, intentId, "client-1", "hash-1", EXPIRES_AT);
        createRetryAllowedParent(accountId, intentId, firstAttemptId, "client-1", "hash-1", "key-1");
        insertAttempt(
                secondAttemptId,
                intentId,
                accountId,
                2,
                "key-2",
                "client-1",
                "hash-1",
                firstAttemptId,
                CREATED_AT.plusMinutes(1),
                EXPIRES_AT);
        var brokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "broker-1",
                "client-1",
                "PENDING");

        acknowledgeAttempt(secondAttemptId, brokerOrderId);

        var otherAttemptId = createInitialAttempt(accountId, otherIntentId, "client-3", "hash-3", "key-3");
        assertThatThrownBy(() -> acknowledgeAttempt(otherAttemptId, brokerOrderId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void reconciliationChecksAreSequencedAndAppendOnly() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");

        recordReconciliationCheck(attemptId, intentId, 1, "MANUAL_REVIEW_REQUIRED", null);

        assertThatThrownBy(() -> recordReconciliationCheck(
                attemptId,
                intentId,
                1,
                "MANUAL_REVIEW_REQUIRED",
                null))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE reconciliation_checks SET result_hash = 'changed' WHERE submission_attempt_id = ?",
                attemptId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "DELETE FROM reconciliation_checks WHERE submission_attempt_id = ?",
                attemptId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void reconciliationChecksMustMatchAttemptCounterWithoutGaps() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");

        assertThatThrownBy(() -> recordReconciliationCheck(
                attemptId,
                intentId,
                2,
                "MANUAL_REVIEW_REQUIRED",
                null))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> executeInTransaction(
                "UPDATE submission_attempts SET last_reconciliation_check_number = 1 WHERE id = ?",
                attemptId))
                .isInstanceOf(SQLException.class);

        recordReconciliationCheck(attemptId, intentId, 1, "RETRY_SAME_KEY_ALLOWED", null);
        recordReconciliationCheck(attemptId, intentId, 2, "MANUAL_REVIEW_REQUIRED", null);

        assertThat(queryAttemptCheckNumber(attemptId)).isEqualTo(2);
    }

    @Test
    void brokerOrderStatusCheckAllowsLegacyInvalidRowsButRejectsNewInvalidWrites()
            throws SQLException {
        flyway.clean();
        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .target("1")
                .load();
        flyway.migrate();
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var legacyBrokerOrderId = insertLegacyBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "legacy-invalid",
                "LEGACY_STATUS");

        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.migrate();

        assertThat(queryBrokerOrderStatus(legacyBrokerOrderId)).isEqualTo("LEGACY_STATUS");
        assertThatThrownBy(() -> insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "new-invalid",
                "NOT_A_BROKER_STATUS"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE broker_orders SET status = 'NOT_A_BROKER_STATUS' WHERE id = ?",
                legacyBrokerOrderId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void brokerOrderIntentAccountConstraintAllowsLegacyMismatchButRejectsNewMismatch()
            throws SQLException {
        flyway.clean();
        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .target("1")
                .load();
        flyway.migrate();
        var accountId = insertAccount();
        var otherAccountId = insertAccount();
        var intentId = insertIntent(accountId);
        var legacyBrokerOrderId = insertLegacyBrokerOrder(
                UUID.randomUUID(),
                intentId,
                otherAccountId,
                "legacy-mismatch",
                "PENDING");

        flyway = Flyway.configure()
                .dataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword())
                .cleanDisabled(false)
                .load();
        flyway.migrate();

        assertThat(queryBrokerOrderStatus(legacyBrokerOrderId)).isEqualTo("PENDING");
        assertThatThrownBy(() -> insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                otherAccountId,
                "new-mismatch",
                "PENDING"))
                .isInstanceOf(SQLException.class);
        var validBrokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "valid-account",
                "PENDING");
        assertThatThrownBy(() -> execute(
                "UPDATE broker_orders SET broker_account_id = ? WHERE id = ?",
                otherAccountId,
                validBrokerOrderId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void attemptsProtectIdentityFieldsAndConstrainTransitions() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");

        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET client_order_id = 'changed' WHERE id = ?",
                attemptId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET status = 'ACKNOWLEDGED' WHERE id = ?",
                attemptId))
                .isInstanceOf(SQLException.class);

        execute("UPDATE submission_attempts SET status = 'DISPATCHING', started_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(1), attemptId);
        execute("UPDATE submission_attempts SET status = 'UNKNOWN', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(2), attemptId);
        execute("UPDATE submission_attempts SET status = 'RECONCILING' WHERE id = ?", attemptId);
        recordReconciliationCheck(attemptId, intentId, 1, "RETRY_SAME_KEY_ALLOWED", null);
        execute("UPDATE submission_attempts SET status = 'RECONCILED_NO_MATCH', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(3), attemptId);
    }

    @Test
    void acknowledgedAttemptsRequireSameIntentBrokerOrder() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");

        assertThatCode(() -> execute("""
                UPDATE submission_attempts
                   SET status = 'DISPATCHING',
                       started_at = ?
                 WHERE id = ?
                """, CREATED_AT.plusSeconds(1), attemptId))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> execute("""
                UPDATE submission_attempts
                   SET status = 'ACKNOWLEDGED',
                       finished_at = ?
                 WHERE id = ?
                """, CREATED_AT.plusSeconds(2), attemptId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void directDispatchAcknowledgementRequiresBrokerOrderClientOrderIdMatch() throws SQLException {
        var accountId = insertAccount();
        var matchingIntentId = insertIntent(accountId);
        var mismatchedIntentId = insertIntent(accountId);
        var matchingAttemptId = createInitialAttempt(accountId, matchingIntentId, "client-1", "hash-1", "key-1");
        var mismatchedAttemptId = createInitialAttempt(accountId, mismatchedIntentId, "client-2", "hash-2", "key-2");
        var matchingBrokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                matchingIntentId,
                accountId,
                "broker-1",
                "client-1",
                "PENDING");
        var mismatchedBrokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                mismatchedIntentId,
                accountId,
                "broker-2",
                "other-client",
                "PENDING");

        acknowledgeAttempt(matchingAttemptId, matchingBrokerOrderId);

        assertThatThrownBy(() -> acknowledgeAttempt(mismatchedAttemptId, mismatchedBrokerOrderId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void acknowledgedAttemptCannotRetargetConfirmedBrokerOrder() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");
        var brokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "broker-1",
                "client-1",
                "PENDING");
        var otherBrokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                intentId,
                accountId,
                "broker-2",
                "client-1",
                "PENDING");

        acknowledgeAttempt(attemptId, brokerOrderId);

        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET confirmed_broker_order_id = ? WHERE id = ?",
                otherBrokerOrderId,
                attemptId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void submissionAttemptTimestampsAreSetOnlyOnOwningTransitions() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");

        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET started_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(1),
                attemptId))
                .isInstanceOf(SQLException.class);
        execute("UPDATE submission_attempts SET status = 'DISPATCHING', started_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(1), attemptId);
        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET started_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(2),
                attemptId))
                .isInstanceOf(SQLException.class);
        execute("UPDATE submission_attempts SET status = 'UNKNOWN', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(3), attemptId);
        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(4),
                attemptId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void submissionAttemptTimestampsMustBeOrderedAndReconciliationFinishMustAdvanceLifecycle()
            throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");

        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET status = 'DISPATCHING', started_at = ? WHERE id = ?",
                CREATED_AT.minusSeconds(1),
                attemptId))
                .isInstanceOf(SQLException.class);
        execute("UPDATE submission_attempts SET status = 'DISPATCHING', started_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(1), attemptId);
        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET status = 'UNKNOWN', finished_at = ? WHERE id = ?",
                CREATED_AT,
                attemptId))
                .isInstanceOf(SQLException.class);
        execute("UPDATE submission_attempts SET status = 'UNKNOWN', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(2), attemptId);
        execute("UPDATE submission_attempts SET status = 'RECONCILING' WHERE id = ?", attemptId);

        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET status = 'RECONCILED_NO_MATCH' WHERE id = ?",
                attemptId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET status = 'RECONCILED_NO_MATCH', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(1),
                attemptId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET status = 'RECONCILED_NO_MATCH', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(2),
                attemptId))
                .isInstanceOf(SQLException.class);
        recordReconciliationCheck(attemptId, intentId, 1, "RETRY_SAME_KEY_ALLOWED", null);
        execute("UPDATE submission_attempts SET status = 'RECONCILED_NO_MATCH', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(3), attemptId);
    }

    @Test
    void reconciliationTerminalTransitionsRequireLatestMatchingCheckDecision() throws SQLException {
        var accountId = insertAccount();

        var noCheckIntentId = insertIntent(accountId);
        var noCheckAttemptId = createReconcilingAttempt(accountId, noCheckIntentId, "client-1", "hash-1", "key-1");
        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET status = 'RECONCILED_NO_MATCH', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(3),
                noCheckAttemptId))
                .isInstanceOf(SQLException.class);

        var mismatchIntentId = insertIntent(accountId);
        var mismatchAttemptId = createReconcilingAttempt(accountId, mismatchIntentId, "client-2", "hash-2", "key-2");
        recordReconciliationCheck(mismatchAttemptId, mismatchIntentId, 1, "MANUAL_REVIEW_REQUIRED", null);
        assertThatThrownBy(() -> execute(
                "UPDATE submission_attempts SET status = 'RECONCILED_NO_MATCH', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(3),
                mismatchAttemptId))
                .isInstanceOf(SQLException.class);

        var acknowledgedIntentId = insertIntent(accountId);
        var acknowledgedAttemptId = createReconcilingAttempt(
                accountId,
                acknowledgedIntentId,
                "client-3",
                "hash-3",
                "key-3");
        var brokerOrderId = insertBrokerOrder(
                UUID.randomUUID(),
                acknowledgedIntentId,
                accountId,
                "broker-1",
                "client-3",
                "PENDING");
        recordReconciliationCheck(
                acknowledgedAttemptId,
                acknowledgedIntentId,
                1,
                "BROKER_ORDER_FOUND",
                brokerOrderId);
        execute("""
                UPDATE submission_attempts
                   SET status = 'ACKNOWLEDGED',
                       confirmed_broker_order_id = ?,
                       finished_at = ?
                 WHERE id = ?
                """, brokerOrderId, CREATED_AT.plusSeconds(3), acknowledgedAttemptId);

        var failedIntentId = insertIntent(accountId);
        var failedAttemptId = createReconcilingAttempt(accountId, failedIntentId, "client-4", "hash-4", "key-4");
        recordReconciliationCheck(failedAttemptId, failedIntentId, 1, "MANUAL_REVIEW_REQUIRED", null);
        execute("UPDATE submission_attempts SET status = 'RECONCILIATION_FAILED', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(3), failedAttemptId);
    }

    @Test
    void dispatchEvidenceChangesOnlyDuringLifecycleTransitions() throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");

        execute("""
                UPDATE submission_attempts
                   SET status = 'DISPATCHING',
                       started_at = ?,
                       dispatch_evidence = '{"phase":"dispatch"}'::jsonb
                 WHERE id = ?
                """, CREATED_AT.plusSeconds(1), attemptId);

        assertThatThrownBy(() -> execute("""
                UPDATE submission_attempts
                   SET dispatch_evidence = '{"phase":"rewrite"}'::jsonb
                 WHERE id = ?
                """, attemptId))
                .isInstanceOf(SQLException.class);
        execute("""
                UPDATE submission_attempts
                   SET status = 'BROKER_REJECTED',
                       finished_at = ?,
                       dispatch_evidence = '{"phase":"rejected"}'::jsonb
                 WHERE id = ?
                """, CREATED_AT.plusSeconds(2), attemptId);
        assertThatThrownBy(() -> execute("""
                UPDATE submission_attempts
                   SET dispatch_evidence = '{"phase":"terminal-rewrite"}'::jsonb
                 WHERE id = ?
                """, attemptId))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void submissionAuditIsAppendOnlyAndOutboxAllowsOnlyDeliveryMetadataUpdates()
            throws SQLException {
        var accountId = insertAccount();
        var intentId = insertIntent(accountId);
        var attemptId = createInitialAttempt(accountId, intentId, "client-1", "hash-1", "key-1");
        var auditId = UUID.randomUUID();
        var outboxId = UUID.randomUUID();
        execute("""
                INSERT INTO order_submission_audit_logs (
                    id, order_intent_id, aggregate_type, aggregate_id, action, actor, payload,
                    occurred_at
                ) VALUES (?, ?, 'SubmissionAttempt', ?, 'CREATED', 'tester', '{}'::jsonb, ?)
                """, auditId, intentId, attemptId, CREATED_AT);
        execute("""
                INSERT INTO order_submission_outbox_events (
                    id, order_intent_id, aggregate_type, aggregate_id, event_type, actor, payload,
                    created_at
                ) VALUES (?, ?, 'SubmissionAttempt', ?, 'SubmissionAttemptCreated',
                    'tester', '{}'::jsonb, ?)
                """, outboxId, intentId, attemptId, CREATED_AT);

        execute("""
                UPDATE order_submission_outbox_events
                   SET published_at = ?, attempts = attempts + 1
                 WHERE id = ?
                """, CREATED_AT.plusSeconds(1), outboxId);

        assertThatThrownBy(() -> execute(
                "UPDATE order_submission_audit_logs SET actor = 'other' WHERE id = ?",
                auditId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "DELETE FROM order_submission_audit_logs WHERE id = ?",
                auditId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE order_submission_outbox_events SET actor = 'other' WHERE id = ?",
                outboxId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "UPDATE order_submission_outbox_events SET payload = '{\"changed\":true}'::jsonb WHERE id = ?",
                outboxId))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute(
                "DELETE FROM order_submission_outbox_events WHERE id = ?",
                outboxId))
                .isInstanceOf(SQLException.class);
    }

    private UUID createInitialAttempt(
            UUID accountId,
            UUID intentId,
            String clientOrderId,
            String requestBodyHash,
            String internalKey
    ) throws SQLException {
        var attemptId = UUID.randomUUID();
        insertCanonicalKey(accountId, intentId, clientOrderId, requestBodyHash, EXPIRES_AT);
        insertAttempt(
                attemptId,
                intentId,
                accountId,
                1,
                internalKey,
                clientOrderId,
                requestBodyHash,
                null,
                CREATED_AT,
                EXPIRES_AT);
        return attemptId;
    }

    private UUID createReconcilingAttempt(
            UUID accountId,
            UUID intentId,
            String clientOrderId,
            String requestBodyHash,
            String internalKey
    ) throws SQLException {
        var attemptId = createInitialAttempt(accountId, intentId, clientOrderId, requestBodyHash, internalKey);
        execute("UPDATE submission_attempts SET status = 'DISPATCHING', started_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(1), attemptId);
        execute("UPDATE submission_attempts SET status = 'UNKNOWN', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(2), attemptId);
        execute("UPDATE submission_attempts SET status = 'RECONCILING' WHERE id = ?", attemptId);
        return attemptId;
    }

    private void createRetryAllowedParent(
            UUID accountId,
            UUID intentId,
            UUID attemptId,
            String clientOrderId,
            String requestBodyHash,
            String internalKey
    ) throws SQLException {
        insertAttempt(
                attemptId,
                intentId,
                accountId,
                1,
                internalKey,
                clientOrderId,
                requestBodyHash,
                null,
                CREATED_AT,
                EXPIRES_AT);
        execute("UPDATE submission_attempts SET status = 'DISPATCHING', started_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(1), attemptId);
        execute("UPDATE submission_attempts SET status = 'UNKNOWN', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(30), attemptId);
        execute("UPDATE submission_attempts SET status = 'RECONCILING' WHERE id = ?", attemptId);
        recordReconciliationCheck(attemptId, intentId, 1, "RETRY_SAME_KEY_ALLOWED", null);
        execute("UPDATE submission_attempts SET status = 'RECONCILED_NO_MATCH', finished_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(31), attemptId);
    }

    private void assertRetryRejectedForForgedDecisionParent(
            UUID accountId,
            UUID intentId,
            String clientOrderId,
            String requestBodyHash,
            String internalKey,
            String parentStatus
    ) throws SQLException {
        var parentId = UUID.randomUUID();
        insertCanonicalKey(accountId, intentId, clientOrderId, requestBodyHash, EXPIRES_AT);
        insertAttempt(
                parentId,
                intentId,
                accountId,
                1,
                internalKey,
                clientOrderId,
                requestBodyHash,
                null,
                CREATED_AT,
                EXPIRES_AT);
        execute("UPDATE submission_attempts SET status = 'DISPATCHING', started_at = ? WHERE id = ?",
                CREATED_AT.plusSeconds(1), parentId);
        if ("ACKNOWLEDGED".equals(parentStatus)) {
            var brokerOrderId = insertBrokerOrder(
                    UUID.randomUUID(),
                    intentId,
                    accountId,
                    "broker-" + clientOrderId,
                    clientOrderId,
                    "PENDING");
            execute("""
                    UPDATE submission_attempts
                       SET status = 'ACKNOWLEDGED',
                           confirmed_broker_order_id = ?,
                           finished_at = ?
                     WHERE id = ?
                    """, brokerOrderId, CREATED_AT.plusSeconds(30), parentId);
        } else if ("RECONCILIATION_FAILED".equals(parentStatus)) {
            execute("UPDATE submission_attempts SET status = 'UNKNOWN', finished_at = ? WHERE id = ?",
                    CREATED_AT.plusSeconds(30), parentId);
            execute("UPDATE submission_attempts SET status = 'RECONCILING' WHERE id = ?", parentId);
            recordReconciliationCheck(parentId, intentId, 1, "MANUAL_REVIEW_REQUIRED", null);
            execute("UPDATE submission_attempts SET status = 'RECONCILIATION_FAILED', finished_at = ? WHERE id = ?",
                    CREATED_AT.plusSeconds(31), parentId);
            recordReconciliationCheck(parentId, intentId, 2, "RETRY_SAME_KEY_ALLOWED", null);
        } else {
            execute("UPDATE submission_attempts SET status = ?, finished_at = ? WHERE id = ?",
                    parentStatus, CREATED_AT.plusSeconds(30), parentId);
            recordReconciliationCheck(parentId, intentId, 1, "RETRY_SAME_KEY_ALLOWED", null);
        }

        assertThatThrownBy(() -> insertAttempt(
                UUID.randomUUID(),
                intentId,
                accountId,
                2,
                internalKey + "-retry",
                clientOrderId,
                requestBodyHash,
                parentId,
                CREATED_AT.plusMinutes(1),
                EXPIRES_AT))
                .isInstanceOf(SQLException.class);
    }

    private UUID insertAccount() throws SQLException {
        var id = UUID.randomUUID();
        execute("INSERT INTO broker_accounts (id) VALUES (?)", id);
        return id;
    }

    private UUID insertIntent(UUID accountId) throws SQLException {
        var id = UUID.randomUUID();
        execute("""
                INSERT INTO order_intents (id, broker_account_id, quantity, status)
                VALUES (?, ?, ?, 'SUBMISSION_PENDING')
                """, id, accountId, new BigDecimal("10"));
        return id;
    }

    private void insertCanonicalKey(
            UUID accountId,
            UUID intentId,
            String clientOrderId,
            String requestBodyHash,
            OffsetDateTime expiresAt
    ) throws SQLException {
        execute("""
                INSERT INTO submission_idempotency_keys (
                    broker_account_id, client_order_id, order_intent_id, request_body_hash,
                    idempotency_expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, accountId, clientOrderId, intentId, requestBodyHash, expiresAt, CREATED_AT);
    }

    private void insertAttempt(
            UUID attemptId,
            UUID intentId,
            UUID accountId,
            int attemptNumber,
            String internalKey,
            String clientOrderId,
            String requestBodyHash,
            UUID retryOfAttemptId,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt
    ) throws SQLException {
        execute("""
                INSERT INTO submission_attempts (
                    id, order_intent_id, broker_account_id, attempt_number,
                    internal_idempotency_key, client_order_id, request_body_hash,
                    retry_of_attempt_id, status, created_at, idempotency_expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                """,
                attemptId,
                intentId,
                accountId,
                attemptNumber,
                internalKey,
                clientOrderId,
                requestBodyHash,
                retryOfAttemptId,
                createdAt,
                expiresAt);
    }

    private void insertAttemptWithLifecycleColumns(
            UUID attemptId,
            UUID intentId,
            UUID accountId,
            String internalKey,
            String clientOrderId,
            String requestBodyHash,
            UUID confirmedBrokerOrderId,
            String status,
            String dispatchEvidence,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            int lastReconciliationCheckNumber
    ) throws SQLException {
        execute("""
                INSERT INTO submission_attempts (
                    id, order_intent_id, broker_account_id, attempt_number,
                    internal_idempotency_key, client_order_id, request_body_hash,
                    retry_of_attempt_id, confirmed_broker_order_id, status, dispatch_evidence,
                    created_at, idempotency_expires_at, started_at, finished_at,
                    last_reconciliation_check_number
                ) VALUES (?, ?, ?, 1, ?, ?, ?, null, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """,
                attemptId,
                intentId,
                accountId,
                internalKey,
                clientOrderId,
                requestBodyHash,
                confirmedBrokerOrderId,
                status,
                dispatchEvidence,
                CREATED_AT,
                EXPIRES_AT,
                startedAt,
                finishedAt,
                lastReconciliationCheckNumber);
    }

    private UUID insertLegacyBrokerOrder(
            UUID brokerOrderPk,
            UUID intentId,
            UUID accountId,
            String brokerOrderId,
            String status
    ) throws SQLException {
        execute("""
                INSERT INTO broker_orders (
                    id, order_intent_id, broker_account_id, broker_order_id, status
                ) VALUES (?, ?, ?, ?, ?)
                """, brokerOrderPk, intentId, accountId, brokerOrderId, status);
        return brokerOrderPk;
    }

    private UUID insertBrokerOrder(
            UUID brokerOrderPk,
            UUID intentId,
            UUID accountId,
            String brokerOrderId,
            String status
    ) throws SQLException {
        return insertBrokerOrder(brokerOrderPk, intentId, accountId, brokerOrderId, brokerOrderId, status);
    }

    private UUID insertBrokerOrder(
            UUID brokerOrderPk,
            UUID intentId,
            UUID accountId,
            String brokerOrderId,
            String clientOrderId,
            String status
    ) throws SQLException {
        return insertBrokerOrder(
                brokerOrderPk,
                intentId,
                accountId,
                brokerOrderId,
                clientOrderId,
                status,
                null);
    }

    private UUID insertBrokerOrder(
            UUID brokerOrderPk,
            UUID intentId,
            UUID accountId,
            String brokerOrderId,
            String clientOrderId,
            String status,
            UUID replacesBrokerOrderId
    ) throws SQLException {
        execute("""
                INSERT INTO broker_orders (
                    id, order_intent_id, broker_account_id, broker_order_id, client_order_id,
                    status, replaces_broker_order_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, brokerOrderPk, intentId, accountId, brokerOrderId, clientOrderId, status, replacesBrokerOrderId);
        return brokerOrderPk;
    }

    private void recordIncompleteReconciliationCheck(
            UUID attemptId,
            UUID intentId,
            int checkNumber,
            String decision,
            boolean openOrdersComplete,
            boolean closedOrdersComplete,
            boolean allPagesRead,
            UUID matchedBrokerOrderId
    ) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        UPDATE submission_attempts
                           SET last_reconciliation_check_number = ?
                         WHERE id = ?
                        """, checkNumber, attemptId);
                execute(connection, """
                                INSERT INTO reconciliation_checks (
                                    id, submission_attempt_id, order_intent_id, check_number,
                                    open_orders_complete, closed_orders_complete, closed_window_start,
                                    closed_window_end, all_pages_read, result_hash, matched_broker_order_id,
                                    decision, checked_at
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        UUID.randomUUID(),
                        attemptId,
                        intentId,
                        checkNumber,
                        openOrdersComplete,
                        closedOrdersComplete,
                        CREATED_AT.minusDays(1),
                        CREATED_AT,
                        allPagesRead,
                        "hash-" + checkNumber,
                        matchedBrokerOrderId,
                        decision,
                        CREATED_AT.plusSeconds(checkNumber));
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void acknowledgeAttempt(UUID attemptId, UUID brokerOrderId) throws SQLException {
        execute("UPDATE submission_attempts SET status = 'DISPATCHING', started_at = ? WHERE id = ?",
                CREATED_AT.plusMinutes(2), attemptId);
        execute("""
                UPDATE submission_attempts
                   SET status = 'ACKNOWLEDGED',
                       confirmed_broker_order_id = ?,
                       finished_at = ?
                 WHERE id = ?
                """, brokerOrderId, CREATED_AT.plusMinutes(2).plusSeconds(1), attemptId);
    }

    private void recordReconciliationCheck(
            UUID attemptId,
            UUID intentId,
            int checkNumber,
            String decision,
            UUID matchedBrokerOrderId
    ) throws SQLException {
        recordReconciliationCheck(
                attemptId,
                intentId,
                checkNumber,
                decision,
                matchedBrokerOrderId,
                CREATED_AT.plusSeconds(checkNumber));
    }

    private void recordReconciliationCheck(
            UUID attemptId,
            UUID intentId,
            int checkNumber,
            String decision,
            UUID matchedBrokerOrderId,
            OffsetDateTime checkedAt
    ) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        UPDATE submission_attempts
                           SET last_reconciliation_check_number = ?
                         WHERE id = ?
                        """, checkNumber, attemptId);
                insertReconciliationCheck(
                        connection,
                        attemptId,
                        intentId,
                        checkNumber,
                        decision,
                        matchedBrokerOrderId,
                        checkedAt);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private void insertReconciliationCheck(
            Connection connection,
            UUID attemptId,
            UUID intentId,
            int checkNumber,
            String decision,
            UUID matchedBrokerOrderId,
            OffsetDateTime checkedAt
    ) throws SQLException {
        execute(connection, """
                        INSERT INTO reconciliation_checks (
                            id, submission_attempt_id, order_intent_id, check_number,
                            open_orders_complete, closed_orders_complete, closed_window_start,
                            closed_window_end, all_pages_read, result_hash, matched_broker_order_id,
                            decision, checked_at
                        ) VALUES (?, ?, ?, ?, true, true, ?, ?, true, ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                attemptId,
                intentId,
                checkNumber,
                CREATED_AT.minusDays(1),
                CREATED_AT,
                "hash-" + checkNumber,
                matchedBrokerOrderId,
                decision,
                checkedAt);
    }

    private void executeInTransaction(String sql, Object... parameters) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("")) {
            connection.setAutoCommit(false);
            try {
                execute(connection, sql, parameters);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private int queryAttemptCheckNumber(UUID attemptId) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement("""
                     SELECT last_reconciliation_check_number
                       FROM submission_attempts
                      WHERE id = ?
                     """)) {
            statement.setObject(1, attemptId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private String queryBrokerOrderStatus(UUID brokerOrderId) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(
                     "SELECT status FROM broker_orders WHERE id = ?")) {
            statement.setObject(1, brokerOrderId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private String queryBrokerOrderClientOrderId(UUID brokerOrderId) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(
                     "SELECT client_order_id FROM broker_orders WHERE id = ?")) {
            statement.setObject(1, brokerOrderId);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private int execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    private int execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = POSTGRES.createConnection("");
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }
}
