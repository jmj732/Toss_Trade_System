# Production readiness closeout

## Goal

운영자가 실제 설정된 provider 자격증명으로 수집·degrade·freshness를 재실행 점검하고,
실거래 canary·scheduler·kill switch·데이터 지연을 한 readiness 화면과 알림에서 확인한다.

## Decision deltas

- Add authenticated `GET /api/v1/operations/readiness` and
  `POST /api/v1/operations/readiness/provider-check`. The POST accepts one validated symbol and
  calls every enabled stock-analysis provider through the existing `StockAnalysisInputAssembler`.
- Persist only redacted provider status, missing-data reason codes, source `asOf`, collection time,
  and lag in append-only `production_readiness_checks`; never persist provider values, API keys,
  tokens, raw responses, or account numbers. Every run gets a new evidence ID, so failed checks are
  safe to rerun.
- Mark each provider `HEALTHY`, `DEGRADED`, `STALE`, `UNAVAILABLE`, `SECRET_MISSING`,
  `NOT_CONFIGURED`, or `DISABLED`. Overall provider check is ready only when every enabled provider
  returns complete, fresh data; partial provider failure remains visible as degraded.
- Readiness exposes canary default-disabled/config/credential/allowlist state, scheduler flags,
  kill-switch state, latest provider evidence and max data lag. No readiness path places, retries,
  cancels, or resubmits an order, and no path raises canary limits.
- Non-ready provider checks emit `PRODUCTION_READINESS_ALERT` through the existing notification
  outbox. Alert payload contains only safe codes and the evidence ID.
- Add a Settings dashboard panel to refresh readiness and rerun provider checks. Secrets and raw
  provider payloads are never rendered.

## Acceptance evidence

- Unit tests prove stale/degraded/unavailable classification and no raw provider value/secret in
  evidence.
- Integration test proves a real configured provider probe writes redacted evidence, partial
  provider failure degrades, stale source data is reported, and rerunning creates a new evidence
  row without any order rows or broker mutation.
- Controller/UI tests prove readiness fields and provider-check CSRF path are exposed without
  secrets.
- Fault injection covers provider timeout and stale source response; both fail closed for readiness
  and emit a safe operational alert.

## Verification

1. Run focused backend RED/GREEN tests and provider fault-injection integration test.
2. Review changed diff once for secret leakage, order side effects, and limit changes.
3. Run backend `./mvnw clean verify`, analysis `pytest`, dashboard `npm test`, and local-stack
   smoke checks available in the workspace.
4. Commit Korean `기능 :: 운영 readiness closeout`, squash-merge into
   `design/modular-monolith-architecture`, push, and verify remote branch/CI state.
