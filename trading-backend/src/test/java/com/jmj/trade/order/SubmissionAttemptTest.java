package com.jmj.trade.order;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubmissionAttemptTest {

    private static final UUID INTENT_ID = UUID.randomUUID();
    private static final UUID ACCOUNT_ID = UUID.randomUUID();
    private static final Instant CREATED_AT = Instant.parse("2026-07-27T01:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plusSeconds(600);
    private static final Instant STARTED_AT = CREATED_AT.plusSeconds(1);
    private static final Instant FINISHED_AT = CREATED_AT.plusSeconds(2);
    private static final DispatchEvidence EVIDENCE =
            new DispatchEvidence("request-1", "accepted by client");

    @Test
    void initialAttemptStartsCreatedWithImmutableIdentity() {
        var id = UUID.randomUUID();
        var attempt = SubmissionAttempt.initial(
                id,
                INTENT_ID,
                ACCOUNT_ID,
                "client-1",
                "hash-1",
                "internal-1",
                CREATED_AT);

        assertThat(attempt.getId()).isEqualTo(id);
        assertThat(attempt.getOrderIntentId()).isEqualTo(INTENT_ID);
        assertThat(attempt.getBrokerAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(attempt.getAttemptNumber()).isEqualTo(1);
        assertThat(attempt.getInternalIdempotencyKey()).isEqualTo("internal-1");
        assertThat(attempt.getClientOrderId()).isEqualTo("client-1");
        assertThat(attempt.getRequestBodyHash()).isEqualTo("hash-1");
        assertThat(attempt.getRetryOfAttemptId()).isNull();
        assertThat(attempt.getConfirmedBrokerOrderId()).isNull();
        assertThat(attempt.getStatus()).isEqualTo(SubmissionAttemptStatus.CREATED);
        assertThat(attempt.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(attempt.getIdempotencyExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(attempt.getLastReconciliationCheckNumber()).isZero();
        assertThat(attempt.getVersion()).isZero();
    }

    @Test
    void retryCopiesCanonicalIdentityAndUsesNewAttemptNumberAndInternalKey() {
        var parent = retryAllowedParent();

        var retry = SubmissionAttempt.retry(
                UUID.randomUUID(),
                parent,
                "internal-2",
                CREATED_AT.plusSeconds(30),
                ReconciliationDecision.RETRY_SAME_KEY_ALLOWED);

        assertThat(retry.getOrderIntentId()).isEqualTo(parent.getOrderIntentId());
        assertThat(retry.getBrokerAccountId()).isEqualTo(parent.getBrokerAccountId());
        assertThat(retry.getAttemptNumber()).isEqualTo(2);
        assertThat(retry.getInternalIdempotencyKey()).isEqualTo("internal-2");
        assertThat(retry.getClientOrderId()).isEqualTo(parent.getClientOrderId());
        assertThat(retry.getRequestBodyHash()).isEqualTo(parent.getRequestBodyHash());
        assertThat(retry.getRetryOfAttemptId()).isEqualTo(parent.getId());
        assertThat(retry.getIdempotencyExpiresAt()).isEqualTo(parent.getIdempotencyExpiresAt());
        assertThat(retry.getStatus()).isEqualTo(SubmissionAttemptStatus.CREATED);
    }

    @Test
    void retryRequiresSameKeyWindowAndOpenParent() {
        var parent = initial();
        parent.startDispatch(STARTED_AT, EVIDENCE);
        parent.markUnknown(FINISHED_AT, EVIDENCE);
        parent.startReconciliation();
        parent.allocateNextReconciliationCheckNumber();
        parent.markNoMatch(FINISHED_AT.plusSeconds(1));

        assertThatThrownBy(() -> SubmissionAttempt.retry(
                UUID.randomUUID(), parent, "internal-2", EXPIRES_AT,
                ReconciliationDecision.RETRY_SAME_KEY_ALLOWED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("idempotency window");

        var acknowledgedParent = initial();
        acknowledgedParent.startDispatch(STARTED_AT, EVIDENCE);
        acknowledgedParent.acknowledge(FINISHED_AT, UUID.randomUUID(), EVIDENCE);

        assertThatThrownBy(() -> SubmissionAttempt.retry(
                UUID.randomUUID(), acknowledgedParent, "internal-2", CREATED_AT.plusSeconds(30),
                ReconciliationDecision.RETRY_SAME_KEY_ALLOWED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retry-allowed");

        assertThatThrownBy(() -> SubmissionAttempt.retry(
                UUID.randomUUID(), parent, "internal-2", CREATED_AT.plusSeconds(30),
                ReconciliationDecision.MANUAL_REVIEW_REQUIRED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("latest reconciliation decision");
    }

    @Test
    void dispatchUnknownAndReconciliationFailurePathFollowsAllowedTransitions() {
        var attempt = initial();

        attempt.startDispatch(STARTED_AT, EVIDENCE);
        attempt.markUnknown(FINISHED_AT, EVIDENCE);
        attempt.startReconciliation();
        int checkNumber = attempt.allocateNextReconciliationCheckNumber();
        attempt.markReconciliationFailed(FINISHED_AT.plusSeconds(1));

        assertThat(checkNumber).isEqualTo(1);
        assertThat(attempt.getStatus()).isEqualTo(SubmissionAttemptStatus.RECONCILIATION_FAILED);
        assertThat(attempt.getLastReconciliationCheckNumber()).isEqualTo(1);
        assertThat(attempt.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(attempt.getFinishedAt()).isEqualTo(FINISHED_AT.plusSeconds(1));
        assertThat(attempt.getDispatchEvidence()).isEqualTo(EVIDENCE);
    }

    @Test
    void acknowledgedAttemptRequiresBrokerOrderIdAndIsTerminal() {
        var attempt = initial();
        attempt.startDispatch(STARTED_AT, EVIDENCE);

        assertThatThrownBy(() -> attempt.acknowledge(FINISHED_AT, null, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirmedBrokerOrderId");

        var brokerOrderId = UUID.randomUUID();
        attempt.acknowledge(FINISHED_AT, brokerOrderId, EVIDENCE);

        assertThat(attempt.getStatus()).isEqualTo(SubmissionAttemptStatus.ACKNOWLEDGED);
        assertThat(attempt.getConfirmedBrokerOrderId()).isEqualTo(brokerOrderId);
        assertThat(attempt.getFinishedAt()).isEqualTo(FINISHED_AT);
        assertThatThrownBy(attempt::startReconciliation)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void brokerRejectedAndNoMatchAreTerminal() {
        var rejected = initial();
        rejected.startDispatch(STARTED_AT, EVIDENCE);
        rejected.reject(FINISHED_AT, EVIDENCE);

        assertThat(rejected.getStatus()).isEqualTo(SubmissionAttemptStatus.BROKER_REJECTED);
        assertThatThrownBy(() -> rejected.markUnknown(FINISHED_AT.plusSeconds(1), EVIDENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");

        var noMatch = initial();
        noMatch.startDispatch(STARTED_AT, EVIDENCE);
        noMatch.markUnknown(FINISHED_AT, EVIDENCE);
        noMatch.startReconciliation();
        assertThatThrownBy(() -> noMatch.markNoMatch(FINISHED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconciliation check");
        noMatch.allocateNextReconciliationCheckNumber();
        noMatch.markNoMatch(FINISHED_AT.plusSeconds(1));

        assertThat(noMatch.getStatus()).isEqualTo(SubmissionAttemptStatus.RECONCILED_NO_MATCH);
        assertThatThrownBy(() -> noMatch.reject(FINISHED_AT.plusSeconds(2), EVIDENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal");
    }

    @Test
    void invalidTransitionsAndRequiredTimestampsAreRejected() {
        var attempt = initial();

        assertThatThrownBy(() -> attempt.markUnknown(FINISHED_AT, EVIDENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CREATED -> UNKNOWN");

        assertThatThrownBy(() -> attempt.startDispatch(null, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startedAt");
        assertThatThrownBy(() -> attempt.startDispatch(CREATED_AT.minusMillis(1), EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startedAt");

        attempt.startDispatch(STARTED_AT, EVIDENCE);

        assertThatThrownBy(() -> attempt.reject(null, EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedAt");
        assertThatThrownBy(() -> attempt.reject(STARTED_AT.minusMillis(1), EVIDENCE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedAt");
    }

    @Test
    void clientOrderIdFormatIsValidated() {
        assertThatThrownBy(() -> SubmissionAttempt.initial(
                UUID.randomUUID(), INTENT_ID, ACCOUNT_ID, "bad id", "hash-1",
                "internal-1", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientOrderId");

        assertThatThrownBy(() -> SubmissionAttempt.initial(
                UUID.randomUUID(), INTENT_ID, ACCOUNT_ID, "", "hash-1",
                "internal-1", CREATED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clientOrderId");
    }

    @Test
    void initialAttemptDerivesTenMinuteExpiry() {
        var attempt = initial();

        assertThat(attempt.getIdempotencyExpiresAt()).isEqualTo(CREATED_AT.plusSeconds(600));
    }

    @Test
    void reconciliationAcknowledgementRequiresAllocatedCheck() {
        var attempt = initial();
        attempt.startDispatch(STARTED_AT, EVIDENCE);
        attempt.markUnknown(FINISHED_AT, EVIDENCE);
        attempt.startReconciliation();

        assertThatThrownBy(() -> attempt.acknowledge(
                FINISHED_AT.plusSeconds(1), UUID.randomUUID(), EVIDENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconciliation check");

        attempt.allocateNextReconciliationCheckNumber();
        attempt.acknowledge(FINISHED_AT.plusSeconds(1), UUID.randomUUID(), EVIDENCE);

        assertThat(attempt.getStatus()).isEqualTo(SubmissionAttemptStatus.ACKNOWLEDGED);
    }

    @Test
    void reconciliationFinishMustBeStrictlyAfterUnknownFinish() {
        var attempt = initial();
        attempt.startDispatch(STARTED_AT, EVIDENCE);
        attempt.markUnknown(FINISHED_AT, EVIDENCE);
        attempt.startReconciliation();
        attempt.allocateNextReconciliationCheckNumber();

        assertThatThrownBy(() -> attempt.markNoMatch(FINISHED_AT.minusMillis(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedAt");

        assertThatThrownBy(() -> attempt.markNoMatch(FINISHED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finishedAt");

        attempt.markNoMatch(FINISHED_AT.plusMillis(1));

        assertThat(attempt.getFinishedAt()).isEqualTo(FINISHED_AT.plusMillis(1));
    }

    @Test
    void retryCreatedAtMustBeAfterParentFinishedAt() {
        var parent = retryAllowedParent();

        assertThatThrownBy(() -> SubmissionAttempt.retry(
                UUID.randomUUID(),
                parent,
                "internal-2",
                parent.getFinishedAt().minusMillis(1),
                ReconciliationDecision.RETRY_SAME_KEY_ALLOWED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parent finishedAt");
    }

    @Test
    void reconciliationFailureRequiresAllocatedCheck() {
        var attempt = initial();
        attempt.startDispatch(STARTED_AT, EVIDENCE);
        attempt.markUnknown(FINISHED_AT, EVIDENCE);
        attempt.startReconciliation();

        assertThatThrownBy(() -> attempt.markReconciliationFailed(FINISHED_AT.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reconciliation check");

        attempt.allocateNextReconciliationCheckNumber();
        attempt.markReconciliationFailed(FINISHED_AT.plusSeconds(1));

        assertThat(attempt.getStatus()).isEqualTo(SubmissionAttemptStatus.RECONCILIATION_FAILED);
    }

    private static SubmissionAttempt initial() {
        return SubmissionAttempt.initial(
                UUID.randomUUID(),
                INTENT_ID,
                ACCOUNT_ID,
                "client-1",
                "hash-1",
                "internal-1",
                CREATED_AT);
    }

    private static SubmissionAttempt retryAllowedParent() {
        var parent = initial();
        parent.startDispatch(STARTED_AT, EVIDENCE);
        parent.markUnknown(FINISHED_AT, EVIDENCE);
        parent.startReconciliation();
        parent.allocateNextReconciliationCheckNumber();
        parent.markNoMatch(FINISHED_AT.plusSeconds(1));
        return parent;
    }
}
