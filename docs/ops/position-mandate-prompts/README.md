# Position Mandate rollout

Status as of 2026-10-27: policy is live in the Investment OS project and all five scheduled-task prompts. Existing schedule times and recurrence are unchanged. The Spring persistence change is in PR #119 and still awaits CI, merge, and deployment.

## Confirmed ticker policy

| Ticker | Horizon | Price-risk role | Review focus |
|---|---|---|---|
| LUNR | STRATEGIC | REVIEW_TRIGGER | Backlog, cash, dilution |
| CSTM | TACTICAL | EXIT_EVALUATION | Price trend, results |
| AVT | TACTICAL | EXIT_EVALUATION | Momentum, relative strength |
| VST | STRATEGIC | REVIEW_TRIGGER | Contracts, EBITDA, FCF |
| GOOGL | STRATEGIC | REVIEW_TRIGGER | Ads, Cloud, AI |
| RDW | STRATEGIC | REVIEW_TRIGGER | Backlog, financing |

TACTICAL means a 20–60 trading-day analysis window. STRATEGIC means 6–18 months. Neither is a forced sale deadline. Other tickers stay `UNKNOWN` unless the user confirms a mandate.

## Live application

- `POSITION_MANDATE_POLICY_v1.md` is uploaded in the project's Sources.
- The project instructions point all MODEs and scheduled analyses to that source. The saved pointer was checked again after refresh and reopening project settings.
- The five Scheduled Tasks—MORNING, PRE-TRADE, OPENING, ORDER MANAGER, and WEEKLY + MONTHLY—include the common policy and task-specific application. Their times and recurrence remain unchanged.
- The local `.md` playbook copies and `schedules/` addenda are an implementation record, not the active source of truth. Original synced project sources remain read-only.

## Spring persistence rollout

PR #119 adds persistent `PositionMandate` storage and `positionMandates` in `get_investment_context`. It must pass CI, merge, and deploy before the user rows can be stored.

The currently connected Toss MCP still has no `upsert_position_mandate` tool, and its `get_investment_context` response has no `positionMandates` field. Therefore these six rows are **not yet stored**. After deployment, use the authenticated write tool with effective date `2026-10-27`, then read the context back to verify all six:

```json
{"ticker":"LUNR","investmentHorizon":"STRATEGIC","effectiveDate":"2026-10-27"}
{"ticker":"CSTM","investmentHorizon":"TACTICAL","effectiveDate":"2026-10-27"}
{"ticker":"AVT","investmentHorizon":"TACTICAL","effectiveDate":"2026-10-27"}
{"ticker":"VST","investmentHorizon":"STRATEGIC","effectiveDate":"2026-10-27"}
{"ticker":"GOOGL","investmentHorizon":"STRATEGIC","effectiveDate":"2026-10-27"}
{"ticker":"RDW","investmentHorizon":"STRATEGIC","effectiveDate":"2026-10-27"}
```

Scheduled Tasks keep their existing `connector:read` scope. Do not write mandates or decisions from them, and do not expand their scope as part of this rollout. Use the normal authenticated caller with existing `connector:trade` scope when the tool becomes available.

## Safety boundary

Price triggers, including `CRITICAL`, set monitoring priority and review requirements. They do not by themselves force a sell or prepare/submit an order. Hard Risk Gates, liquidity emergencies, confirmed structural Thesis damage, and portfolio limits still require prompt evaluation at every horizon. No orders were submitted as part of this rollout.
