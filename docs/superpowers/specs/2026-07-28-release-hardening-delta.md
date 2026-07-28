# Release Hardening Delta

## Scope

- Test-only delta. No production code, no new endpoints, no new behavior.
- One HTTP-level E2E test chains: session bootstrap → broker connection → verify →
  account sync → portfolio analysis → intelligence event → event review → paper order
  propose → approve → paper execution (COMPLETED), all through real controllers with a
  real CSRF token fetched from `/api/v1/session` (not the `.with(csrf())` bypass), matching
  how a real browser client behaves.
- Failure/duplicate/restart coverage added at the pipeline level (not per-module):
  propose/approve/review idempotent replay inside the full chain, an oversized order
  blocked by pre-trade risk without executing, a crashed-mid-sync `RUNNING` row recovered
  automatically by the real sync endpoint, and a crashed-mid-analysis `RUNNING` row
  correctly rejected as `ANALYSIS_ALREADY_RUNNING` (documented current behavior — analysis
  has no auto stale-recovery, only a monitoring gauge; this delta does not add one, since
  that would be a new feature).
- Security regression coverage: default Spring Security response headers are present on
  authenticated responses, cross-user access at every pipeline step returns owner-scoped
  404/403 with no side effects, and no credential/secret value leaks into any response body
  collected across the full chain.

## Minimal design

- New test package `com.jmj.trade.release` (crosses module boundaries, so it does not fit
  an existing module test package).
- `ReleaseWorkflowE2EIntegrationTest`: happy-path chain + duplicate/idempotency/restart
  scenarios. Reuses existing per-module test conventions: `PostgresIntegrationTest`, a
  `WireMockServer` analysis stub, a `TestConfiguration`-supplied `BrokerAdapter`,
  `broker.credentials.enabled=true`, `spring.datasource.hikari.maximum-pool-size=4`, and
  `@DirtiesContext(AFTER_CLASS)` (this is a new distinct Spring context signature, so it
  must not hold pooled connections open for the rest of the suite — same fix applied to
  `ScheduledPortfolioRefreshIntegrationTest`).
- `ReleaseSecurityRegressionIntegrationTest`: security headers + secret-leak + cross-user
  ownership sweep across entities created by one real pipeline run.
- No schema migration, no new dependency, no new production class.

## Plan

- [ ] Write failing E2E happy-path test (session → connection → sync → analysis → event →
      review → propose → approve → COMPLETED).
- [ ] Add duplicate/idempotency replay assertions inside the chain.
- [ ] Add oversized-order-blocked and crashed-sync-recovered/crashed-analysis-rejected
      restart scenarios.
- [ ] Write failing security regression test (headers, secret leak, ownership sweep).
- [ ] Run new tests, fix any real defect found (no scope expansion beyond the fix).
- [ ] Perform exactly one code review and fix findings.
- [ ] Run full verification (backend, analysis, dashboard, local stack, smoke).
- [ ] One feature commit, squash merge into base branch, push.
