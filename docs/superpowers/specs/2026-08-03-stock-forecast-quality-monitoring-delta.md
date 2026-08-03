# Stock forecast quality monitoring delta

## Goal

- Link the existing D1/D5/D20 outcome grades to immutable stock forecast metrics and expose
  evidence-based performance, data sufficiency, drift, degradation, and delayed-grading state.
- Keep analysis-service, stock forecast responses, Gemini explanations, and order decisions
  unchanged. This feature is read-only monitoring over existing persisted rows.

## Decisions

- Reuse `analysis_prediction_outcomes` as the only grading source. The existing opt-in
  `PredictionEvaluationScheduler` remains the evaluator; it already uses a PostgreSQL TTL lease,
  `ON CONFLICT DO NOTHING` outcome writes, and quote memoization. Its per-item evaluation loop
  will catch unexpected item failures so one malformed/failed item cannot stop the tick.
- Add a backend read service that joins connection-linked `stock_forecasts`,
  `analysis_predictions`, and `analysis_prediction_outcomes`. It parses the persisted forecast
  response without changing it and computes metrics only when the corresponding horizon outcome
  exists; forecasts without a ledger prediction are not gradable in this connection-scoped view.
- D1 uses `forecast.d1_up_probability`: hit rate uses the existing directional grade, while
  calibration uses non-flat outcomes only, with actual-up label, mean calibration error, and
  Brier score. D5/D20 use the expected-return metric: strict sign hit rate, signed error
  (`actualReturn - expectedReturn`), and mean absolute error. Flat returns are not treated as a
  directional hit and are excluded from D1 calibration samples.
- Rows are grouped by symbol, model version, contract version, and horizon. The requested
  period is applied to forecast evaluation time; a same-length immediately preceding period is
  used as the drift baseline. Current and baseline values are compared only when both have at
  least the configured minimum sample count (default 10). Otherwise the row reports
  `DATA_SHORTAGE`/`NO_BASELINE` and null conclusions instead of a strong performance claim.
- A row exposes `sampleCount`, `eligibleForecastCount`, `pendingCount`, `minimumSampleCount`,
  `status`, `hitRate`, error/calibration metrics, and a drift object. Degradation/drift flags are
  emitted only for sufficient samples using fixed, documented thresholds; raw deltas remain
  visible so operators can inspect the evidence.
- Extend the existing prediction operations read model with `longUngradedCount` and
  `oldestLongUngradedDueAt`, derived from current database rows using a configurable
  `prediction.evaluation.long-ungraded-after` (default 24 hours). No new order or notification
  path is introduced.
- Extend the existing prediction performance response and dashboard panel rather than creating a
  parallel route. Existing outcome tables remain unchanged; a separate forecast-quality table
  makes data shortage and conclusion status explicit. The UI continues to state that monitoring
  does not place orders or modify forecast/analysis/Gemini output.

## API delta

- `GET /api/v1/broker-connections/{connectionId}/analysis-predictions` accepts optional `symbol`
  and returns `forecastQuality` plus the existing `predictions` and `byVersion` fields.
- `forecastQuality` contains the effective period, baseline period, minimum sample count, and
  rows grouped by symbol/model/contract/horizon. Existing clients can ignore the additive field.
- `GET /api/v1/prediction-operations` keeps its existing fields and adds long-ungraded state.

## Safety and non-goals

- No forecast, analysis, or Gemini result is updated or recalculated.
- No quality metric is consumed by order approval, risk, broker, or any automatic action.
- No new dependency, background summary table, or materialized view is added; metrics are a
  bounded read query over existing immutable data.

## Verification

- Backend unit/integration tests cover metric math, D1 calibration/flat handling, D5/D20 error,
  symbol/version grouping, period baseline and sample shortage, drift/degradation suppression,
  long-ungraded operations state, and scheduler item-failure isolation.
- Dashboard tests cover quality rows/statuses, drift/degradation labels, and long-ungraded
  operations metrics.
- Run backend `./mvnw clean verify`, dashboard `npm test`, analysis-service `pytest`, and the
  repository local-stack smoke/test scripts when their prerequisites are available.
