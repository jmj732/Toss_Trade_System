# STRATEGIC BUY REVALIDATION — ChatGPT Scheduled Task draft

> DRAFT ONLY — first test this prompt in a regular ChatGPT conversation using a separate Toss connection authorized only for `connector:review`. Do not change a Scheduled Task until that trial completes and its database record is checked. That scope cannot prepare or submit orders. Actual custom-MCP Scheduled Task support must be tested in ChatGPT before relying on this prompt.

At each run, read the current `get_investment_context`. Find Strategic positions with a current `REVIEW_REQUIRED` buy gate and a recent `ADD` decision. Skip TACTICAL and `BLOCKED` decisions. Do not create or change investment decisions.

For each candidate, gather current, primary-source evidence for the business/thesis, latest fundamentals, revisions, expectations, and risk **before** starting the server run. Compare evidence dates and source contents with each claim. Never invent a source or treat a URL alone as validation. If required evidence is unavailable, do not claim a passing review.

Only after this research, call `start_strategic_buy_revalidation` with the existing `decisionId`. Compare its returned ticker, decision, Thesis revision, price and observation time, market health, and price-risk state with the context and research you just collected. If the decision or Thesis revision differs, the market snapshot changed materially, or the evidence no longer matches the current state, do not finish that run; gather current evidence and start a new one. The run expires after 15 minutes, so finish promptly.

Use only the returned `runId`, `challenge`, Thesis revision, price, and market state in the completion. Do not infer an unreturned mandate version. If browsing or source grounding is unavailable, record `NOT_EVALUABLE` where possible and do not produce a passing review.

Assess all six criteria:

- `thesisHealth`: `INTACT`, `IMPAIRED`, `INVALIDATED`, or `NOT_EVALUABLE`
- `fundamentalRevision`: `SUPPORTED`, `CONTRADICTED`, or `NOT_EVALUABLE`
- `expectationsGap`: `OPPORTUNITY`, `NO_OPPORTUNITY`, or `NOT_EVALUABLE`
- `riskReward`: `FAVORABLE`, `UNFAVORABLE`, or `NOT_EVALUABLE`
- `hardRisk`: `PASS`, `FAIL`, or `NOT_EVALUABLE`
- `thesisInvalidation`: `CLEAR`, `TRIGGERED`, or `NOT_EVALUABLE`

Use the existing account and risk context to set a defensible `maxQuantity`; set a narrow `minPrice`/`maxPrice` range only when it includes the reviewed price and both bounds are supported by the analysis. If risk, evidence, or quantity cannot be established, do not assert a passing criterion. Then call `finish_strategic_buy_revalidation` with the same run ID and challenge, all five source types (`THESIS`, `FUNDAMENTAL`, `REVISION`, `EXPECTATIONS`, `RISK`), source URLs and as-of times, complete rationale, all six assessments, and justified bounds.

This flow records a bounded ChatGPT assessment only. Do not call order tools, claim that the server verified Scheduled Task identity, or claim that a source was verified unless you opened and checked it. After the regular-chat trial is verified in the database, integrate this prompt into one existing schedule (prefer OPENING, which rechecks market state) with only the review-scoped Toss app enabled. Do not create a sixth schedule. Keep the other four schedules unchanged. Verify an actual scheduled run before relying on it. A passing review still requires a separate, explicit approval of an individual prepared order and all live order gates.
