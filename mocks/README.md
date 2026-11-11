# Toss WireMock mock

Local and mock-stack Toss OpenAPI stubs (`compose.mock.yaml`, WireMock 3.13.2). Read-only: no order or trade
endpoint may be stubbed (`scripts/test-local-stack.sh` enforces this).

## Hazard scenarios (BUY hard-risk checks)

Each symbol has a `hazard-<symbol>-info.json` (`GET /api/v1/stocks?symbols=`) and a
`hazard-<symbol>-warnings.json` (`GET /api/v1/stocks/{symbol}/warnings`) stub at priority 1. Any other symbol
falls back to the generic stubs: ACTIVE, no warnings.

| Symbol | Scenario | Expected BUY result |
|---|---|---|
| `INVW` | Warning present (`INVESTMENT_WARNING`) | BLOCKED, `TOSS_STOCK_WARNING_INVESTMENT_WARNING` |
| `UNKW` | Unknown warning code (`FUTURE_WARNING_CODE`) | BLOCKED, `TOSS_STOCK_WARNING_UNKNOWN` |
| `DLST` | `status=DELISTED` | BLOCKED, `TOSS_STOCK_NOT_ACTIVE` |
| `PAST` | `delistDate=2026-01-02` (past), status ACTIVE | BLOCKED, `TOSS_STOCK_DELISTING` |
| `LEVR` | ETF with `leverageFactor=2.0` | BLOCKED, `TOSS_LEVERAGED_PRODUCT` |
| `WRNT` | `securityType=STOCK_WARRANTS` | BLOCKED, `TOSS_SECURITY_TYPE_UNSUPPORTED` |
| `NF404` | Warnings endpoint returns 404 `stock-not-found` | BLOCKED, `TOSS_STOCK_HAZARD_UNAVAILABLE` |
| `ERR500` | Warnings endpoint returns 500 | BLOCKED, `TOSS_STOCK_HAZARD_UNAVAILABLE` |

The stubs are static. They do not check the request time, so a delisting or warning window never expires in the
mock. Use the symbols above for scenario checks; use any other ticker for the clean path.
