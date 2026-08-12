# Live market data surfaces completion delta

## Audited surface state

The wire envelope keeps its existing `AVAILABLE` / `DEGRADED` / `UNAVAILABLE`
status values. The product state `SUCCESS` maps to `AVAILABLE`, and
`PROVIDER_UNSUPPORTED` maps to `UNAVAILABLE` with an explicit unsupported
reason. Upstream failures remain `UNAVAILABLE` with their actual failure reason.

| Current UI surface | Direct source | Product state when usable | Unsupported / degraded boundary |
|---|---|---|---|
| Home quote ticker | Toss `GET /api/v1/prices` | SUCCESS | Missing bid/ask or symbol-level failure stays DEGRADED; no candle fallback |
| Home USD/KRW | Toss `GET /api/v1/exchange-rate` | SUCCESS | Provider-expired validity is DEGRADED; no inferred FX |
| Home market calendar | Toss `GET /api/v1/market-calendar/{KR,US}` | SUCCESS | Provider payload is preserved; missing payload fields stay DEGRADED |
| Home volume/amount/gainers/losers | Toss `GET /api/v1/rankings` | SUCCESS | Empty or incomplete direct items stay DEGRADED |
| Home market-cap ranking | None approved | PROVIDER_UNSUPPORTED | No FMP/FRED or derived ranking |
| Stock quote summary | Toss `GET /api/v1/prices` | SUCCESS | No current-price synthesis from candle closes |
| Stock orderbook | Toss `GET /api/v1/orderbook` | SUCCESS | Missing side/level fields stay DEGRADED |
| Stock candles | Toss `GET /api/v1/candles` | SUCCESS | Missing OHLCV fields stay DEGRADED; only `1m`/`1d` |
| Stock warnings/investor trading/commissions | None approved | PROVIDER_UNSUPPORTED | Existing UI remains unchanged |

FMP and FRED remain analysis providers. They do not expose a compatible direct
source for the unsupported surfaces, so no fallback, average, scrape, or
estimate is added. Provider provenance uses only response metadata and leaves
`asOf`/`currency` unknown when the source does not provide them. Staleness is
reported only from provider-declared validity; no arbitrary age threshold is
introduced for market data.

## Verification contract

- Adapter contract tests assert Toss response, decimal, currency, timestamp,
  validity, partial-field, malformed, timeout, and rate-limit mapping.
- Surface service tests assert direct provenance, degraded missing fields,
  provider-unsupported reasons, provider failure reasons, and stale validity.
- Dashboard tests assert direct quote use, visible missing fields, and the
  distinction between unsupported and upstream failure messages.
