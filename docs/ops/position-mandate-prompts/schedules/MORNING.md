# MORNING — Position Mandate addendum

> DRAFT ONLY — append to the active MORNING prompt. Preserve weekdays 08:00 Asia/Seoul and all existing checks.

At the start of each run, call `get_investment_context` and read `positionMandates`. Use the authenticated user's stored horizon and risk-price role for each held ticker. Until all six user-confirmed mandates have been persisted, use the confirmed mapping in the common Portfolio Manager; leave other tickers `UNKNOWN`.

For each position record `investmentHorizon`, `priceRiskState`, `thesisHealth`, and `actionReason`. Apply TACTICAL (20–60 trading days) price/trend/relative-strength review to CSTM and AVT. Apply STRATEGIC (6–18 months) business, results, revisions, liquidity, and thesis review to LUNR, VST, GOOGL, and RDW. The horizon is not a forced sale deadline.

A price-risk trigger is a review signal, not a sell instruction. Keep the existing CRITICAL monitoring priority, but distinguish it from action selection. Hard Risk Gate violations and liquidity emergencies remain urgent across all horizons. Never lower a risk trigger automatically. Keep all existing position sizing, account reconciliation, order evidence, and user approval requirements. Do not submit orders.
