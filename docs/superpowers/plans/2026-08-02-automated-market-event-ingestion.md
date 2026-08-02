# Automated Market Event Ingestion Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add opt-in SEC/IR/Federal Reserve/FRED/BLS/BEA event ingestion that is idempotent, scope-aware, failure-isolated, and safely scheduled.

**Architecture:** Keep `com.jmj.trade.intelligence` as the owning domain. Add a small provider port and configured adapters behind a registry, then pass normalized events to `EventIntelligenceService` so existing notification/review/reanalyze behavior is unchanged. Persist macro scope and provider run/lease state with an append-only Flyway migration; conditionally register the scheduler only when explicitly enabled.

**Tech Stack:** Java 21 records, Spring Boot 4.1 configuration/scheduling/RestClient, PostgreSQL/Flyway/JdbcTemplate, JUnit 5/AssertJ/WireMock.

---

## Chunk 1: Contracts, schema, and failing tests

### Task 1: Add the delta schema

**Files:**
- Create: `trading-backend/src/main/resources/db/migration/V36__create_market_event_ingestion.sql`

- [ ] Add non-null macro scope JSONB with an empty-array default, and relax the old non-empty symbol check while retaining JSON-array validation.
- [ ] Add `market_event_ingestion_leases(name PRIMARY KEY, owner UUID, acquired_at, expires_at)` with `expires_at > acquired_at`, and `market_event_ingestion_runs(id, provider, status RUNNING|SUCCEEDED|FAILED, attempt >= 1, requested_since, started_at, completed_at, next_retry_at, collected_events >= 0, last_error)` with status/timestamp checks and provider/index support.
- [ ] Add indexes `(provider, next_retry_at) WHERE status = 'FAILED'` and `(provider, started_at DESC)`; retain existing event dedupe/ownership constraints and add the macro-scope array check.
- [ ] Add configuration-backed lookback and per-provider/batch limits to the run contract so one sweep cannot fetch or fan out unbounded work.
- [ ] Run `./mvnw -Dtest=EventIntelligenceIntegrationTest test`; expected RED initially from the missing `macro_scope` column, then GREEN after the migration and event mapper are complete.

### Task 2: Write failing normalized provider tests

**Files:**
- Create: `trading-backend/src/test/java/com/jmj/trade/intelligence/ingestion/MarketEventProviderTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/intelligence/ingestion/MarketEventProviderRegistryTest.java`

- [ ] Test SEC accession identity and acceptance/filing timestamp parsing, IR/FED RSS/Atom IDs and publication parsing, and FRED/BLS/BEA composite IDs including vintage/revision/value discriminators.
- [ ] Test retry classification for 408/429/5xx/network failures and non-retryable 4xx responses.
- [ ] Test RSS/Atom parsing rejects external entities, configured URLs are HTTPS (or localhost HTTP in tests), and credentials/user-agent validation is enforced.
- [ ] Test disabled providers are absent and registry construction never instantiates an unconfigured provider.
- [ ] Run `./mvnw -Dtest='MarketEventProviderTest,MarketEventProviderRegistryTest' test`; expected RED with missing provider types/methods, then record the exact failing test names before implementation.

### Task 3: Write failing ingestion integration tests

**Files:**
- Create or modify: `trading-backend/src/test/java/com/jmj/trade/intelligence/EventIntelligenceIntegrationTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/intelligence/AutomatedMarketEventIngestionIntegrationTest.java`

- [ ] Test automated insertion preserves provider/source ID, occurred/collected times, symbol linkage, and macro scope; assert macro-only events have an empty symbol array and remain visible through existing review/list APIs.
- [ ] Pass an automated event through the existing review/detail and explicit reanalyze endpoints; assert the event ID is the same and the comparison is stored by the existing workflow.
- [ ] Seed two users, one inactive connection, and unrelated symbols; assert only active owned connections with matching symbols/macroscopes receive events.
- [ ] Test repeated provider output creates one `intelligence_events` row and one `notification_outbox_events` row, not a provider failure.
- [ ] Test a failing provider leaves one `FAILED` run with `next_retry_at` while a succeeding provider inserts events and records `SUCCEEDED`; assert exact per-provider counts.
- [ ] Test due retry and stale `RUNNING` reprocessing, max-attempt behavior, and two service instances where only one acquires the lease.
- [ ] Test scheduler beans are absent with default properties and present only with `market-events.scheduler.enabled=true` in a separate Spring context signature.
- [ ] Run `./mvnw -Dtest='AutomatedMarketEventIngestionIntegrationTest,EventIntelligenceIntegrationTest' test`; expected RED with missing ingestion types/tables, then preserve the failing output as the TDD checkpoint.

## Chunk 2: Minimal implementation

### Task 4: Extend event persistence for macro scope and automated idempotency

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/intelligence/EventIntelligenceService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/intelligence/EventIntelligenceController.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/intelligence/EventReviewWorkflowService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/dashboard/DashboardReadModelService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/intelligence/EventIntelligenceException.java`

- [ ] Define the canonical `MacroScope` JSON shape as `{provider, identifier, period, vintage}`; require provider/identifier, normalize provider to upper case, allow nullable/empty period and vintage, and serialize an empty array when absent.
- [ ] Factor one insert path that throws `EVENT_ALREADY_EXISTS` for manual duplicates but returns `false` for automated duplicates; a concurrent `ON CONFLICT DO NOTHING` duplicate must not emit a notification.
- [ ] Keep notification emission inside the successful insert transaction.
- [ ] Update the review and dashboard read models to select/map macro scope without changing review/reanalyze behavior.
- [ ] Run `./mvnw -Dtest=EventIntelligenceIntegrationTest test`; expected GREEN with the existing manual, review, reanalyze, and macro-scope assertions.

### Task 5: Implement provider contracts and configured adapters

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventProviderId.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventProvider.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEvent.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventIngestionProperties.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventProviderRegistry.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/ConfiguredMarketEventProvider.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventHttpClient.java`

- [ ] Implement SEC, IR, FED, FRED, BLS, and BEA parsing behind one normalized provider port.
- [ ] Bind `market-events.providers.<id>.enabled`, `base-url`, `path`, `api-key`, `user-agent`, `identifiers`, `feed-urls`, and `scopes`; use SEC `symbol -> CIK`, IR `symbol -> feed URL`, FED `scope -> feed URL`, FRED/BLS `series IDs`, and BEA `dataset|table|line|geo|year` scopes.
- [ ] Use `CIK + accessionNumber`, `series + observation date + realtime/vintage/value`, and `dataset/table/line/geo/period/value` as source IDs; use SEC acceptance, feed guid/id/link/hash publication, or observation period as `occurredAt`, never retrieval time.
- [ ] Apply bounded retry to retryable HTTP/network failures and safe XML parser features.
- [ ] Contact only URLs supplied in application configuration; require HTTPS except localhost HTTP tests, reject user-info/query-bearing base URLs, use the JDK client's no-redirect default, cap response bodies at 1 MiB, and require SEC's descriptive `user-agent` plus FRED/BEA keys when enabled. Do not instantiate disabled providers.
- [ ] Run `./mvnw -Dtest='MarketEventProviderTest,MarketEventProviderRegistryTest,ConfiguredMarketEventProviderTest' test`; expected GREEN for all six adapters, XML hardening, retry classification, and opt-in validation.

### Task 6: Implement lease, run ledger, isolated sweep, and conditional scheduler

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventIngestionLease.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventIngestionService.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventIngestionConfiguration.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/intelligence/ingestion/MarketEventIngestionScheduler.java`
- Modify: `trading-backend/src/main/resources/application.yml`

- [ ] Query only `broker_connections.status = 'ACTIVE' AND deleted_at IS NULL`; resolve each connection's latest successful sync and distinct position symbols, route stock events only on symbol intersection, and fan out configured macro events only to those active connections.
- [ ] Apply `lookback`, `batch-size`, and `max-events-per-provider` bounds to each provider request and fan-out loop.
- [ ] Acquire and renew with atomic lease SQL, release by `name + owner UUID`, transition stale `RUNNING` rows older than `max(leaseTtl, readTimeout)` to `FAILED` with an immediate `next_retry_at`, and transition active runs `RUNNING -> SUCCEEDED|FAILED`; commit each provider run independently so a provider exception cannot roll back other providers.
- [ ] Retry `FAILED` rows only when `next_retry_at <= CURRENT_TIMESTAMP` and `attempt < maxAttempts`, using bounded exponential backoff; reset the attempt window after success, expose explicit failed-run reprocessing that resets the attempt window, and allow the next scheduled tick to reprocess due/stale work.
- [ ] Register `@Scheduled` only with `market-events.scheduler.enabled=true` (assert `ApplicationContext.getBeansOfType(MarketEventIngestionScheduler.class).isEmpty()` by default and non-empty with the explicit property); keep service/registry testable without scheduling.
- [ ] Run `./mvnw -Dtest=AutomatedMarketEventIngestionIntegrationTest test`; expected GREEN for insertion, routing, duplicate no-op, failure isolation, retry/reprocessing, lease renewal/exclusion, and scheduler-gate cases.

## Chunk 3: Review, full verification, and integration

### Task 7: Review and harden

**Files:**
- Review: `docs/superpowers/specs/2026-08-02-automated-market-event-ingestion-delta.md`
- Review: `trading-backend/src/main/java/com/jmj/trade/intelligence/**`
- Review: `trading-backend/src/main/resources/db/migration/V36__create_market_event_ingestion.sql`
- Review: `trading-backend/src/main/resources/application.yml`
- Review: `trading-backend/src/test/java/com/jmj/trade/intelligence/**`

- [ ] Run one comprehensive code-review pass over the branch diff and requirements; completion output must be zero critical/important findings.
- [ ] Verify and, if needed, fix configuration-only HTTPS/localhost URL validation, XXE rejection, provider credential isolation, active-connection ownership, symbol/macro routing, existing review/reanalyze handoff, transaction boundaries, duplicate notifications, retry starvation, and the no-LLM/no-order boundary.
- [ ] Rerun `./mvnw -Dtest='AutomatedMarketEventIngestionIntegrationTest,EventIntelligenceIntegrationTest' test` and `./mvnw -Dtest='MarketEventProviderTest,MarketEventProviderRegistryTest,ConfiguredMarketEventProviderTest' test`; expected all focused tests GREEN after every review fix.
- [ ] Reconcile this plan/spec with the final diff and record any intentional simplification with its known ceiling.

### Task 8: Full verification and squash integration

- [ ] Run `cd trading-backend && ./mvnw --no-transfer-progress clean verify`; expected exit 0 and zero test failures.
- [ ] Run `cd analysis-service && pytest -q`; run `cd web-dashboard && npm test && npm run build && npm audit --omit=dev`; run `scripts/test-local-stack.sh` and `scripts/smoke-local-stack.sh` when Docker/credentials are available, otherwise record the exact blocker.
- [ ] Inspect `git diff --check`, `git status --short`, and the complete diff; commit with the repository's Korean format and trailer `Co-Authored-By: jaeminjo732 <jaeminjo732@gmail.com>`, then verify the feature branch is clean.
- [ ] Confirm the base branch is clean and up to date with `origin`, squash-merge `feature/automated-market-event-ingestion` into `design/modular-monolith-architecture`, and rerun the backend focused/full gates on the merged base.
- [ ] Push the updated base branch, confirm the pushed SHA and remote CI run/result, and report any unavailable Docker, external provider, or CI validation explicitly.
