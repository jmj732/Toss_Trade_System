# Investment Horizon and Position Mandate Policy

## Goal

Persist a user's investment horizon per ticker in Spring, expose it to every
analysis run, and distinguish a price-risk signal from an actual sell decision.

## Confirmed mappings

| Ticker | Horizon | Risk-price role | Core review |
|---|---|---|---|
| LUNR | STRATEGIC | REVIEW_TRIGGER | Backlog, cash, dilution |
| CSTM | TACTICAL | EXIT_EVALUATION | Price trend, results |
| AVT | TACTICAL | EXIT_EVALUATION | Momentum, relative strength |
| VST | STRATEGIC | REVIEW_TRIGGER | Contracts, EBITDA, FCF |
| GOOGL | STRATEGIC | REVIEW_TRIGGER | Ads, Cloud, AI |
| RDW | STRATEGIC | REVIEW_TRIGGER | Backlog, financing |

TACTICAL uses a 20–60 trading-day analysis horizon. STRATEGIC uses 6–18 months.
Neither horizon is a forced sale deadline.

## Persistence and read contract

- `PositionMandate` is user-owned and separate from Thesis `classification`.
- Persist `ticker`, `investmentHorizon`, derived `riskPriceRole`, `source`,
  `effectiveDate`, and server `updatedAt` in PostgreSQL.
- Accepted horizons are `TACTICAL` and `STRATEGIC`; their paired roles are
  respectively `EXIT_EVALUATION` and `REVIEW_TRIGGER`.
- The authenticated caller is the only source of `userId`. The server sets
  `source=USER_CONFIRMED`; callers cannot impersonate another user or set the
  source.
- `get_investment_context` returns the authenticated user's stored mandates.
  Missing rows stay missing; analysis prompts may use the confirmed six-ticker
  mapping as their explicit fallback and must treat every other ticker as
  `UNKNOWN`.
- `priceRiskTriggerPrice` remains a Thesis field and is not changed by a
  PositionMandate write.

## Decision and risk behavior

- Every newly authored decision should carry `investmentHorizon`,
  `priceRiskState`, `thesisHealth`, and `actionReason`.
- New MCP decision fields use `investmentHorizon` (`TACTICAL`, `STRATEGIC`,
  `UNKNOWN`), `priceRiskState` (`UNKNOWN`, `CLEAR`, `NEAR`,
  `INTRADAY_BREACH`, `PRICE_RISK_BREACH`, `RECOVERED`), `thesisHealth`
  (`UNKNOWN`, `INTACT`, `IMPAIRED`, `INVALIDATED`), and a caller-authored
  `actionReason` up to 2,000 characters. They stay optional for legacy callers;
  the updated prompts require all four for new decisions.
- A verified TACTICAL price-risk breach requires an EXIT/REDUCE evaluation;
  it does not submit or prepare an order by itself.
- A STRATEGIC price-risk breach requires thesis revalidation. If the thesis is
  intact, HOLD remains possible. Structural thesis damage or rising capital
  loss/liquidity risk may justify REDUCE or EXIT.
- A risk-price breach alone never forces an automatic reduce. Hard Risk Gates,
  liquidity emergencies, and confirmed structural impairment remain actionable
  regardless of horizon.
- Spring's pure risk resolver emits only `NO_PRICE_ACTION`, `EXIT_EVALUATION`,
  `THESIS_REVALIDATION`, `URGENT_REVIEW`, or `MANDATE_REVIEW`; it never chooses
  a trade action or creates an order.
- Risk prices are never lowered automatically. Sell quantity, reason, order
  readiness, and approval remain explicit. Existing order preparation and
  submission approval boundaries remain in force.

## Required analysis changes

- The common Portfolio Manager policy is inherited by all 12 analysis MODEs.
- MODEs 03 LONG_TERM, 04 POSITION, 05 SELL, and 09 PORTFOLIO_REVIEW add
  horizon-specific analysis.
- The five reservation prompts retain their existing schedules and add the
  same stored-policy, risk-state, thesis-health, and action-reason contract.
- Schedules and synced project source files are not modified by local prompt
  drafts. Draft files must be clearly labeled for manual application.

## Validation

- Verify persistence is user-scoped and context returns the stored role/date.
- Verify a TACTICAL confirmed breach routes to EXIT_EVALUATION.
- Verify a STRATEGIC breach with intact thesis routes to THESIS_REVALIDATION,
  never directly to REDUCE/EXIT.
- Verify Hard Risk Gate response applies independent of horizon.
- Verify MCP decision schema can carry all four decision fields.
