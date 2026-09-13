# Investment OS connector

Read-only Toss account access for a Custom GPT Action.

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

## Read surface

- `GET /api/v1/connector/portfolio` — request-time Toss sync, then persisted snapshot.
- `GET /api/v1/connector/orders?group=OPEN|CLOSED` — broker order list.
- `GET /api/v1/connector/fills?since=<ISO-8601>` — filled quantities from both groups.

`cashBuyingPower` is the account's cash/order-available balance in this
integration. No separate cash-balance field or second cash ledger is exposed.

## Freshness and safety

- `stale=true` or `partial=true`: do not size or submit; request a resync/review.
- `unknownFields` and `missingSections` are authoritative uncertainty signals.
- Connector keys can only authenticate `GET /api/v1/connector/**` and carry
  `SCOPE_CONNECTOR_READ`; they cannot call order mutation APIs.
- Revoke with `DELETE /api/v1/connector-api-keys/{id}` using recent human
  authentication. Rotate before expiry.
