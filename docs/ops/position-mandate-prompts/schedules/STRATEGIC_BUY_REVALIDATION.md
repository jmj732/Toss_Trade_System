# STRATEGIC BUY REVALIDATION — ChatGPT Scheduled Task draft

> DRAFT ONLY — configure as a dedicated ChatGPT Scheduled Task with the Toss Plugin authorized for `connector:review`. That scope cannot prepare or submit orders. Actual custom-MCP Scheduled Task support must be tested in ChatGPT before relying on this prompt.

At each run, read the current `get_investment_context`. Find Strategic positions with a current `REVIEW_REQUIRED` buy gate and a recent `ADD` decision. Skip TACTICAL and `BLOCKED` decisions. Do not create or change investment decisions.

For each candidate, call `start_strategic_buy_revalidation` with its existing `decisionId`. Use only the returned `runId`, `challenge`, thesis revision, mandate version, price and market state. If the run expires or any current state changes, stop and start a fresh review.

Use ChatGPT web research to gather current, primary-source evidence for the business/thesis, latest fundamentals, revisions, expectations, and risk. Compare evidence dates and the source contents with the claim. Never invent a source or treat a URL alone as validation. If browsing or source grounding is unavailable, record `NOT_EVALUABLE` where possible and do not produce a passing review.

Assess all six criteria:

- `thesisHealth`: `INTACT`, `IMPAIRED`, `INVALIDATED`, or `NOT_EVALUABLE`
- `fundamentalRevision`: `SUPPORTED`, `CONTRADICTED`, or `NOT_EVALUABLE`
- `expectationsGap`: `OPPORTUNITY`, `NO_OPPORTUNITY`, or `NOT_EVALUABLE`
- `riskReward`: `FAVORABLE`, `UNFAVORABLE`, or `NOT_EVALUABLE`
- `hardRisk`: `PASS`, `FAIL`, or `NOT_EVALUABLE`
- `thesisInvalidation`: `CLEAR`, `TRIGGERED`, or `NOT_EVALUABLE`

Use the existing account and risk context to set a defensible `maxQuantity`; set a narrow `minPrice`/`maxPrice` range only when it includes the reviewed price and both bounds are supported by the analysis. If risk, evidence, or quantity cannot be established, do not assert a passing criterion. Then call `finish_strategic_buy_revalidation` with the same run ID and challenge, all five source types (`THESIS`, `FUNDAMENTAL`, `REVISION`, `EXPECTATIONS`, `RISK`), source URLs and as-of times, complete rationale, all six assessments, and justified bounds.

This task records a bounded ChatGPT assessment only. Do not call order tools, claim that the server verified Scheduled Task identity, or claim that a source was verified unless you opened and checked it. A passing review still requires a separate, explicit approval of an individual prepared order and all live order gates.
