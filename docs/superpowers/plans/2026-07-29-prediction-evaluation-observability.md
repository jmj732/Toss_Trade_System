# Prediction Evaluation Observability Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 예측 채점의 DB backlog/lag와 tick/lease/cap 상태를 Prometheus로 안전하게 노출하고 기본 비활성 배포 설정과 운영 절차를 제공한다.

**Architecture:** `PredictionEvaluationMetrics`가 단일 due-candidate SQL의 TTL snapshot과 Micrometer gauge/counter를 소유한다. `AnalysisPredictionService`는 기존 `int` 계약을 유지하면서 scheduler 전용 상세 결과를 제공하고, scheduler가 결과와 중단 원인을 metrics에 전달한다. Actuator는 기존 backend port와 네트워크 경계를 그대로 사용한다.

**Tech Stack:** Java 21, Spring Boot Actuator, Micrometer Prometheus registry, PostgreSQL, Docker Compose, JUnit 5, AssertJ, Mockito.

---

## Chunk 1: Metrics and evaluation accounting

### Task 1: SQL-backed gauges with bounded DB load

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/prediction/PredictionEvaluationMetrics.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/prediction/PredictionEvaluationMetricsTest.java`

- [ ] Write failing tests for earliest-horizon backlog, max lag, shared TTL snapshot, concurrent refresh, first-refresh failure, and last-good snapshot retention.
- [ ] Run `./mvnw test -Dtest=PredictionEvaluationMetricsTest` and confirm compilation/test failure because the class is absent.
- [ ] Implement one SQL returning backlog count and oldest target due time; register two gauges over a synchronized 30-second cached snapshot.
- [ ] Ensure failed refresh attempts are also TTL-throttled and never escape the gauge callback.
- [ ] Run `./mvnw test -Dtest=PredictionEvaluationMetricsTest` and confirm green.

### Task 2: Preserve grading behavior while reporting tick outcomes

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/AnalysisPredictionService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/PredictionEvaluationScheduler.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/connection/CredentialVaultConfiguration.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/prediction/AnalysisPredictionIntegrationTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/prediction/PredictionEvaluationSchedulerTest.java`

- [ ] Write failing tests for `GRADED`, `QUOTE_FAILED`, and duplicate accounting while preserving `evaluateDue(...)` succeeded-count behavior.
- [ ] Write failing scheduler tests for attempted/succeeded/quote-failed counters, lease acquire/renew failure, and count/time early-stop precedence.
- [ ] Run the two targeted test classes and confirm failures caused by missing detailed results/metrics wiring.
- [ ] Add a scheduler-only detailed evaluation result; keep every existing `evaluateDue(...) -> int` overload and SQL/write behavior unchanged.
- [ ] Record cumulative tick counters and tagged lease/early-stop counters in `PredictionEvaluationMetrics`.
- [ ] Inject metrics through `CredentialVaultConfiguration`; do not add order imports or change GET/controller paths.
- [ ] Run targeted prediction tests and confirm green.

## Chunk 2: Prometheus, deployment, and operations

### Task 3: Prometheus registry and bounded endpoint exposure

**Files:**
- Modify: `trading-backend/pom.xml`
- Modify: `trading-backend/src/main/resources/application.yml`
- Modify: `compose.yaml`
- Modify: `.env.example`
- Modify: `.env.staging.example`
- Modify: `scripts/test-local-stack.sh`
- Create: `trading-backend/src/test/java/com/jmj/trade/observability/PrometheusEndpointIntegrationTest.java`

- [ ] Write/extend static contract checks for default-disabled scheduler variables, all `prediction.evaluation.*` mappings, `health,prometheus` exposure, unchanged backend port binding, and no management/public port.
- [ ] Write a failing backend integration test that requests `/actuator/prometheus` through the application server port with no separate management port.
- [ ] Run the contract check and endpoint test; confirm RED.
- [ ] Add `micrometer-registry-prometheus` runtime dependency and expose Prometheus through the existing Actuator server only.
- [ ] Add compose/env values for enabled, interval, initial delay, lock TTL, batch size, max per tick, max runtime, and metrics cache TTL.
- [ ] Run the contract check and endpoint test; confirm green.

### Task 4: Runbook and real query-plan evidence

**Files:**
- Create: `docs/ops/prediction-evaluation-runbook.md`
- Create: `scripts/prediction-evaluation-observability-drill.sh`
- Modify: `scripts/AGENTS.md`

- [ ] Write a self-contained isolated-compose drill that keeps the backend host bind on loopback, starts the mock stack, requests `/actuator/prometheus` through the existing backend port, verifies all prediction metric families, and cleans up.
- [ ] Start an isolated PostgreSQL 17 instance or use the test database, apply current Flyway migrations, and seed a documented representative prediction/outcome data set.
- [ ] Run the exact V19 keyset candidate SQL with `EXPLAIN (ANALYZE, BUFFERS)` using a documented cursor and limit.
- [ ] Record data size, due ratio, selected plan/indexes, buffer counts, planning time, and execution time without claiming index usage unless observed.
- [ ] Document enablement, same-boundary scrape verification, PromQL, alert thresholds, safe disable/rollback, cache staleness, and unsupported public exposure.
- [ ] Run `scripts/test-local-stack.sh` and `scripts/prediction-evaluation-observability-drill.sh`.

## Chunk 3: Review, verification, and integration

### Task 5: One implementation code review

**Files:**
- Review the complete feature diff only.

- [ ] Dispatch exactly one `code-reviewer` after implementation and targeted tests are green.
- [ ] Apply only concrete correctness/security/behavior-preservation fixes.
- [ ] Re-run affected targeted tests.

### Task 6: Full verification and delivery

**Files:**
- Reconcile the delta spec and runbook with the final diff.

- [ ] Verify zero `com.jmj.trade.order` imports in prediction main/test sources.
- [ ] Run `cd trading-backend && ./mvnw clean verify`.
- [ ] Run `cd web-dashboard && npm test`.
- [ ] Run `scripts/test-local-stack.sh`.
- [ ] Inspect the final diff and ensure no user-owned untracked `.omc`, `.omo`, or `AGENTS.md` files are staged.
- [ ] Commit feature work using the Korean commit convention.
- [ ] Switch to `design/modular-monolith-architecture`, squash merge the feature branch, verify backend/dashboard again, commit once, push, and confirm Release Gates green.
