# Live market data surfaces delta

기준 문서: Toss OpenAPI latest (`info.version=1.2.13`, 2026-08-11 확인)
및 [공식 Market Data 문서](https://developers.tossinvest.com/docs/market-data).

## Decision deltas

## Provider connection matrix

| Surface | Current state | Connected source | Contract result |
|---|---|---|---|
| Home realtime quote | DEGRADED | Toss `GET /api/v1/prices` | `lastPrice`, `timestamp`, `currency`; bid/ask explicitly missing |
| Home USD/KRW | PROVIDER_UNSUPPORTED | Toss `GET /api/v1/exchange-rate` | rate, midRate, basisPoint, validity window, currencies |
| Home market calendar | PROVIDER_UNSUPPORTED | Toss `GET /api/v1/market-calendar/{KR,US}` | provider calendar payload preserved; no local holiday inference |
| Home volume/amount/gainers/losers | PROVIDER_UNSUPPORTED | Toss `GET /api/v1/rankings` | direct ranked items only |
| Home market-cap ranking | PROVIDER_UNSUPPORTED | none approved | remains unavailable; no derived rank |
| Stock orderbook | PROVIDER_UNSUPPORTED | Toss `GET /api/v1/orderbook` | asks/bids and `volume`; partial sides remain DEGRADED |
| Stock candles | PROVIDER_UNSUPPORTED | Toss `GET /api/v1/candles` | only `1m`/`1d`, OHLCV, timestamp, currency |
| Stock warnings | PROVIDER_UNSUPPORTED | none connected | unchanged |
| Stock investor trading | PROVIDER_UNSUPPORTED | none connected | unchanged |
| Stock commissions | PROVIDER_UNSUPPORTED | none connected | unchanged |

- Replace the broker surface `PROVIDER_UNSUPPORTED` routes for orderbook, candles,
  exchange rate, market calendar, and supported rankings with read-only Toss Open
  API calls.
- Keep the existing response envelope and add endpoint-level provider provenance
  with the provider, endpoint, currency, as-of, and observed-at values.
  Provider values are never averaged or synthesized.
- Map Toss decimal strings to `BigDecimal`, ISO-8601 offsets to `Instant`, and
  `currency` to the existing `Currency` contract. Preserve partial orderbook
  data as `DEGRADED` with explicit missing fields.
- Expose only Toss candle intervals (`1m`, `1d`). Existing weekly/monthly UI
  choices are removed because Toss does not provide them.
- Toss ranking support is limited to the documented ranking types: market
  trading amount/volume, top gainers/losers, and Toss trading amount/volume.
  Market-cap ranking remains explicitly unavailable until an approved provider
  supplies a direct ranked value; no derived ranking is added.
- FMP/FRED are not wired into these surfaces: the current official Toss guide
  documents the required FX, calendar, candle, orderbook, and ranking endpoints,
  while existing FMP/FRED wiring is for analysis data and does not provide a
  compatible direct market-cap/orderbook fallback.

## Verification deltas

- WireMock contract tests cover the new Toss paths, field mapping, malformed
  responses, timeout/rate-limit propagation, and partial orderbook data.
- Dashboard fixtures and route/component tests use available/degraded live
  responses instead of unsupported fixtures.
