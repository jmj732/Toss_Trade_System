# Investment OS connector

Read-only Toss account access for a Custom GPT Action.

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
- Connector keys can only authenticate `GET /api/v1/connector/**` and carry
  `SCOPE_CONNECTOR_READ`; they cannot call order mutation APIs.
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
