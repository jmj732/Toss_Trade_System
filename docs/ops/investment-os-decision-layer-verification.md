# Investment OS Decision Layer Verification

Local verification only; no production deployment, order endpoints, or automatic rule tuning. Existing dirty monitoring work preserved.

## Phase 1 evidence (2026-09-29)
- Python full regression: 75 PASS.
- Spring full regression: 887 enumerated, 886 PASS, one optional HTTP-runtime test skipped in default suite. Subsequent fresh focused runtime gate: 10 API + 2 actual FastAPI/PostgreSQL tests PASS, zero skips/errors/failures.
- Actual FastAPI HTTP -> Spring owner-scoped API -> PostgreSQL17 V50 -> immutable snapshots -> GET readback: PASS for genuine downloaded FRED input.
- Same six observations -> Python replay -> actual Spring MonitoringEvaluationPersister: persisted effective market states and escalation count match, including UNKNOWN hold, delayed older adverse axis and complete recovery. Replay itself creates zero notifications.
- Unit/integration/failure/persistence/replay/existing regression checks run. Upstream malformed/invalid/timeout boundaries, rollback and append-only constraints covered.
- Historical actual import: eight FRED series, 13 stress/control windows, 2,873 selected observations. Full replay files under /tmp/noddy-historical-replay; compact report in historical-replay-results.json. Current executed code/rules/config hashes checked equal.
- Public graph history is HISTORICAL_APPROXIMATION. No point-in-time/release-vintage proof. HY/IG currently returned only from 2023-09-29; prior credit coverage unavailable.
- Price history access: Stooq returned browser-verification HTML; Yahoo SPY public chart returned429. SPY/QQQ/IWM historical OHLC unavailable; no index proxy used. Returns/outcomes/FP/FN/lead-time metrics remain unmeasured at this phase.

Commands: analysis-service/.venv/bin/python -m pytest -q (from analysis-service); Java21 + DOCKER_HOST=unix:///Users/jjm/.colima/default/docker.sock + TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw test -q (from trading-backend); ./mvnw test -q -Dtest=MonitoringReplayApiIntegrationTest,MonitoringReplayLiveAnalysisIntegrationTest -Dmonitoring.replay.runtime-url=http://127.0.0.1:18171 with local Uvicorn app.main:app listening on18171.

Later phase evidence pending; do not interpret this as six-layer completion or production approval.

## Phase 2 runtime evidence (2026-10-03)

The previously local-only evidence above remains scoped to its 2026-09-29 verification; it is not evidence of a production rollout. The later deployment is tracked separately here: PR57 (investment data engine and V52) and PR58 (preserve provider HTTP status) were merged, followed by PR59 (typed intraday price fields), deployed at `93be55cc1656f01d79f3139b21927eb34b64dc4f`. The deployed service was healthy, V52 had succeeded, and the scheduler was enabled.

The runtime snapshot captured at `2026-10-02T13:21:14Z` had `PROVIDER_HTTP_402`; this records that point in time, not live status. The held symbols in that snapshot were AVT, CSTM, LUNR, and RDW. Prior requests for all four held symbols returned HTTP 402; detailed evidence includes AVT quote and a separate EOD light probe. AAPL quote returned HTTP 200 as a control, which does not establish plan or route coverage for the held symbols. The account portal was signed out, so the key's account plan and minimum qualifying plan remain unverified. No purchase or authenticated account change was made. Keep the historical values in the decision layer unchanged while this provider failure persists.

Full capture is scheduled at 16:15 America/New_York on weekdays. The 08:00 run refreshes the portfolio and captures quote fields only; intraday quote updates run every five minutes. Startup bootstrap gets three full-capture attempts, then yields to the scheduled after-close capture. There is no exposed manual recapture endpoint.

A no-key SEC EDGAR company-facts probe returned HTTP 200 for the four symbols and resolved CIKs AVT `0000008858`, CSTM `0001563411`, LUNR `0001844452`, and RDW `0001819810`. This supports a partial free filed-fundamentals path, but the deployed SEC mapping is still static-Apple filing metadata; no per-ticker CompanyFacts ingestion or values were added. SEC facts cannot provide prices or analyst estimates. FMP Basic is free with 250 calls/day, but its symbol restrictions and lack of TTM income/cash-flow access fail this configured bundle; the exact paid plan minimum remains unverified because FMP does not list the configured EOD full route. Twelve Data and Alpha Vantage are documented only as unverified price-data candidates, not selected or integrated. The [backend runbook](backend-cd.md#fmp-entitlement-recovery) records the taxonomy and as-of constraints and other unverified free-tier options.

Revision history has not been established by a successful estimate capture. Captures preserve collection time as `asOf` and the estimate row date as `horizon`; 30D and 90D revisions require earlier eligible snapshots with the same source and horizon. They must stay `DATA_MISSING` until such baselines exist. FMP's current estimate mapping supplies revenue, EPS, and EBITDA consensus, not FCF consensus. See [backend CD and FMP entitlement recovery](backend-cd.md#fmp-entitlement-recovery) for the purchase gate, exact routes, secret handling, and validation sequence.
