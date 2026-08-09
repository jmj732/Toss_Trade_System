# Toss sellable quantity live delta

## Scope

- Connect the Toss Open API `GET /api/v1/sellable-quantity` route to the existing
  broker-agnostic `getSellableQuantity` port.
- Preserve `UNKNOWN` when the route is unavailable, times out, returns a malformed
  payload, or returns a value inconsistent with the held position.
- Keep the live account sync as the single source used by portfolio reads and the
  final pre-trade risk gate. The API value is already effective sellable quantity,
  so open sell orders and partial fills must not be deducted a second time.
- Do not add a real-order call or a live credentialed test.

## Decisions

1. Use Toss's official `GET /api/v1/sellable-quantity?symbol={symbol}` with the
   `Authorization: Bearer` and `X-Tossinvest-Account: {accountSeq}` headers.
2. Map `result.sellableQuantity` as a non-negative decimal. US fractional shares
   remain supported.
3. For unavailable data (`NOT_FOUND`, authorization/transport/temporary/rate-limit
   failure, or contract failure), return the existing typed `UNKNOWN` snapshot so
   persistence and final risk evaluation remain fail-closed.
4. During sync, a known sellable value greater than the corresponding holdings
   quantity is an inconsistent broker response and is treated as `UNKNOWN`; no
   estimate is substituted.
5. No local subtraction of holdings minus open orders is introduced: Toss's
   sellable response is the effective quantity after broker-side availability
   rules, including pending/partial/today activity.

## Verification

- WireMock contract covers route, query, headers, response mapping, fractional US
  quantities, and malformed/timeout/error fallback.
- Account sync covers known, unknown, and holdings-inconsistent values.
- Risk/live-read coverage proves the latest synchronized sellable quantity is used
  immediately before submission, with partial fills and pending sells represented
  by the broker value exactly once.
- Only mock/contract/integration tests run; no real order is submitted.

## Official source

- https://developers.tossinvest.com/llms.txt
- https://openapi.tossinvest.com/openapi-docs/latest/api-reference/Apis/OrderInfoApi.md
- https://openapi.tossinvest.com/openapi-docs/latest/api-reference/Models/SellableQuantityResponse.md
