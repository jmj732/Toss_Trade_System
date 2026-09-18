# Investment OS connector

Toss account access for a Custom GPT Action or ChatGPT MCP app. The default
connector scope is read-only; trade tools require an explicit
`connector:trade` scope.

## Issue a key

Use the human-authenticated session. The raw key is returned once; store it in
the GPT Action configuration, not in source control.

```bash
curl -X POST "$BASE_URL/api/v1/connector-api-keys" \
  -H "Authorization: Bearer $HUMAN_SESSION" \
  -H 'Content-Type: application/json' \
  -d '{"connectionId":"<broker-connection-id>"}'
```

Configure `docs/connector-openapi.yaml` in the Custom GPT Action and set the
Authorization API-key value to `Bearer ckey_...`.

Smoke test before importing the Action:

```bash
curl -fsS "$BASE_URL/api/v1/connector/portfolio" \
  -H "Authorization: Bearer $CONNECTOR_KEY"
```

Required user-owned inputs: public HTTPS backend URL, broker `connectionId`,
and the one-time connector key. Do not put the key in chat, Git, or logs.

## ChatGPT MCP app

The MCP app exposes the connector surface through the Streamable HTTP
transport used by current ChatGPT custom-app connections:

```text
https://web-dashboard-phi-lac.vercel.app/api/v1/connector/mcp
```

In the app setup dialog, select `OAuth` (or `혼합` if the UI presents both
choices). The server publishes MCP protected-resource and authorization-server
metadata, then redirects the browser through the existing OIDC login. OAuth
authorization requires exactly one active Toss connection for the signed-in
user. No connector key needs to be copied into ChatGPT.

The requested OAuth scope determines the MCP tools:

- `connector:read`: `get_portfolio`, `get_orders`, and `get_recent_fills`.
- `connector:trade`: the three read tools plus `prepare_order`,
  `submit_order`, `cancel_order`, and `get_order`.

If `REAL_ORDER_ENABLED=false`, trade-scoped connections still show the four
trade tools so the capability is discoverable, but calls return an explicit
disabled error and no broker order is sent. Reconnect the MCP app after
changing its requested scope so the client receives a new token and rescans
the tool list.

After tool scanning, test with prompts such as:

- `내 Toss 포트폴리오를 조회해줘.`
- `열린 주문을 보여줘.`
- `2026-09-01T00:00:00Z 이후 체결 내역을 보여줘.`

## Read surface

- Dashboard reads the same contract at
  `GET /api/v1/broker-connections/{connectionId}/portfolio/state` with the
  human-authenticated session.
- `GET /api/v1/connector/portfolio` — request-time Toss sync, then persisted snapshot.
- `GET /api/v1/connector/portfolio/state` — the same sync plus calculation-ready account,
  positions, risk percentages, and open orders.
- `GET /api/v1/connector/orders?group=OPEN|CLOSED` — broker order list.
- `GET /api/v1/connector/fills?since=<ISO-8601>` — filled quantities from both groups.

`cashBuyingPower` is the account's cash/order-available balance in this
integration. No separate cash-balance field or second cash ledger is exposed.

## Freshness and safety

- `stale=true` or `partial=true`: do not size or submit; request a resync/review.
- `unknownFields` and `missingSections` are authoritative uncertainty signals.
- `currency` is the calculation currency (USD first, then KRW); values are not silently FX-converted.
- Connector keys can authenticate the REST surface and MCP messages under
  `/api/v1/connector/**`. Every key carries `SCOPE_CONNECTOR_READ`; only a key
  with `connector:trade` also carries `SCOPE_CONNECTOR_TRADE` and can call
  trade tools.
- Keys do not expire by default. Revoke with
  `DELETE /api/v1/connector-api-keys/{id}` using recent human authentication;
  rotate/revoke if the key may have leaked.

## Canonical `PortfolioState` contract

`GET /api/v1/connector/portfolio/state` and the human dashboard route return the
same state. Consumers must not call Toss directly or recompute money/risk fields.

- `asOf`: state snapshot completion time. Kept for compatibility with existing
  consumers; it is not a Toss quote timestamp.
- `sourceAsOf`: earliest non-null `observedAt` among account, position, and
  buying-power data. `null` means no broker value is known.
- `syncedAt`: sync-run completion time. Fallback snapshots retain their original
  timestamps; a failed first sync leaves them `null`.
- `null` means unknown. An empty list means the source explicitly reported no
  items. Missing values are never replaced with zero.
- `stale=true` means the values may be old or the live sync failed. `partial=true`
  means one or more sections are unavailable. `unknownFields` and
  `missingSections` are authoritative field paths, not display hints.
- Percentage fields (`weightPct`, `cashPct`, `investedPct`, `unrealizedPnlPct`)
  are percentage points (`16.5` = 16.5%), not ratios. Amounts remain in their
  native `currency`; no silent FX conversion occurs.
- `account.profitLossRate` and `account.dailyProfitLossRate` retain Toss's decimal
  ratio (`0.05` = 5%); position `unrealizedPnlPct` is already percentage points.
- `account.totalValue`, cash, and risk percentages are calculated only for the
  selected primary currency. A multi-currency account keeps non-primary weights
  unknown until an explicit FX policy exists.
