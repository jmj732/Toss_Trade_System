# Canonical investment data

## Sources and status

- Toss supplies the latest quote and verified regular-close prices. A quote with no verified session classification remains visible with its timestamp and missing-session reason; it is not stored as a regular close.
- SEC CompanyFacts supplies filed financial facts and basic shares. Alpha Vantage supplies daily annual and quarterly analyst estimates; it supplies diluted shares only when SEC has no valid diluted-share fact.
- Financial Modeling Prep is disabled by default in the credentialed staging overlay. It may be enabled as an optional provider, but its failure does not replace or invalidate Toss and SEC inputs.
- A capture is `SUCCEEDED` when the required current Toss and filed SEC inputs are available. Missing optional provider fields produce `PARTIAL`; missing required canonical inputs produce `FAILED`. A partial run retains its last-success time.

## Valuation and provenance

Regular market capitalization is Toss regular close multiplied by verified SEC basic shares. Fully diluted market capitalization is a separate value using the available diluted-share fact and records its share source. Enterprise value is market capitalization plus the latest filed debt minus the latest filed cash. The price, shares, cash, and debt keep independent source dates; a negative enterprise value is valid arithmetic.

Fundamental context and the Security Snapshot sheet expose the source, as-of date, unit, period, identifier, and calculation formula for each available value. Calculated EBITDA is labeled with its calculation type, formula, and source facts. Consensus history keeps annual and quarterly estimate types distinct, along with period end, source label, currency, and analyst counts.

## Refreshes and quota

The existing weekday after-close capture continues on its configured cron, including exchange holidays. A separate weekend full capture runs Saturday and Sunday at 16:15 New York time, providing a daily capture on every calendar day without changing the established weekday override. Both cron values remain configurable. Alpha Vantage logical responses are cached per UTC date, symbol, and function across configured credentials, preserving their original observation time. The durable daily request limit is 25 per credential; legacy observations seed only the primary key's usage. The existing `ALPHA_VANTAGE_API_KEY` remains the primary key, and optional comma-separated fallback keys can be supplied through `ALPHA_VANTAGE_ADDITIONAL_API_KEYS`. The provider rotates after a key's local 25-request budget is exhausted, or on an explicit provider daily-quota or invalid-key response; transient and burst-rate-limit failures stop the request. Failed and in-progress requests remain visible through safe reason codes without exposing provider messages or credentials.

Consensus revisions compare only the same provider, estimate type, and period horizon. Currency verification controls currency-sensitive forward valuation, not historical revision arithmetic. A current estimate with no baseline reports `INSUFFICIENT_HISTORY`; a missing current estimate reports `DATA_MISSING`.

The configured additional data targets are `AVT`, `CSTM`, `GOOGL`, `LUNR`, `RDW`, and `VST`. These targets make research data available in Context and Sheets. A target is not a portfolio position; position quantity and weight remain absent unless confirmed by the broker account.
