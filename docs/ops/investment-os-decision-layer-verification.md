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
