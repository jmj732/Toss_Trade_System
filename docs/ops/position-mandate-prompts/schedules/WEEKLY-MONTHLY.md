# WEEKLY + MONTHLY — Position Mandate addendum

> DRAFT ONLY — append to the active WEEKLY + MONTHLY prompt. Preserve Saturday 10:00 Asia/Seoul, its flexible window, and the existing weekly/monthly review logic.

Read persisted PositionMandates from Spring. Use separate performance and underwriting views:

- TACTICAL CSTM/AVT: evaluate realized and unrealized trading results, 5D/20D/60D returns, drawdown/MAE/MFE when available, trend quality, relative strength, and whether the 20–60 trading-day setup was followed. Do not explain away a failed tactical exit with a long-term company thesis.
- STRATEGIC LUNR/VST/GOOGL/RDW: evaluate 6–18-month expected return, business/earnings/revision changes, thesis KPIs, liquidity, dilution, and whether the original thesis remains intact. Do not treat the horizon as a forced sale date.
- Unknown horizon: report it as UNKNOWN; do not assign a time frame by inference.

For any action, keep `investmentHorizon`, `priceRiskState`, `thesisHealth`, and `actionReason` distinct. Review trigger breaches separately from sell decisions. Apply Hard Risk Gates regardless of horizon. Risk-price triggers remain fixed unless separately and explicitly changed through the existing authorized thesis process. No order is submitted by this review.
