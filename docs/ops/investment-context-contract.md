# Investment onboarding and risk preflight contract

This document describes the MCP surfaces for inspecting investment onboarding,
calculating a proposed-quantity risk view, and registering a ticker for the
existing monitoring pipeline. These tools do not issue investment approval or
place orders.

## Tools and scopes

| Tool | Scope | Inputs | Effect |
|---|---|---|---|
| `get_investment_onboarding_readiness` | `connector:read` | Required `ticker` | Reads saved registration, pipeline, thesis, risk-mark, and candidate state. |
| `preflight_investment_risk` | `connector:read` | Required `ticker`, positive JSON number `quantity` | Computes a proposed-quantity risk view from server-selected risk inputs. It does not accept an execution price. |
| `register_investment_candidate` | `connector:trade` | Required `ticker` | Idempotently registers the authenticated user's ticker in the existing monitoring watchlist. |

MCP input objects are closed: unknown fields are rejected. Ticker syntax is
1–32 ASCII letters, digits, `.`, `_`, or `-`; accepted tickers are normalized
to uppercase. Quantity must be a JSON number strictly greater than zero and
must satisfy the service's `BigDecimal` bounds: scale -18 through 10, at most
18 integer digits, and at most 28 significant digits. Exponent notation is
validated by the resulting decimal scale and integer-digit count. Strings,
null, zero, negative values, and out-of-bound decimals return
`INVALID_ARGUMENT`. The protocol checks shape, types, ticker syntax, positivity,
scale, and integer-digit bounds before service dispatch; service validation
failures are mapped to the same sanitized code. A read-scope key cannot
register a candidate.

The same authenticated-user services have session-authenticated REST routes:

```http
GET  /investment/onboarding/readiness?ticker=AAPL
POST /investment/onboarding/register
POST /investment/risk/preflight
```

Registration body: `{ "ticker": "AAPL" }`. Risk preflight body:
`{ "ticker": "AAPL", "quantity": 1.25 }`. The GET reads readiness; the
registration POST changes only the caller's existing watchlist state. The
preflight POST performs the read-only scenario calculation. REST validation
errors use HTTP 400 with `INVESTMENT_INPUT_INVALID`; an invalid authenticated
principal uses HTTP 403.

Equivalent MCP calls use the same ticker/quantity arguments but different
scopes:

```json
{"name":"get_investment_onboarding_readiness","arguments":{"ticker":"AAPL"}}
{"name":"register_investment_candidate","arguments":{"ticker":"AAPL"}}
```

The first requires `connector:read`; the second requires `connector:trade`.

## Readiness

`get_investment_onboarding_readiness` calls
`InvestmentOnboardingService.readiness(authenticatedUserId, ticker)`. It returns
the service view unchanged, including `registered`, overall status, snapshot
time, named source statuses and per-source `dataAsOf`, collection/pipeline
summary, thesis revision/invalidation/receipt/verification and eligibility
fields, risk-mark value/source/basis/as-of/status/reason, full candidate
records (including trigger, source time, and formula inputs), and blocker codes.
A syntactically valid ticker that is not registered and is not held returns a
readiness view with missing-data status; it is not an input error.

The read path uses stored investment context and watchlist state. It does not
fetch a provider, start data collection, write a candidate, change a thesis, or
prepare an order. Missing values remain JSON `null`; source statuses and their
as-of values are not replaced with inferred values.

## Risk preflight

`preflight_investment_risk` calls
`InvestmentRiskPreflightService.preflight(authenticatedUserId,
PreflightInput(ticker, quantity))`. The response is the service's `PreflightView`
unchanged: ticker and quantity; `buyEligible` and blockers; current thesis
receipt; portfolio/manual freshness gate; order-policy gate; cash gate with its
source; and `currentRisk` and `proFormaRisk` contribution maps.

The result can include the service's risk mark, source, basis, timestamps,
statuses, and numeric contributions. For the pro-forma scenario, the service
adds `quantity × server-selected risk mark` to the target position and computes
weights against the existing total equity, which stays unchanged under the
cash-funded assumption. It does not add the purchase cost to the equity
denominator. A cash gate is reported separately. Its source identifies either
sheet-reported cash or broker-reported cash buying power, depending on the
configured data source. The calculation does not model margin, open-order
reservations, or fees. MCP does not select or recalculate marks, policy limits,
cash, current risk, or pro-forma risk. Unknown or stale inputs remain
null/status-bearing and can make `buyEligible` false. `buyEligible` is a
risk-gate result for this preflight only. It is not a buy recommendation, user
approval, an order proposal, or permission to trade. The tool is read-only and
does not call a broker order-execution service.

Business gates such as a missing/stale mark, incomplete portfolio, unconfirmed
thesis, or policy violation return a successful structured view with blockers;
they are not converted into MCP transport errors. Structurally invalid ticker
or quantity returns `INVALID_ARGUMENT` before service invocation.

## Candidate registration and scheduler boundary

`register_investment_candidate` calls
`InvestmentOnboardingService.register(authenticatedUserId, ticker)` and returns
the service receipt (`ticker`, `registered`, `alreadyRegistered`, and `status`)
unchanged. Duplicate registration is idempotent and does not create a second
watchlist audit transition. The authenticated user's existing `monitoring_watchlist`
and monitoring-state history are used; no schema migration is required.

Registration does not run alpha research, call a provider, or start data
collection in the request. The existing `InvestmentDataScheduler` captures its
normal scheduled inputs. `InvestmentContextService.captureSymbols` already
includes eligible watchlist statuses (`WATCH`, `PREPARE`, and
`ACTION_CANDIDATE`) along with its other configured symbols. A later normal
scheduler run handles collection and context refresh.

Registration requires `connector:trade`. Missing write scope returns
`TRADE_SCOPE_REQUIRED`; malformed input returns `INVALID_ARGUMENT`. Neither
read tool requires write scope.

## Position horizon context (V65/V66)

Position horizons are stored separately from Thesis `classification` in the user-scoped `investment_position_mandates` table. `get_investment_context` exposes each row through `positionMandates[]`; `upsert_position_mandate` writes one ticker through the existing `connector:trade` scope. The server derives `riskPriceRole` and `source=USER_CONFIRMED` from the supplied horizon. Missing rows remain missing and must be treated as `UNKNOWN` unless an explicitly user-confirmed prompt fallback applies. Neither the mandate nor this onboarding contract changes `priceRiskTriggerPrice`.

V66 adds optional decision fields `investmentHorizon`, `priceRiskState`, `thesisHealth`, and `actionReason`, preserving historical ledger rows. Updated prompt drafts require the four values for new decisions. Tactical price breaches trigger an EXIT/REDUCE evaluation; strategic breaches trigger thesis revalidation, and an intact thesis may remain HOLD. Hard Risk Gates and confirmed structural thesis damage remain independent of the horizon. The policy resolver returns review states only; it does not choose order actions or place orders. See the full [Investment Context MCP contract](investment-context-mcp-contract.md#position-mandates-and-decision-context-v65v66).

## Protocol and source references

The MCP endpoint is `POST /api/v1/connector/mcp`. The connector key supplies
the authenticated user identity; the caller cannot choose another user.

- [`ConnectorMcpProtocol`](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java)
- [`InvestmentOnboardingService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentOnboardingService.java)
- [`InvestmentRiskPreflightService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentRiskPreflightService.java)
- [`InvestmentDataScheduler`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentDataScheduler.java)
- [`InvestmentContextService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java)
- [`MonitoringWatchlistService`](../../trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringWatchlistService.java)
