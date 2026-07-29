# Paper Performance Analytics Delta

## Scope

- `GET /api/v1/broker-connections/{connectionId}/paper-performance` computes realized/
  unrealized P&L, fees/tax, win rate, turnover, and max drawdown from a connection's
  already-persisted paper fills (`execution_snapshots` joined through `broker_orders` to
  `order_intents`) — no new tables, no new write path. Every order in this system is a
  paper order (no live-order path exists), so no `PAPER`-type filter is needed on the query.
- KRW/USD kept **fully separate**: every metric is reported per-currency in a
  `byCurrency` map, never summed or FX-converted. A currency key appears iff that currency
  has at least one fill anywhere in the connection's history (independent of the requested
  period), so the set of currencies shown doesn't flicker as the user changes `from`/`to`.
- Period filter (`from`/`to`, default `[EPOCH, now]`), same defaulting/validation as
  `PortfolioHistoryService`. `maxPoints` (default 90, clamped 2–500) downsamples the
  drawdown equity curve using the same evenly-spaced-index approach.
- **No real-execution or guaranteed-return language anywhere** — this is paper-trading
  simulation only. The dashboard panel carries a fixed disclaimer
  ("모의(Paper) 매매 시뮬레이션 결과이며 실제 주문 체결이나 수익을 보장하지 않습니다") and
  no copy elsewhere implies otherwise.

## Fill model (reuse, nothing new)

- One row per `broker_order` = its **latest** `execution_snapshot` (fields are cumulative,
  so the latest row already is the effective fill: final quantity, avg price, cumulative
  commission/tax, currency, `captured_at`) — same convention `OrderSubmissionService
  .latestExecution` already uses internally.
- Query shape: `order_intents` (`user_id`, `broker_connection_id`, `side`, `symbol`) JOIN
  `broker_orders` (`order_intent_id`) JOIN LATERAL `execution_snapshots` (latest per
  `broker_order_id`, `filled_quantity > 0`). `es.currency` (not `oi.trading_currency`) is
  the grouping key — it's the value actually recorded at fill time.
- Fee/tax are **read, not recomputed** — `PaperTradingBroker.execution()` already persists
  cumulative `commission`/`tax` per broker order (tax is SELL-only by construction); this
  feature never re-derives them from a rate.

## Noted scope decisions (not bugs)

- **FIFO uses full history, period only selects which closes are reported.** To correctly
  match a SELL within `[from, to]` against BUYs that may predate `from`, the service walks
  *all* fills for the connection in `captured_at` order to build FIFO lots per
  `(symbol, currency)`, regardless of the requested period. Only SELL closing events whose
  own `captured_at` falls in `[from, to]` count toward `realizedPnl`/`winRate`/
  `closedTradeCount` for that period. Turnover and fees/tax sum all fills (BUY+SELL) with
  `captured_at` in `[from, to]`. This mirrors how real trade-history reports work and avoids
  spurious "SELL with no matching lot" artifacts at period boundaries.
- **No shorting assumed.** Pre-trade risk checks prevent selling more than is held, so every
  SELL fill is assumed to fully match against existing FIFO lots. If this invariant is ever
  violated (data bug, manual DB edit), the service does **not** attempt to model a short
  position — it clamps matched quantity to available lot quantity and logs nothing further;
  this is a defensive clamp, not a feature.
- **Unrealized P&L marks against the latest fill price for that symbol as of `to`**, not
  against `position_snapshots.last_price`. `position_snapshots` reflects the *real* Toss
  account's actual holdings (synced from the broker), which is a different (real) position
  set from the *simulated* paper position this feature tracks — mixing them would silently
  conflate real and paper data. There is no simulated live-quote feed in this system, so
  "most recent paper fill price" is the only price basis available; this is a known
  approximation, stated here rather than left implicit.
- **Equity curve (for drawdown) is period-local, not lifetime-cumulative.** The curve starts
  at 0 at `from` and accumulates realized P&L only from SELL closes with `captured_at` in
  `[from, to]`, in chronological order — so max drawdown reflects peak-to-trough *within the
  requested window*, not a dip that happened before `from`. This matches the intuitive
  reading of "drawdown over this period."
- **No quality/staleness flags** (`stale`/`unknown` from `PortfolioReadService`) — those
  describe sync freshness of the *real* broker account, a dimension that doesn't apply to
  paper fills (they're written synchronously at approval time, never synced). Only
  `unavailable` (no paper fills exist for this connection, ever) is surfaced, analogous to
  `PortfolioHistoryService`'s `hasAnySuccessfulRun` check.
- **Max drawdown is reported only as a currency amount, no percentage.** The equity curve
  starts at 0 (previous bullet), so a peak-relative rate is unbounded — a curve of
  `[+1, -100]` would report a "10000% drawdown," which is not a meaningful number. Amount
  alone (peak-to-trough in currency units) is well-defined regardless of how small the peak
  was.
- **Unrealized P&L nets the already-incurred buy-side fee**, same as the realized path
  (which nets both buy- and sell-side fee/tax). An open lot's contribution is
  `(markPrice − lotPrice) × remainingQty − buyFeePerUnit × remainingQty` — the sell fee/tax
  isn't included since that trade hasn't happened yet. This keeps `realizedPnl` and
  `unrealizedPnl` on the same net-of-cost-so-far convention rather than mixing gross and net
  figures on one panel.

## Minimal design

- `PaperPerformanceAnalyticsService` (new, `com.jmj.trade.order`, package-private except
  response records) — lives in the `order` package (owns `execution_snapshots`/
  `order_intents`/`broker_orders`) rather than `account`, since this feature never touches
  `account_sync_runs`/`account_snapshots`. Depends on `JdbcTemplate` directly, no JPA.
  Ownership check (`broker_connections` `user_id`/`ACTIVE`/`deleted_at IS NULL`) is a local
  copy of `PortfolioHistoryService.requireOwnedConnection` — this codebase copies this kind
  of small query rather than extracting a shared helper (see that service's own docs).
- `PaperPerformanceAnalyticsController` — copies `PortfolioHistoryController`'s exact shape:
  `Principal → userId()` → private static-nested `InvalidUserException`, one
  `PaperPerformanceAnalyticsException` (single `INVALID_INPUT` code, for bad period/
  `maxPoints` bounds) + `@ExceptionHandler`. Ownership 404 falls through to the existing
  global `BrokerConnectionErrorHandler`.
- FIFO lot matching, win-rate, turnover, drawdown are computed in Java over the fetched fill
  rows (same "row-mapping + post-processing in Java" style as `PortfolioHistoryService`'s
  downsampling) — no SQL-side aggregation beyond the join/fetch.
- `V16__index_paper_fill_lookup.sql` adds indexes on `order_intents(user_id,
  broker_connection_id)`, `broker_orders(order_intent_id)`, and
  `execution_snapshots(broker_order_id, captured_at DESC, id DESC)` — none of the three
  existed before this feature, and this service's fill lookup (unlike `PortfolioHistoryService`,
  which caps at `MAX_ROWS`) has to walk full per-connection fill history for correct FIFO, so
  it can't rely on a `LIMIT` to bound cost the way history does.
- Frontend: `app/paper-performance-view.js` (hand-rolled inline SVG equity-curve sparkline
  per currency, following `portfolio-history-view.js`'s pattern — no charting library
  exists in this project), `loadPaperPerformance` in `lib/api.js`, wired into `page.js`
  alongside the existing dashboard/history load, with its own try/catch so a failure here
  doesn't blank the rest of the dashboard (same pattern as the history panel).

## Plan

- [x] Add `PaperPerformanceAnalyticsService`/`Exception`/`Controller` with failing tests for:
      FIFO realized P&L (multi-lot partial matches), unrealized mark-to-market, fee/tax
      totals, win rate, turnover, period-local drawdown, currency separation, ownership,
      period/`maxPoints` validation, `unavailable` when no fills exist.
- [x] Add dashboard panel (equity sparkline + metrics table + disclaimer + filter form) +
      API client function + tests.
- [x] Perform exactly one code review and fix findings.
- [x] Run full verification (backend, analysis, dashboard, local-stack, existing drills).
- [ ] One feature commit, squash merge into base branch, push.
