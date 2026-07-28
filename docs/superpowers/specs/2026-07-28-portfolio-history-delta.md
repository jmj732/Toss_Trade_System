# Portfolio History Delta

## Scope

- `GET /api/v1/broker-connections/{connectionId}/portfolio-history` reads the asset/P&L
  trend from already-persisted successful sync snapshots (`account_sync_runs` joined to
  `account_snapshots` by `sync_run_id`) — no new tables, no new write path.
- KRW/USD kept separate throughout: amounts stay in the flat `{"KRW":...,"USD":...}` map
  exactly as stored, never summed or converted between currencies.
- Period filter (`from`/`to`, default `[EPOCH, now]`) and downsampling (`maxPoints`,
  default 90, clamped 2–500) — downsampling picks evenly spaced points from up to 2000
  matching rows (fetched newest-first so a connection with more history than the row cap
  keeps its most recent points, not its oldest).
- `stale`/`unknown`/`unavailable` surfaced at the top level, reusing
  `PortfolioReadService`'s existing quality computation (same `stale`/`staleReason`/
  `unknownFields` the single-snapshot dashboard already shows) rather than inventing a
  parallel notion of freshness. `unavailable` means "no successful sync ever" for the
  connection, distinct from an empty `points` list for a period with no data (that's just
  normal, not a failure).
- `partial: true` on the response's `data` object whenever the returned series is smaller
  than what matched the period (downsampling or the row-cap safety valve), with
  `totalMatched`/`returnedPoints` so the UI can say "showing N of M".
- **No FX conversion, no return-rate estimation.** `profitLossRate`/`dailyProfitLossRate`
  are passed through as already-stored, broker-reported figures per point — never computed
  or derived by this delta (no period-over-period % change, no cumulative return, no CAGR).
- Dashboard UI: a new "Asset & P/L trend" panel (KRW/USD sparklines + a raw points table),
  loaded alongside the existing dashboard/events fetch when a connection is opened, with a
  from/to/maxPoints filter form that re-queries on submit.

## Noted scope decision (not a bug)

The history query does **not** filter by the connection's current `credential_revision`
(unlike `PortfolioReadService`, which does — because a stale-revision run means "not
trustworthy as the CURRENT state"). For history, an older run from before a credential
rotation is still genuine past data for the same account, and excluding it would silently
truncate a user's trend every time they update credentials. Freshness (the top-level
`stale`/`unknown` flags) is still computed from the connection's current state via
`PortfolioReadService`, so "is what I'm looking at right now trustworthy" and "what
happened over time" are answered independently, on purpose.

## Minimal design

- `PortfolioHistoryService` (new, `com.jmj.trade.account`, package-private except the
  response records) — depends on `PortfolioReadService` (for the top-level quality flags)
  and `JdbcTemplate` directly (for the time-series query). No new migration: the existing
  partial index `ix_account_sync_run_latest_success` on
  `account_sync_runs(user_id, broker_connection_id, completed_at DESC, id DESC)
  WHERE status='SUCCEEDED'`, plus the unique index on `account_snapshots.sync_run_id`,
  already cover the query shape (filter/sort via `account_sync_runs`, join snapshots by
  `sync_run_id` second).
- `PortfolioHistoryController`, matching `DashboardReadModelController`'s exact shape:
  `Principal` → `userId()` → owner-scoped service call, private `InvalidUserException` +
  handler, `PortfolioHistoryException` (single `INVALID_INPUT` code) + handler.
  `BrokerConnectionException` (not-owned/not-found) is already handled globally by
  `BrokerConnectionErrorHandler` — no local handler needed for that one.
- Downsampling is done in Java (evenly-spaced index selection), not SQL `date_trunc` —
  there was no existing bucketing precedent in this codebase, and the row-mapping +
  post-processing-in-Java style matches every other service here.
- Frontend: `app/portfolio-history-view.js` (hand-rolled inline SVG sparkline — no
  charting library exists in this project and one wasn't added for a single trend panel),
  `loadPortfolioHistory` in `lib/api.js`, wired into `page.js` alongside the existing
  dashboard/events load.

## Plan

- [x] Add `PortfolioHistoryService`/`Exception`/`Controller` with failing period-filter/
      downsampling/quality/ownership/validation tests.
- [x] Add dashboard trend UI (sparklines + table + filter form) + API client function +
      tests.
- [ ] Perform exactly one code review and fix findings.
- [ ] Run full verification (backend, analysis, dashboard, local-stack, existing drills).
- [ ] One feature commit, squash merge into base branch, push.
