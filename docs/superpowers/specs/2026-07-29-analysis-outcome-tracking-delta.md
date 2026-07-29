# Analysis Outcome Tracking Delta

## Scope

- New standalone domain: a **prediction ledger**, not an extension of
  `com.jmj.trade.analysis` (portfolio concentration/weight) or `com.jmj.trade.intelligence`
  (manual event ingestion). Neither of those produces a directional call today — both are
  point-in-time weight/concentration math with no direction, target price, confidence, or
  horizon. Inventing a "predicted direction" by reading a signal out of them (e.g. sign of
  `profitLossRate`) would be a fabricated metric dressed up as a real prediction, which this
  delta explicitly avoids.
- `POST /api/v1/broker-connections/{connectionId}/analysis-predictions` — caller (a person
  or, later, a real model) explicitly submits `{symbol, currency, predictedDirection
  (UP|DOWN), modelVersion, contractVersion}`. The service fetches a **live quote** via the
  existing `BrokerAdapter.getQuote` (same call `PaperOrderWorkflowService` already uses to
  price a paper fill) and stores it as the baseline price, at server-clock `predictedAt`.
  This feature only stores and grades predictions — it does not generate them.
- `GET /api/v1/broker-connections/{connectionId}/analysis-predictions` — before computing
  anything, evaluates any (prediction, horizon) pairs that have matured
  (`now >= predictedAt + horizonDays`) and haven't been graded yet: fetches one live quote
  per due pair, writes an immutable outcome row. Returns both the raw prediction+outcome
  rows and a performance breakdown grouped by `(modelVersion, contractVersion, horizon)`:
  sample count, direction hit rate, average directional return, average max adverse
  excursion.
- **No order or auto-trading linkage of any kind.** This package never imports from
  `com.jmj.trade.order`; the only broker capability used is the read-only `getQuote` market
  data call, not order submission. Nothing here can create, approve, or influence an order.
- **KRW/USD not mixed** — `currency` is stored per prediction and never converted; the
  performance breakdown groups also include currency implicitly via the prediction's own
  currency field (a `(modelVersion, contractVersion)` group naturally spans one currency at
  a time per symbol, and cross-currency symbols are never summed together since every
  metric here is a per-prediction return/hit, not an amount).

## Noted scope decisions (not bugs)

- **Horizons are calendar days, not trading days** (`predictedAt + 1/5/20 days`). No trading
  calendar concept exists anywhere in this codebase; adding one is out of scope for this
  delta. Documented here rather than silently assumed.
- **Evaluation is lazy-on-read, not a background scheduler.** `com.jmj.trade.refresh` has a
  reusable lease-based scheduled-sweep pattern, but adding a second scheduler (and its own
  lease table) for this feature is more than this delta needs — evaluating due horizons as
  part of the read that a user is already making is simpler and suffices. The real cost of
  this choice isn't just latency: **a connection that goes unread for a while accumulates
  overdue horizons**, and a prediction's D1/D5/D20 grades can each end up graded later than
  their nominal horizon (whenever the next read happens to land), not necessarily days apart
  from each other. `evaluateDue` bounds the worst failure mode of this — grading at most one
  horizon per prediction per read (see below) — but does not eliminate lateness itself. A
  scheduled sweep, run daily, is the durable fix and can be added later without changing the
  grading logic; this delta accepts the lazy tradeoff for now and states it plainly rather
  than only describing it as a latency concern.
- **At most one horizon is graded per prediction per read**, even if several (e.g. D1 and
  D5) are simultaneously overdue because the connection went unread for a while. Horizons
  are checked in ascending order (D1, D5, D20) and grading stops at the first one that isn't
  due yet — a shorter horizon not being due implies a longer one isn't either. Without this
  cap, multiple overdue horizons would all be graded against the *same* live quote in one
  pass, making D1/D5/D20 numerically identical for that prediction — silently worse than
  leaving the later ones ungraded until a subsequent read happens to catch each one nearer
  its own due time.
- **A live-quote failure for the one pair being evaluated is caught and skipped, not
  propagated.** `evaluateDue` calls the broker for exactly one due pair per prediction per
  read; if that call throws (broker outage, delisted/invalid symbol), the pair is left
  ungraded and retried on the next read — it does not fail the whole `GET`, and does not
  block other predictions in the same response from being returned or evaluated.
- **Quotes are memoized per symbol within one evaluation pass**, so several predictions on
  the same symbol maturing in the same read cost one live broker call, not one per
  prediction. This does not bound the *number of due predictions* that can accumulate before
  a read (still an open scaling concern for an account with many stale, never-reopened
  predictions), only the redundant same-symbol calls within a single pass.
- **No continuous intraday price path exists**, so "max adverse excursion" is computed only
  from the discrete points this feature actually captures: baseline price at `predictedAt`,
  plus whichever of D1/D5/D20 have been evaluated so far. This is a coarser, honestly-scoped
  approximation, not true intraday MAE — stated explicitly rather than implied.
- **"오차" (error) is operationalized as average directional return**, not a distance from a
  predicted magnitude — there is no predicted price/magnitude to compare against, only a
  direction. `directionalReturn = actualReturn * (+1 if predictedDirection == UP else -1)`:
  positive means the call was, on average, profitable to have followed; negative means it
  moved against the call. This is the natural error/performance measure for a
  direction-only prediction and is reported as `avgDirectionalReturn`, not mislabeled as a
  generic "error" field.
- **No leakage by construction, not by a special guard.** Because both the baseline and
  every outcome price come from a *live* quote call made *at* evaluation time (never a
  caller-supplied price, never a historical replay), there is no dataset to peek ahead in.
  The only leakage risk this delta actually guards against is evaluating a horizon before
  its wall-clock time has passed — `evaluateDue` only calls the broker for a
  `(prediction, horizon)` pair when `now >= predictedAt + horizonDays`, and the
  `UNIQUE (prediction_id, horizon)` constraint plus an append-only trigger on
  `analysis_prediction_outcomes` make a horizon's grade permanent once written (it can never
  be quietly re-priced with a later, more favorable quote).
- **An exactly-flat return counts as a miss for both UP and DOWN predictions.**
  `directionCorrect` requires strictly positive (UP) or strictly negative (DOWN) return —
  `signum() == 0` is false either way. A directional call that produced no price movement at
  all did not pay off in either direction, so scoring it as correct for whichever side
  happened to be predicted would be more misleading than scoring it a miss. This is rare for
  liquid symbols at `NUMERIC(28,10)` precision but stated as a deliberate choice, not an
  overlooked edge case.
- **No idempotency-key ledger on create.** `PaperOrderWorkflowService` requires
  `Idempotency-Key` because a duplicate paper order submission is a real financial/state
  problem. A duplicate prediction row from a client retry has no such consequence (it just
  grades independently) — the machinery `SubmissionIdempotencyKey` provides isn't
  proportionate here, so plain POST semantics are used.

## Minimal design

- New package `com.jmj.trade.prediction` (not `com.jmj.trade.analysis` — this is a
  genuinely separate bounded context from portfolio-concentration analysis, matching this
  codebase's existing pattern of one package per domain concern even for closely related
  features, e.g. `risk`/`notification`/`refresh` next to `order`/`account`).
- `AnalysisPredictionService` (package-private except response records and the public
  constructor) — no JPA entities, `JdbcTemplate` directly, matching
  `PortfolioHistoryService`/`PaperPerformanceAnalyticsService`'s lighter modern style.
  Depends on `BrokerAdapter` for `getQuote`. Ownership check (`broker_connections`
  `user_id`/`ACTIVE`/`deleted_at IS NULL`) is a local copy of the same small query every
  other connection-scoped service in this codebase repeats. **Not a component-scanned
  `@Service`** — every other `BrokerAdapter` consumer in this codebase
  (`PaperOrderWorkflowService`, `AccountSyncService`, `BrokerConnectionValidationService`,
  `PreTradeRiskEngine`) is wired as a `@Bean` inside `CredentialVaultConfiguration`, which
  only exists when `broker.credentials.enabled=true`; this service is wired the same way
  (a new `analysisPredictionService` `@Bean` method there) rather than as a plain always-on
  `@Service`, so it never demands a `BrokerAdapter` bean in a context where credentials
  aren't configured at all. (A first pass got this wrong — an unconditional `@Service` here
  broke `PaperTradingBrokerIntegrationTest`'s context, which sets no `broker.credentials.*`
  properties and provides no `BrokerAdapter` bean of its own — caught in code review.)
- `AnalysisPredictionController` — `Principal → userId()` → `InvalidUserException`,
  `AnalysisPredictionException` (`INVALID_INPUT`, `QUOTE_CURRENCY_MISMATCH`) +
  `@ExceptionHandler`; broker-call failures (`BrokerException`) and connection-not-found
  (`BrokerConnectionException`) already fall through to the existing global
  `BrokerConnectionErrorHandler` — no local handling needed for those. Gated by the same
  `@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue =
  "true")` as `PaperOrderWorkflowController`, since both need decrypted broker credentials
  to call `getQuote`.
- `V17__create_analysis_predictions.sql`: `analysis_predictions` (id, user_id,
  broker_connection_id FK to `broker_connections(user_id, id)`, symbol, currency,
  predicted_direction, model_version, contract_version, baseline_price, predicted_at,
  created_at — no update path is ever exposed, so no immutability trigger is added here) and
  `analysis_prediction_outcomes` (id, prediction_id, horizon `D1|D5|D20`, price,
  actual_return, direction_correct, observed_at, `UNIQUE(prediction_id, horizon)`,
  append-only trigger matching the `execution_snapshots`/`risk_policy_history` convention).
- Frontend: `app/analysis-outcome-view.js` (predictions table + per-`(modelVersion,
  contractVersion, horizon)` performance summary table, a small create form), `lib/api.js`
  functions, wired into `page.js` alongside the other connection-scoped panels, with its own
  try/catch so a failure here doesn't blank the rest of the dashboard.

## Plan

- [x] Add `AnalysisPredictionService`/`Exception`/`Controller` + migration, with failing
      tests for: baseline capture via live quote, lazy horizon evaluation (only when due),
      no-leakage guard (horizon not evaluated early), outcome immutability, direction
      hit-rate/avgDirectionalReturn/MAE aggregation, ownership, ownership across
      model/contract version filters, ensure no import from `com.jmj.trade.order` anywhere
      in the new package.
- [x] Add dashboard panel (predictions table + performance summary + create form) + API
      client function + tests.
- [x] Perform exactly one code review and fix findings.
- [x] Run full verification (backend, analysis, dashboard, local-stack, existing drills).
- [ ] One feature commit, squash merge into base branch, push.
