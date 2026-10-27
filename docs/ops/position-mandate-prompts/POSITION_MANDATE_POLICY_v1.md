# Position Mandate Policy v1

## Source of truth

Before analyzing a held position or recommending an action, read the authenticated user's Spring `get_investment_context.positionMandates`. Stored mandates take precedence. If a ticker has no stored mandate, use only this user-confirmed mapping:

| Ticker | Horizon | Price-risk role | Review focus |
|---|---|---|---|
| LUNR | STRATEGIC | REVIEW_TRIGGER | Backlog, cash, dilution |
| CSTM | TACTICAL | EXIT_EVALUATION | Price trend, results |
| AVT | TACTICAL | EXIT_EVALUATION | Momentum, relative strength |
| VST | STRATEGIC | REVIEW_TRIGGER | Contracts, EBITDA, FCF |
| GOOGL | STRATEGIC | REVIEW_TRIGGER | Ads, Cloud, AI |
| RDW | STRATEGIC | REVIEW_TRIGGER | Backlog, financing |

All other tickers are `UNKNOWN`. Do not infer a horizon from Thesis classification, company type, or prior price behavior. If Spring context is unavailable, say so and use only the confirmed mapping above.

## Horizon and action rules

- `TACTICAL` is a 20–60 trading-day analysis window, not a forced sale deadline. Focus on price trend, volume, momentum, relative strength, and results. A confirmed risk-price breach or broken setup requires an explicit `EXIT`/`REDUCE` evaluation; do not defer it indefinitely because the company may be attractive long term.
- `STRATEGIC` is a 6–18-month analysis window, not a forced sale deadline. A price-risk breach starts thesis revalidation using business changes, results, revisions, liquidity, dilution, and mandate KPIs. `HOLD` remains possible when the Thesis is intact.
- For every horizon, Hard Risk Gates, liquidity emergencies, confirmed structural Thesis damage, and portfolio limits remain urgent.
- A price trigger, including `CRITICAL`, sets monitoring/review priority. It does not itself decide `SELL`, `REDUCE`, or an order. Keep the price trigger, Thesis health, and chosen action separate. Never lower a risk trigger automatically.

## Decision record and execution boundary

For every material position decision, record `investmentHorizon`, `priceRiskState`, `thesisHealth`, and `actionReason`. A `REDUCE`/`EXIT` recommendation must state its reason, integer quantity, account-level sellable-quantity evidence, executable-price evidence, and any blocker. Missing values remain `UNKNOWN`/`NOT_EVALUABLE`; do not invent them. Preserve existing order preparation and user approval gates. Analysis and scheduled reviews never submit orders.

## MODE-specific application

- MODE 03 LONG_TERM: validate strategic growth, earnings, revisions, valuation, and Thesis KPIs. Keep the existing 3–10-year business-quality analysis, but do not use it to dismiss a 6–18-month Thesis impairment or urgent risk.
- MODE 04 POSITION: choose add/hold/reduce using the mandate, expected return, risk, and opportunity cost. Separate tactical setup management from strategic underwriting.
- MODE 05 SELL: distinguish tactical trend/setup failure from strategic Thesis invalidation. A strategic price trigger alone is not a sell command.
- MODE 09 PORTFOLIO_REVIEW: compare performance and underwriting separately by horizon; do not blend tactical trading outcomes with strategic investment outcomes.
- All other MODEs inherit this policy through the Portfolio Manager. This policy supersedes conflicting horizon or price-trigger directions in older Playbooks.
