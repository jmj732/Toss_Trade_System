# Order Submission Reconciliation V3 Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement replay-safe order submission attempts, UNKNOWN reconciliation, BrokerOrder confirmation, and broker-status-driven OrderIntent outcomes with atomic audit/outbox persistence.

**Architecture:** Flyway V3 provides the final ledger constraints, JPA aggregates enforce commands before persistence, and one Spring transaction service coordinates SubmissionAttempt, BrokerOrder, ReconciliationCheck, OrderIntent, audit, and outbox. Broker query/order results are method inputs; no Toss HTTP integration is added.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL 17, Flyway, Testcontainers, JUnit 5.

---

## Chunk 1: V3 schema and domain persistence

### Task 1: Add Flyway V3 ledger constraints

**Files:**
- Create: `trading-backend/src/main/resources/db/migration/V3__create_order_submission_reconciliation.sql`
- Create: `trading-backend/src/test/java/com/jmj/trade/order/OrderSubmissionLedgerSchemaTest.java`
- Modify: existing integration-test cleanup methods to truncate V3 child tables first.

- [ ] **Step 1: Write failing schema tests**

Cover:

- V1→V2→V3 migration succeeds.
- `(order_intent_id, broker_account_id)` account consistency for canonical keys, attempts, and BrokerOrders.
- account-scoped client order ID cannot belong to a different intent or body hash.
- different accounts may reuse the same client order ID.
- retry rows require the same intent, client ID, body hash, expiry, next attempt number, latest `RETRY_SAME_KEY_ALLOWED` check, and `created_at < idempotency_expires_at`.
- retry at exactly the expiry instant and after it fails.
- one parent has at most one direct retry child.
- several attempts may confirm the same BrokerOrder; cross-intent confirmation fails.
- ReconciliationCheck `(submission_attempt_id, check_number)` is unique and rows are append-only.
- SubmissionAttempt request identity fields are immutable and status transitions are constrained.
- submission audit rows are append-only; outbox business fields are immutable while delivery metadata remains writable.

- [ ] **Step 2: Run the new test and verify RED**

Run:

```bash
cd trading-backend
./mvnw -q -Dtest=OrderSubmissionLedgerSchemaTest test
```

Expected: failure because V3 tables do not exist.

- [ ] **Step 3: Implement V3**

Add:

- `order_intents UNIQUE(id, broker_account_id)`
- BrokerOrder projection columns, version, status CHECK, intent/account FK, and `(id, order_intent_id)` unique
- `submission_idempotency_keys`
- `submission_attempts`
- `reconciliation_checks`
- `order_submission_audit_logs`
- `order_submission_outbox_events`
- transition, retry, append-only, and immutable-business-field triggers

The retry trigger compares `NEW.created_at` with the parent expiry; it must not call the database clock.

- [ ] **Step 4: Run schema and existing ledger tests**

```bash
./mvnw -q -Dtest='OrderSubmissionLedgerSchemaTest,OrderIntentLedgerSchemaTest,OrderIntentTransitionLedgerSchemaTest' test
```

Expected: all pass.

## Chunk 2: Domain aggregates and repositories

### Task 2: Implement SubmissionAttempt commands

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/order/SubmissionAttempt.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/SubmissionAttemptStatus.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/DispatchEvidence.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/SubmissionAttemptRepository.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/order/SubmissionAttemptTest.java`

- [ ] Test and implement `startDispatch`, `markUnknown`, `startReconciliation`, `acknowledge`, `reject`, `markNoMatch`, and `markReconciliationFailed`.
- [ ] Enforce allowed source states, immutable terminal attempts, non-null timestamps, ACKNOWLEDGED BrokerOrder requirement, and monotonically allocated reconciliation check numbers.
- [ ] Provide factories for initial and retry attempts; retry copies intent/account/client ID/body hash/expiry and receives a new internal key.
- [ ] Run `./mvnw -q -Dtest=SubmissionAttemptTest test`.

### Task 3: Map BrokerOrder, reconciliation, and submission ledgers

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/order/BrokerOrder.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/BrokerOrderStatus.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/BrokerOrderRepository.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/SubmissionIdempotencyKey.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/SubmissionIdempotencyKeyId.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/SubmissionIdempotencyKeyRepository.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/ReconciliationCheck.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/ReconciliationDecision.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/ReconciliationCheckRepository.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderSubmissionAuditLog.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderSubmissionAuditLogRepository.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderSubmissionOutboxEvent.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderSubmissionOutboxEventRepository.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/order/OrderSubmissionRepositoryIntegrationTest.java`

- [ ] Map only V3 fields required by the approved specification.
- [ ] Implement BrokerOrder creation and projection update commands with optimistic versioning.
- [ ] Keep ReconciliationCheck and audit rows constructor-only and append-only.
- [ ] Generate controlled JSON outbox payloads without adding a dependency.
- [ ] Verify JPA validation against the real Flyway schema and JSONB payload shape.

## Chunk 3: Transaction service and recovery behavior

### Task 4: Extend OrderIntent recovery commands

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/order/OrderIntent.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/order/OrderIntentTest.java`

- [ ] Add `requireReconciliation()` for `SUBMISSION_PENDING -> RECONCILIATION_REQUIRED`.
- [ ] Add `requireManualReview()` for `RECONCILIATION_REQUIRED -> MANUAL_REVIEW_REQUIRED`.
- [ ] Preserve the existing V1 transition matrix and terminal quantity rules.
- [ ] Run `./mvnw -q -Dtest=OrderIntentTest test`.

### Task 5: Implement OrderSubmissionService

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderSubmissionService.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/order/OrderSubmissionServiceIntegrationTest.java`

- [ ] **Initial attempt**
  - Require an intent in `SUBMISSION_PENDING`.
  - Create the canonical account/client ID key and attempt 1 with expiry `createdAt + 10 minutes`.
  - Reject account/client ID reuse belonging to another intent or body hash.

- [ ] **Dispatch and UNKNOWN**
  - Transition attempt to DISPATCHING.
  - On UNKNOWN, transition intent to RECONCILIATION_REQUIRED.
  - Persist submission audit/outbox and existing V2 intent audit/outbox with one timestamp.

- [ ] **Reconciliation input**
  - Accept completeness, closed window, pagination completeness, result hash, optional exact BrokerOrder evidence, broker status, cumulative fill, and captured time.
  - Increment attempt check number under `@Version`.
  - Store one append-only ReconciliationCheck.

- [ ] **No match and retry**
  - Complete/no match/before expiry produces `RETRY_SAME_KEY_ALLOWED`.
  - `retrySameKey` creates one child with the same canonical identity and returns intent to `SUBMISSION_PENDING`.
  - At or after expiry, incomplete search, ambiguous result, or conflicting BrokerOrder produces manual review and never creates a child.

- [ ] **BrokerOrder found**
  - Reuse only the same account/order ID/same intent row; otherwise manual review.
  - Link any number of attempts from the same intent to that BrokerOrder.
  - Insert ExecutionSnapshot evidence after the BrokerOrder exists.
  - Map broker result:
    - fillable/pending → `ACTIVE`
    - filled quantity equal to intent quantity → `COMPLETED`
    - non-fillable with zero fill → `CANCELED`
    - non-fillable with partial fill → `PARTIALLY_COMPLETED`
    - inconsistent status/quantity/evidence → `MANUAL_REVIEW_REQUIRED`

- [ ] **Pre-ID rejection**
  - Transition attempt to `BROKER_REJECTED`.
  - Terminate intent as `REJECTED` with zero final fill and calculated full remaining quantity.

- [ ] **Atomic ledgers**
  - Every service command stores submission audit/outbox.
  - Every OrderIntent transition also stores existing V2 intent audit/outbox.
  - Forced audit/outbox insert failure rolls back all domain rows.
  - Concurrent attempt/check updates produce one winner; loser leaves no audit/outbox.

- [ ] **Run targeted tests**

```bash
./mvnw -q -Dtest='OrderSubmissionServiceIntegrationTest,SubmissionAttemptTest,OrderIntentTest' test
```

Expected: all pass.

## Chunk 4: Full verification

### Task 6: Verify the complete backend

- [ ] Run:

```bash
DOCKER_HOST=unix:///Users/jjm/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
JAVA_HOME=/Users/jjm/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.8/Contents/Home \
./mvnw -q clean verify
```

- [ ] Confirm all tests have zero failures/errors, the JAR exists, `git diff --check` passes, and no unrelated files changed.
- [ ] Request final independent code review and fix every Critical/Important issue before completion.
