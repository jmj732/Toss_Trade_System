# Analysis Service Foundation Delta

- Add an independent `analysis-service` FastAPI process.
- Expose `POST /internal/v1/portfolio-analyses` and `GET /internal/v1/health`.
- Contract version is the literal string `1`.
- Input contains a fixed `requestId`, `asOf`, quality flags, and position values.
- Positions require symbol, currency, market value; profit/loss may be unknown.
- No FX conversion: weights, concentration, and totals are calculated per currency.
- Position weight is `marketValue / currency marketValue`.
- Currency concentration is the largest position weight in that currency.
- Currency profit/loss is `null` when any constituent profit/loss is unknown.
- Quality returns `stale`, `partial`, and explicit `unknownFields` unchanged.
- Result status is `DEGRADED` when any quality issue exists; otherwise `COMPLETED`.
- Zero-value currency groups return zero weights and concentration.
- Decimal ratios are rounded to at most 10 fractional digits.
- Python has no DB, BrokerAdapter, order, event-bus, or Spring dependency.
- Spring adds DTO/JSON contract tests only; no analysis client or persistence.
- Canonical request/response fixtures live under repo-root `contracts/analysis/v1`.
