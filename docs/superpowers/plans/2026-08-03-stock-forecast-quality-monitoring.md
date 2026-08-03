# Stock Forecast Quality Monitoring Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add read-only quality monitoring for persisted D1/D5/D20 forecasts and existing outcome grades, with sample-aware drift/degradation and operational delayed-grading visibility.

**Architecture:** Reuse the existing outcome grader and lease scheduler. Add one prediction-package read service that joins immutable forecast/prediction/outcome rows, parses forecast metrics, aggregates current and preceding periods, and returns explicit data-sufficiency states. Extend the existing prediction API/dashboard components additively.

**Tech Stack:** Java 21, Spring Boot 4.1, JdbcTemplate, Jackson, PostgreSQL/Flyway, Micrometer; Next.js 16/React 19 with plain JavaScript and Node built-in tests.

---

## Chunk 1: Backend quality contract and math

### Task 1: Lock the metric behavior with failing tests

**Files:**
- Create: `trading-backend/src/test/java/com/jmj/trade/prediction/ForecastQualityMonitoringServiceTest.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/prediction/AnalysisPredictionIntegrationTest.java`

- [x] Write unit tests for D1 calibration/Brier, flat-return exclusion, D5/D20 signed error/MAE/sign hit rate, grouping, and minimum sample suppression.
- [x] Run the red focused test first, then make `ForecastQualityMonitoringServiceTest` green.
- [x] Add integration coverage for the additive prediction response, symbol filter, and forecast/outcome joins.

### Task 2: Implement the minimum read-side quality service

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/prediction/ForecastQualityMonitoringService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/StockForecastConfiguration.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/AnalysisPredictionService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/AnalysisPredictionController.java`

- [x] Define immutable response records for period, row status, metrics, drift, and forecast-quality view.
- [x] Query only owned connection-linked rows filtered by period/model/contract/symbol; parse persisted forecast response metrics.
- [x] Aggregate D1/D5/D20 per symbol/model/contract/horizon and create prior-period comparisons.
- [x] Return null comparative conclusions when current or baseline samples are below the minimum.
- [x] Wire the service as an always-available read bean and add the additive endpoint fields/query parameter.
- [x] Run the focused unit tests; Testcontainers integration execution remains environment-blocked without Docker.

## Chunk 2: Evaluation isolation and operations state

### Task 3: Isolate unexpected item failures in the existing scheduler

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/AnalysisPredictionService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/PredictionEvaluationMetrics.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/PredictionEvaluationScheduler.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/prediction/PredictionEvaluationSchedulerTest.java`

- [x] Extend the scheduler result/metric test to prove item failures are separately counted.
- [x] Add the smallest per-item failure result/counter while preserving existing attempted/succeeded/quote-failed semantics.
- [x] Keep opt-in gating, distributed lease, and `ON CONFLICT DO NOTHING` idempotency unchanged.
- [x] Run scheduler/metrics tests with an explicit Byte Buddy test agent.

### Task 4: Expose long-ungraded state from actual rows

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/PredictionOperationsController.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/connection/CredentialVaultConfiguration.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/prediction/AnalysisPredictionIntegrationTest.java`

- [x] Add a configurable 24-hour overdue threshold and query count/oldest due timestamp for the earliest missing horizon.
- [x] Add integration assertions for long overdue state and preserve user ownership.
- [x] Compile the integration test; execution is blocked by unavailable Docker/Testcontainers.

## Chunk 3: Operational dashboard and verification

### Task 5: Render quality and delayed-grading evidence

**Files:**
- Modify: `web-dashboard/app/analysis-outcome-view.js`
- Modify: `web-dashboard/app/prediction-operations-view.js`
- Modify: `web-dashboard/test/analysis-outcome-view.test.mjs`
- Modify: `web-dashboard/test/prediction-operations-view.test.mjs`

- [x] Add a forecast-quality table with symbol/version/horizon, sample state, hit/error/calibration values, and drift/degradation labels.
- [x] Add symbol to the existing filter and show long-ungraded count/oldest due state in operations.
- [x] Preserve the no-order/no-result-mutation disclaimer and existing UI conventions.
- [x] Run `npm test` in `web-dashboard`.

### Task 6: Docs, review, and full verification

**Files:**
- Modify: `docs/ops/prediction-evaluation-runbook.md`
- Modify: `.env.example`
- Modify: `.env.staging.example`

- [x] Document the metric definitions, sample threshold, drift interpretation, and long-ungraded property.
- [x] Run one code-review pass over the complete diff; fix the connection-scope finding.
- [x] Run backend clean verify, dashboard tests, analysis-service pytest, and local-stack scripts where prerequisites are available; backend integration and Docker smoke remain blocked by an unavailable Docker daemon/image registry.
- [x] Reconcile the delta spec with the final diff, commit the feature on the feature branch, and squash-merge into `design/modular-monolith-architecture`; remote push is blocked pending explicit destination authorization.
