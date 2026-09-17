# Investment OS connector

Read-only Toss account access for a Custom GPT Action or ChatGPT MCP app.

## Issue a key

Use the human-authenticated session. The raw key is returned once; store it in
the GPT Action configuration, not in source control.

```bash
curl -X POST "$BASE_URL/api/v1/connector-api-keys" \
  -H "Authorization: Bearer $HUMAN_SESSION" \
  -H 'Content-Type: application/json' \
  -d '{"connectionId":"<broker-connection-id>","expiresAt":"2026-12-31T23:59:59Z"}'
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

The MCP app exposes the same read-only surface through the SSE transport used
by the ChatGPT custom-app setup screen:

```text
https://web-dashboard-phi-lac.vercel.app/api/v1/connector/mcp/sse
```

In the app setup dialog, select the API-key/Bearer authentication option if it
is available, and use the one-time connector key as `Bearer ckey_...`. Do not
select OAuth for this server: the existing connector key is bound to one broker
connection and the MCP endpoint intentionally does not implement an OAuth
authorization server.

After tool scanning, test with prompts such as:

- `내 Toss 포트폴리오를 조회해줘.`
- `열린 주문을 보여줘.`
- `2026-09-01T00:00:00Z 이후 체결 내역을 보여줘.`

Only `get_portfolio`, `get_orders`, and `get_recent_fills` are exposed. No order
creation, cancellation, or other write tool is available.

## Read surface

- `GET /api/v1/connector/portfolio` — request-time Toss sync, then persisted snapshot.
- `GET /api/v1/connector/orders?group=OPEN|CLOSED` — broker order list.
- `GET /api/v1/connector/fills?since=<ISO-8601>` — filled quantities from both groups.

`cashBuyingPower` is the account's cash/order-available balance in this
integration. No separate cash-balance field or second cash ledger is exposed.

## Freshness and safety

- `stale=true` or `partial=true`: do not size or submit; request a resync/review.
- `unknownFields` and `missingSections` are authoritative uncertainty signals.
- Connector keys can authenticate the read-only REST surface and MCP SSE
  messages under `/api/v1/connector/**` and carry
  `SCOPE_CONNECTOR_READ`; they cannot call order mutation APIs.
- Revoke with `DELETE /api/v1/connector-api-keys/{id}` using recent human
  authentication. Rotate before expiry.
