# Position Mandate prompt drafts

> DRAFT ONLY. These files are reviewable local copies/addenda. They do not change the synced ChatGPT project or any live Scheduled Task.

## Confirmed policy

| Ticker | Horizon | Price-risk role | Review focus |
|---|---|---|---|
| LUNR | STRATEGIC | REVIEW_TRIGGER | Backlog, cash, dilution |
| CSTM | TACTICAL | EXIT_EVALUATION | Price trend, results |
| AVT | TACTICAL | EXIT_EVALUATION | Momentum, relative strength |
| VST | STRATEGIC | REVIEW_TRIGGER | Contracts, EBITDA, FCF |
| GOOGL | STRATEGIC | REVIEW_TRIGGER | Ads, Cloud, AI |
| RDW | STRATEGIC | REVIEW_TRIGGER | Backlog, financing |

TACTICAL means a 20–60 trading-day analysis window. STRATEGIC means 6–18 months. Neither is a forced sale deadline. Unknown tickers stay `UNKNOWN`.

## Playbook copies

The five `.md` playbooks were copied from the read-only synced source directory:
`/Users/jjm/.codex/.chatgpt-projects/g-p-6a898b481fe88191b20e9b9662c7eacd/sources/`.
Only the copies in this folder were changed.

- `00_PORTFOLIO_MANAGER.md`: common policy inherited by MODEs 1–12.
- `03_LONG_TERM.md`, `04_POSITION.md`, `05_SELL.md`, `09_PORTFOLIO_REVIEW.md`: mode-specific horizon logic.

## Schedule addenda

The active version pages were fetched read-only from Notion on 2026-10-27. Their recorded schedules remain:

| Schedule | Recorded active version | Schedule retained |
|---|---|---|
| MORNING | v1.4.2 | Weekdays 08:00 Asia/Seoul |
| PRE-TRADE | v1.0 KST Sleep-Safe | Weekdays 20:30 Asia/Seoul |
| OPENING | v1.4.2 | Weekdays 09:50 America/New_York |
| ORDER MANAGER | v1.1 Closing :50 | US market days 10:50–15:50 America/New_York, hourly at :50 |
| WEEKLY + MONTHLY | v1.5 | Saturday 10:00 Asia/Seoul, flexible; monthly review on first Saturday |

The matching `schedules/` files contain policy blocks to append during manual prompt revision. They preserve each schedule's existing task logic and time. They are not the complete task prompts.

## One-time data rollout

After V65 is deployed and the MCP tool is available with the authenticated user's `connector:trade` scope, persist these six user-confirmed mandates once with `upsert_position_mandate`, using effective date `2026-10-27`:

```json
{"ticker":"LUNR","investmentHorizon":"STRATEGIC","effectiveDate":"2026-10-27"}
{"ticker":"CSTM","investmentHorizon":"TACTICAL","effectiveDate":"2026-10-27"}
{"ticker":"AVT","investmentHorizon":"TACTICAL","effectiveDate":"2026-10-27"}
{"ticker":"VST","investmentHorizon":"STRATEGIC","effectiveDate":"2026-10-27"}
{"ticker":"GOOGL","investmentHorizon":"STRATEGIC","effectiveDate":"2026-10-27"}
{"ticker":"RDW","investmentHorizon":"STRATEGIC","effectiveDate":"2026-10-27"}
```

The current connected Toss MCP does not expose that new write tool yet, so no live rows were written. Until those rows exist, the prompts use the six confirmed mappings above as fallback; other tickers remain `UNKNOWN`.

The connector runbook currently keeps Scheduled Tasks on `connector:read`; do not call mandate or decision write tools from those tasks or expand their scope as part of this change. Schedule reports must still show the four decision fields. Persisting a decision to the ledger requires a separate caller with the existing `connector:trade` scope.

## Safety boundary

Price triggers, including CRITICAL events, set monitoring priority and review requirements. They do not by themselves force a sell or prepare/submit an order. Hard Risk Gates, liquidity emergencies, and confirmed structural thesis damage still require prompt evaluation regardless of horizon. Existing order sizing, broker evidence, and approval requirements remain in force.
