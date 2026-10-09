# Investment MCP decision append delta

## Goal

Let a `connector:trade` caller save a complete, externally supplied decision record through MCP. The new tool uses the existing decision ledger and gives the caller the same user-scoped record that Context and the research Sheet mirror read.

## Contract

Add `append_investment_decision` beside `put_investment_thesis`. Its JSON object is strict (`additionalProperties: false`) and maps one-to-one to the existing `InvestmentContextService.DecisionInput`:

- `decisionId`, `asOf`, `asset`, `action`, `referencePrice`, `priceSession`
- `horizon`, `alphaThesis`, `invalidation`, `nextReviewTrigger`, `confidence`

The action and session enums, numeric ranges, ticker, timestamp, and required text rules match `recordDecision`. Backend-generated `riskPolicyCheck` and `createdAt` are output only. No field is defaulted from zero, null, another Sheet row, current prices, or an inferred thesis.

## Authorization and writes

The tool requires the existing `connector:trade` scope and remains available when live order execution is disabled. It calls `recordDecision` for the authenticated key's user. Same UUID plus same normalized content is an idempotent replay; a mismatched or foreign-owned UUID returns `DECISION_CONFLICT`. Unsupported or invalid input returns `INVALID_ARGUMENT`; invalid authenticated user and unavailable persistence retain separate safe errors.

`get_investment_context` and the `Decision Ledger` Sheet remain projections of the existing DB row. The tool does not import or mutate Sheet cells, create an AI judgment, confirm a thesis, prepare an order, or submit an order. No migration, permission, dependency, or alternate decision store is introduced.

The seven existing legacy Sheet rows are not auto-imported: `REVIEW` is not a supported action, their identifiers are not UUIDs, their price-session labels are unsupported, and six rows have no reference price. The tool rejects unsupported or absent fields; it does not convert or invent them. Its append endpoint does not write to or migrate those Sheet rows.

## Validation

Protocol tests cover discovery/schema, trade-scope denial, disabled-order availability, exact field mapping, strict arguments/enums, and error separation. PostgreSQL integration verifies append, idempotent replay, and exact parity between the saved row and the subsequent Context decision-ledger entry. Existing Sheet tests continue to cover the DB-backed decision projection.
