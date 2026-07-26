# OrderIntent Transition Service Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enforce explicit `OrderIntent` commands and atomically persist each accepted transition with one audit record and one outbox event.

**Architecture:** `OrderIntent` owns transition and terminal invariants. A transactional application service loads the aggregate, invokes one command, and appends audit/outbox rows in the same PostgreSQL transaction; existing Flyway triggers remain the final defense and JPA `@Version` rejects concurrent writes.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, PostgreSQL, Flyway, Testcontainers, JUnit 5.

---

## Chunk 1: Aggregate, ledgers, and transactional service

### Task 1: Lock aggregate behavior with tests

**Files:**
- Create: `trading-backend/src/test/java/com/jmj/trade/order/OrderIntentTest.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/order/OrderIntent.java`

- [ ] Test every explicit command's allowed source state.
- [ ] Test invalid transitions and terminal re-transition rejection.
- [ ] Test terminal field requirements and status-specific filled quantity rules.
- [ ] Test that `remainingQuantity` is calculated as `quantity - finalFilledQuantity`.
- [ ] Run `./mvnw -q -Dtest=OrderIntentTest test` and confirm red, then green.

### Task 2: Add append-only audit and outbox persistence

**Files:**
- Create: `trading-backend/src/main/resources/db/migration/V2__create_order_transition_ledgers.sql`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderIntentAuditLog.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderIntentOutboxEvent.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderIntentAuditLogRepository.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderIntentOutboxEventRepository.java`

- [ ] Add only the columns needed to reconstruct a status transition: aggregate id, from/to status, actor, terminal reason, event time; outbox also stores event type and a JSON payload.
- [ ] Add PostgreSQL triggers that reject audit/outbox update and delete.
- [ ] Map the two append-only tables with JPA and expose repositories for integration assertions.
- [ ] Run the existing Flyway/Testcontainers schema suite.

### Task 3: Commit state, audit, and outbox atomically

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/order/OrderIntentTransitionService.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/order/OrderIntentTransitionServiceIntegrationTest.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/order/OrderIntentOptimisticLockTest.java`

- [ ] Expose service methods matching aggregate commands: `approve`, `startRevalidation`, `markSubmissionPending`, `activate`, and `terminate`.
- [ ] In one `@Transactional` method, load the intent, invoke the command, then append exactly one audit row and one outbox row.
- [ ] Verify successful atomic commit and invalid-command rollback.
- [ ] Force an outbox insert failure and verify state plus audit rows roll back.
- [ ] Run two concurrent transitions from the same version and verify one commit, one optimistic-lock rejection, one audit row, and one outbox row.
- [ ] Run `./mvnw -q clean verify` against Testcontainers PostgreSQL.
