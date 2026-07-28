# Observability Foundation Delta

## Scope

- Spring request boundary owns correlation IDs and Micrometer operation timing.
- `X-Correlation-Id` is accepted only as a canonical UUID; otherwise UUID generated.
- Correlation ID is returned to caller and forwarded to FastAPI analysis.
- FastAPI echoes correlation ID and emits one JSON completion log without request data.
- Spring console logs use built-in ECS JSON; MDC contributes `correlation_id`.
- Existing `http.server.requests` remains request metric; `trade.operation.duration` adds bounded
  `operation=analysis|sync|order|request` and `outcome=success|failure` tags.
- DB-backed gauges expose stale `RUNNING` analysis/sync counts and unpublished outbox counts.
- Readiness includes PostgreSQL, Redis, and FastAPI analysis; liveness remains process-only.
- Logs contain no credentials, authorization values, request bodies, or account numbers.
- Alert rules, exporters, tracing backends, dashboards, and external monitoring are excluded.

## Minimal design

- One servlet filter: correlation, safe completion log, operation timer.
- One `MeterBinder`: four query-on-scrape gauges.
- One `HealthIndicator`: bounded FastAPI readiness request.
- No schema migration or new dependency.

## Plan

- [ ] Add failing Spring correlation/metric tests.
- [ ] Add failing FastAPI correlation/log tests.
- [ ] Add failing gauge and readiness tests.
- [ ] Implement minimum correlation and structured logging.
- [ ] Implement Micrometer gauges and dependency readiness.
- [ ] Run related tests and local-stack smoke.
- [ ] Perform exactly one code review and fix findings.
- [ ] Run full verification, one feature commit, squash merge, push.
