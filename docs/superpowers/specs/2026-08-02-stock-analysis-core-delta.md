# Delta Spec: Stock Analysis Core

Date: 2026-08-02
Branch: `feature/stock-analysis-core`

## Scope

Generate deterministic fundamental, valuation, technical, and market-regime
analysis from the normalized stock input snapshot. Preserve raw observations,
provider provenance, `asOf`, `collectedAt`, and `missingData`. Forecast, Gemini
explain, and order integration are out of scope.

## Contract

Keep `/internal/v2/stock-analysis-inputs` unchanged. Add
`POST /internal/v3/stock-analyses` with the existing normalized request and a
response containing the echoed observations plus ordered `analyzers`:
`fundamental`, `valuation`, `technical`, `marketRegime`.

Each metric contains `value`, `unit`, `asOf`, `provenance`, and `missingData`.
`provenance` contains provider, source field, source `asOf`, and
`collectedAt`. A missing or ambiguous input produces a null metric and an
explicit reason; provider values are never averaged or synthesized.

## Deterministic rules

- Ratios use `Decimal`, round half-even to 10 fractional places, and serialize
  without insignificant trailing zeroes.
- Confidence is complete metrics divided by the fixed analyzer metric count,
  rounded with the same rule.
- Duplicate semantic fields are `AMBIGUOUS_DUPLICATE_FIELD`; no provider
  priority is applied.
- A provider/input failure degrades only dependent metrics; usable metrics and
  analyzers continue.
- Regime is `RISK_ON` when VIX <= 20 and S&P 500 20-day return >= 0,
  `RISK_OFF` when VIX >= 30 and return < 0, otherwise `NEUTRAL`.

## Backend API

The existing `POST /api/v1/stock-analyses/{symbol}` persists the normalized
snapshot and core response. `GET /api/v1/stock-analyses/{symbol}` returns the
latest successful result for the authenticated owner; no result returns 404.

## Acceptance

- Same input snapshot produces byte-equivalent JSON across repeated calls.
- Complete inputs produce all four analyzers with provenance and basis time.
- Missing and duplicate fields degrade only dependent metrics and confidence.
- Generation and lookup persist and return the core response.
- Forecast, Gemini explain, and order paths are absent.

## Verification

Run analysis-service pytest, targeted Docker-backed backend integration tests,
then backend `./mvnw clean verify`, dashboard checks, and local-stack smoke
checks. Perform one review pass before squash merge and push.
