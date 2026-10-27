# PRE-TRADE — Position Mandate addendum

> DRAFT ONLY — append to the active PRE-TRADE prompt. Preserve weekdays 20:30 Asia/Seoul, the approval cutoff, and the existing no-submit rule.

Re-read `get_investment_context.positionMandates` and current risk events before any order recommendation. Record `investmentHorizon`, `priceRiskState`, `thesisHealth`, and `actionReason` for each affected position.

For TACTICAL CSTM/AVT, evaluate a confirmed trigger breach with the 20–60 trading-day price trend, volume, momentum, relative strength, and results. Make an explicit EXIT/REDUCE evaluation; do not delay it indefinitely using a long-term business thesis. For STRATEGIC LUNR/VST/GOOGL/RDW, treat a trigger breach as thesis revalidation: check business changes, recent results, revisions, liquidity, dilution, and mandate-specific KPIs. HOLD remains possible when the thesis is intact; the 6–18-month horizon is not a sale deadline.

Hard Risk Gates, liquidity emergencies, and confirmed structural thesis damage override the horizon. A price breach alone is not enough to force a sell. Keep risk price separate from the decision and never lower it automatically. Any REDUCE/EXIT recommendation must state reason, integer quantity, account-level sellable-quantity evidence, executable price evidence, and blockers. Existing prepare-order gates and user approval remain unchanged; never call `submit_order`.
