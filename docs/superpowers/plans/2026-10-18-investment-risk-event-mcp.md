# Investment OS Risk Event MCP Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist each approved-thesis price-risk state transition exactly once, deliver the same event through the existing Telegram outbox, and expose the stored event and explicitly linked decisions through a read-only Toss MCP tool.

**Architecture:** Reuse `monitoring_current_states` as the current snapshot and append-only `monitoring_state_history` as the event log. Add a dedicated `THESIS_REVIEW` scope plus an append-only decision-link table; use one event UUID as the history ID and notification outbox source ID. Keep the existing trusted price selection and 3% NEAR band unchanged. A new MCP read service queries only authenticated-user rows and never refreshes providers.

**Tech Stack:** Spring Boot, JDBC, PostgreSQL, Flyway, existing JSON-RPC MCP protocol, Telegram notification outbox, JUnit/Testcontainers.

---

### Task 1: Persist idempotent risk transitions

**Files:**
- Modify: `trading-backend/src/main/resources/db/migration/V62__investment_risk_event_links.sql`
- Modify: `trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringStateStore.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringThesisTriggerDetector.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/monitoring/MonitoringThesisTriggerDetectorTest.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/monitoring/MonitoringThesisTriggerDetectorIntegrationTest.java`

- [x] Add integration coverage for normal→near, near→breach, recovery, recurrence, repeated evaluations, stale/untrusted evidence, concurrency, and event-ID/outbox linkage.
- [x] Exercise transition persistence, Telegram retry, and bounded related-decision reads against PostgreSQL integration fixtures.
- [x] Extend monitoring state scope and create owner-safe decision-link schema with append-only constraints.
- [x] Add transactionally locked risk transition persistence with stable incident IDs and dedup keys.
- [x] Emit one existing `MONITORING_ALERT` outbox event per persisted transition using the history event ID.
- [x] Use Spring evaluation time for transition ordering and retain the provider timestamp/date label separately as `sourceAsOf`.
- [x] Re-run focused tests.

### Task 2: Add explicit decision linkage

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/investment/InvestmentContextCaptureIntegrationTest.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/connector/ConnectorMcpProtocolTest.java`

- [x] Add backward-compatible optional `riskEventIds` to `DecisionInput` and the existing `append_investment_decision` input schema.
- [x] Reject links not owned by the authenticated user or for a different ticker; make replay compare linked IDs.
- [x] Keep old decisions and records unlinked; do not backfill inferred associations.
- [x] Verify explicit links retain decision ID, as-of, action/rationale, and event thesis revision.

### Task 3: Add read-only MCP history tool

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/monitoring/InvestmentRiskEventService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/connector/ConnectorMcpProtocolTest.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/security/InvestmentContextMcpSecurityIntegrationTest.java`

- [x] Add tests for tool discovery, empty result, DB error, validation, user isolation, `since`, cursor, limits, unresolved events, and schema serialization.
- [x] Implement `get_investment_risk_events` with bounded keyset pagination and preserved null/status/as-of values.
- [x] Include latest events, optional unresolved incidents, and only explicitly linked decisions, with response-size caps.
- [x] Verify the read path uses PostgreSQL only, without provider refresh or order submission.

### Task 4: Preserve Telegram and document contracts

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/notification/NotificationOutboxProcessor.java`
- Modify: `docs/ops/continuous-monitoring.md`
- Modify: `docs/ops/investment-context-mcp-contract.md`
- Modify: `docs/ops/connector-runbook.md`
- Modify: `README.md`
- Test: `trading-backend/src/test/java/com/jmj/trade/notification/NotificationOutboxProcessorIntegrationTest.java`

- [x] Include event ID, event state, price basis/session, observation time, and the existing signed distance percentage; keep raw observed/trigger prices in the owner-scoped event read only.
- [x] Preserve rendering for pre-existing alert payloads and keep all other Telegram alert paths unchanged.
- [x] Document transition semantics, migration recovery, MCP schema, explicit decision linkage, and the no-backfill rule.
- [x] Verify notification send retries do not create or delete risk events.

### Task 5: Full verification and delivery

**Files:**
- Review all change files and Flyway migration.

- [x] Run targeted unit/integration tests, full backend tests, and build.
- [ ] Run repository CI/review gates and review the final diff.
- [ ] If all gates pass, create PR and use the previously authorized main/CD path; verify deployment health, migration version, and actual read-only MCP discovery/call if runtime access is available.
- [ ] Report unavailable runtime checks explicitly; never substitute an empty result for a failed read.
