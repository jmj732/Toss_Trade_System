# Scheduled Portfolio Refresh Delta

## Scope

- Spring Boot periodically syncs and analyzes ACTIVE broker connections; no other service schedules.
- One sweep per interval refreshes each due connection: account sync first, then portfolio analysis.
- A PostgreSQL lease row makes the sweep single-writer across instances and survives crashes via TTL.
- Per-connection failures are isolated: the sweep records the failure and continues with the next
  connection.
- Retries are bounded: a connection with `maxConsecutiveFailures` failed syncs since its last success
  is skipped until a successful sync happens.
- Manual runs win: connections with a `RUNNING` sync or analysis are skipped, never failed.
- Stale connections are skipped: `last_validated_at` null or older than `connectionStaleAfter`.
- Refresh never creates order intents, order suggestions, or broker order calls.
- Scheduling is off by default (`portfolio.refresh.enabled=false`) and requires the credential vault.
- Alerting, backoff tuning UI, and dashboard surfacing are excluded.

## Minimal design

- New module `com.jmj.trade.refresh`.
- `ScheduledRefreshProperties` (`portfolio.refresh`): enabled, interval, initialDelay, lockTtl,
  connectionStaleAfter, maxConsecutiveFailures, batchSize.
- `ScheduledRefreshLease`: `INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at <= now` acquire,
  owner-scoped delete release. Migration `V13__create_scheduled_refresh_leases.sql`, one row.
- `ScheduledPortfolioRefreshService.refresh()`: acquires lease, selects due candidates in one SQL
  query, reuses `AccountSyncService` and `PortfolioAnalysisWorkflowService` per connection, releases
  lease in `finally`. Returns `RefreshSweepResult(lockAcquired, refreshed, failed, skipped)`.
- `ScheduledPortfolioRefreshScheduler`: `@Scheduled` wrapper, only bean gated by `enabled`.
- Observability reuses existing conventions: per-connection UUID `correlation_id` in MDC so the
  FastAPI call is correlated, one completion log per connection and per sweep with
  `operation=scheduled_refresh`, and `trade.operation.duration{operation=scheduled_refresh}`.
- No new dependency; `spring-context` scheduling only.

## Delta from existing design

- Due-time selection uses the last **successful** sync `completed_at` at the connection's current
  `credential_revision`; a credential rotation therefore makes a connection immediately due.
- Retry limiting is derived from `account_sync_runs` history, not stored counters.
- `@EnableScheduling` is scoped to the refresh configuration instead of the application class.

## Plan

- [ ] Add failing integration test: due connection is synced then analyzed, no order rows.
- [ ] Add failing tests: lease blocks concurrent sweeps and expires after TTL.
- [ ] Add failing tests: failure isolation, retry limit, RUNNING conflict skip, stale skip.
- [ ] Add lease migration and `ScheduledRefreshProperties`.
- [ ] Implement lease, candidate query, and sweep with per-connection isolation.
- [ ] Wire configuration, scheduler, and observability defaults.
- [ ] Run related backend tests.
- [ ] Perform exactly one code review and fix findings.
- [ ] Run full verification (backend, analysis, dashboard, local stack, smoke).
- [ ] One feature commit, squash merge into base branch, push.
