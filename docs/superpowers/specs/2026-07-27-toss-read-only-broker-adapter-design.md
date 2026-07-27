# Toss OpenAPI 1.2.4 Read-Only Broker Adapter Design

## 1. Goal

Implement a multi-user, read-only `BrokerAdapter` for Toss Securities OpenAPI 1.2.4.

The adapter supports:

- OAuth 2.0 Client Credentials tokens
- Redis-backed single-token issuance per broker connection
- account discovery
- holdings summary and position mapping
- current price mapping
- buying-power mapping as a separate capacity snapshot
- normalized errors and rate-limit metadata
- sensitive-data-safe logging
- WireMock contract tests and Redis integration tests

It does not contain order creation, modification, cancellation, or order-history operations.

## 2. Official Contract

Source of truth:

- <https://developers.tossinvest.com/docs>
- <https://openapi.tossinvest.com/openapi-docs/latest/openapi.json>

Pinned API version: `1.2.4`.

Verified endpoints:

| Purpose | Contract |
|---|---|
| Token | `POST /oauth2/token`, form-urlencoded Client Credentials, no refresh token |
| Accounts | `GET /api/v1/accounts` |
| Holdings | `GET /api/v1/holdings`, requires `X-Tossinvest-Account` |
| Prices | `GET /api/v1/prices?symbols=...`, maximum 200 symbols |
| Buying power | `GET /api/v1/buying-power?currency=...`, requires `X-Tossinvest-Account` |

Token constraints:

- One access token is valid per client.
- Reissuing a token invalidates the previous token immediately.
- `expires_in` is expressed in seconds.
- All non-token calls use `Authorization: Bearer {access_token}`.

Rate-limit headers:

- `X-RateLimit-Limit`
- `X-RateLimit-Remaining`
- `X-RateLimit-Reset`
- `Retry-After`

Unknown enum values and unknown broker error codes must be accepted and normalized rather than causing deserialization failure.

## 3. Scope and Non-Goals

### In scope

- Imperative Spring `RestClient`
- hand-written DTOs containing only fields used by the read-only adapter
- connection-scoped token cache and distributed lock
- a production `TossCredentialProvider` port
- test-only credential provider implementation
- deterministic mapping to internal domain records
- WireMock HTTP contract tests
- Testcontainers Redis concurrency tests

### Out of scope

- credential persistence or encryption
- credential-management REST APIs
- real order submission
- order mutation DTOs or clients
- order history and UNKNOWN order reconciliation
- cash-balance inference
- adding buying power to cash, total assets, or portfolio value
- automatic retry of rate-limited or temporary broker requests
- holdings or quote caching

## 4. Module Boundary

Create a focused package:

```text
com.jmj.trade.broker
  BrokerAdapter
  BrokerConnectionRef
  BrokerAccountRef
  BrokerAccountView
  AccountSnapshot
  AccountCapacitySnapshot
  Position
  Quote
  MoneyByCurrency
  CashBalanceStatus
  BrokerException
  BrokerErrorCategory
  BrokerResponse
  BrokerCallMetadata
  RateLimitSnapshot

com.jmj.trade.broker.toss
  TossInvestBrokerAdapter
  TossCredentialProvider
  TossCredentials
  TossTokenManager
  TossApiClient
  TossResponseMapper
  TossErrorNormalizer
  TossSensitiveDataMasker
  TossApiProperties

com.jmj.trade.broker.toss.dto
  TossTokenDto
  TossAccountDto
  TossHoldingsDto
  TossPriceDto
  TossBuyingPowerDto
  TossErrorDto
  TossOAuth2ErrorDto
```

The Toss DTO package is private to the adapter implementation. No Toss response type may cross the `BrokerAdapter` boundary.

## 5. Read-Only Broker Contract

```java
public interface BrokerAdapter {
    BrokerResponse<List<BrokerAccountView>> getAccounts(
            BrokerConnectionRef connection);

    BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account);

    BrokerResponse<List<Position>> getPositions(BrokerAccountRef account);

    BrokerResponse<Quote> getQuote(
            BrokerConnectionRef connection,
            String symbol);

    BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(
            BrokerAccountRef account,
            Currency currency);
}
```

There are deliberately no order mutation methods.

`BrokerResponse<T>` contains:

- the mapped domain value
- `BrokerCallMetadata`

`BrokerCallMetadata` contains:

- broker request ID
- locally observed response time
- nullable `RateLimitSnapshot`

`RateLimitSnapshot` preserves successful-response headers when present:

- limit
- remaining
- reset duration
- retry-after duration

Absence of rate-limit headers is represented as absent metadata, not zero.
Application services receive this envelope directly; thread-local, logging-only,
or metrics-only side channels are not used.

### References

`BrokerConnectionRef` contains only the internal `brokerConnectionId`.

`BrokerAccountRef` contains:

- `brokerConnectionId`
- `accountSeq`
- masked display account number
- broker account type as a raw string

Secrets never appear in either reference.

## 6. Domain Mapping

### Accounts

`GET /api/v1/accounts` maps:

- `accountNo` -> masked display value only
- `accountSeq` -> `BrokerAccountRef.accountSeq`
- `accountType` -> raw string

The raw account number is not logged or exposed by the internal domain model.

### Account snapshot

`getAccount()` calls `GET /api/v1/holdings` and returns:

- account reference
- total purchase amount by currency
- market value before and after cost by currency
- profit/loss before and after cost by currency
- portfolio profit/loss rates
- daily profit/loss amount by currency and rate
- `cashBalanceStatus = UNKNOWN`
- no cash amount

KRW and USD values remain separate. No exchange-rate conversion or cross-currency addition occurs.

### Positions

Only holdings with `marketCountry = US` are returned by the US-stock adapter.

Each `Position` maps:

- symbol
- name
- market country
- currency
- quantity
- last price
- average purchase price
- market value
- profit/loss
- daily profit/loss
- required `cost.commission`
- nullable `cost.tax`

Decimal strings are parsed with `BigDecimal`. Invalid or missing required decimal fields produce a contract error rather than zero.

### Quote

`GET /api/v1/prices` maps:

- symbol
- nullable broker timestamp
- last price
- currency
- observation time recorded locally

Symbol input is normalized to uppercase and must match `^[A-Z0-9.-]+$`. The requested symbol must exactly match one returned result.

### Account capacity

`GET /api/v1/buying-power` maps:

- account reference
- currency
- `cashBuyingPower`
- observation time

`AccountCapacitySnapshot` is not a balance and must not be added to account cash, holdings value, or total assets.

## 7. Credential and Token Isolation

```java
public interface TossCredentialProvider {
    TossCredentials get(UUID brokerConnectionId);
}
```

Only the port exists in production code. Tests supply an in-memory implementation.

Redis keys are connection-scoped:

```text
broker:toss:oauth:v1:{brokerConnectionId}
broker:toss:oauth:v1:{brokerConnectionId}:lock
```

Token algorithm:

1. Read the cached token.
2. If present, use it.
3. Otherwise acquire the Redis lock with a unique owner value and a 10-second TTL.
4. The lock owner checks the token cache again.
5. If still absent, load credentials and issue one token.
6. Cache the token with `max(1 second, expires_in - 60 seconds)`.
7. Release the lock with an owner-checked Lua compare-and-delete.
8. A non-owner waits for the configured token wait timeout, which must be
   strictly longer than the maximum token HTTP request time.
9. If no token appears, fail with `TEMPORARY`; do not issue a second token.

Redis failure is fail-closed. There is no local token fallback because a concurrent token issuance would invalidate the token used by other instances.

Configuration validation rejects startup unless:

```text
token-wait-timeout > token-request-timeout
token-lock-ttl > token-request-timeout
```

On `401` with `invalid-token` or `expired-token`:

1. Remove the cached token only if it equals the rejected token.
2. Re-enter the locked issuance flow.
3. Retry the broker request once.
4. A second authentication failure is returned to the caller.

## 8. HTTP and Error Handling

`RestClient` uses:

- configurable base URL, default `https://openapi.tossinvest.com`
- explicit connect and read timeouts
- JSON response mapping
- form-urlencoded token request
- Bearer authorization for read APIs
- `X-Tossinvest-Account` only for account-context calls

Normalized error categories:

| Condition | Category | Retriable |
|---|---|---|
| invalid/expired token after one refresh | `AUTHENTICATION` | false |
| 403 | `AUTHORIZATION` | false |
| broker validation error | `INVALID_REQUEST` | false |
| 404 | `NOT_FOUND` | false |
| 429 | `RATE_LIMITED` | true, caller-controlled |
| 5xx | `TEMPORARY` | true, caller-controlled |
| timeout/connection error | `NETWORK` | true, caller-controlled |
| malformed success response | `CONTRACT` | false |

The token endpoint does not use the normal API error envelope. Its OAuth error
body has top-level `error` and `error_description` fields. Token failures map:

| OAuth response | Category |
|---|---|
| 400 `invalid_request` or `unsupported_grant_type` | `INVALID_REQUEST` |
| 401 `invalid_client` | `AUTHENTICATION` |
| 403 `access_denied` | `AUTHORIZATION` |
| 429 | `RATE_LIMITED` |

`BrokerException` contains:

- category
- HTTP status when available
- raw broker error code as a safe identifier
- request ID
- retry-after duration when available
- retriable flag

It never contains access tokens, client secrets, raw response bodies, or full account numbers.

The adapter does not automatically retry 429, 5xx, or network failures.

## 9. Sensitive Information

`TossSensitiveDataMasker` applies:

- `Authorization` -> `Bearer ***`
- token and client secret -> `***`
- account number -> all but the final four characters masked
- query parameters are logged only from an allowlist

Structured call metadata may contain:

- operation name
- HTTP status
- broker request ID
- latency
- rate-limit limit, remaining, reset, and retry-after values

It may not contain credentials, access tokens, full account numbers, raw holdings payloads, or raw error response bodies.

`TossCredentials.toString()` must be explicitly redacted.

## 10. Tests

### WireMock contract tests

Pin fixtures to OpenAPI 1.2.4 examples and verify:

- token request content type and form fields
- Bearer header
- `X-Tossinvest-Account`
- account-list mapping and account-number masking
- holdings-summary mapping with cash `UNKNOWN`
- US-only position mapping, including required commission and nullable tax
- nullable quote timestamp
- buying-power separation
- broker-returned error envelope normalization
- OAuth token error normalization for 400, 401, 403, and 429
- 429 `Retry-After` and rate-limit header mapping
- successful-response rate-limit headers returned through
  `BrokerResponse<T>.metadata.rateLimit`
- unknown account type and error code tolerance
- absence of order endpoint calls

### Redis integration tests

Use Testcontainers Redis and WireMock:

- concurrent cache misses issue one OAuth token
- separate connection IDs receive separate tokens
- lock losers wait for the cached token
- lock timeout does not issue a second token
- Redis failure is fail-closed
- one 401 invalidates the rejected cached token and refreshes once

### Security tests

Verify that logs, exception messages, credential `toString()`, and masking helpers do not contain:

- client secret
- access token
- full account number

### Conditional configuration tests

Verify with an application context runner:

- no `TossCredentialProvider` Bean creates no Toss adapter, token manager, or
  Toss HTTP client Bean
- an explicit test provider Bean activates the read-only Toss adapter
- no production fallback provider Bean exists
- invalid timeout relationships fail configuration binding or startup

## 11. Configuration

Production configuration contains no credentials:

```yaml
broker:
  toss:
    base-url: https://openapi.tossinvest.com
    connect-timeout: 2s
    read-timeout: 5s
    token-request-timeout: 5s
    token-lock-ttl: 10s
    token-wait-timeout: 7s
    token-expiry-skew: 60s
```

Credentials come only from `TossCredentialProvider`.

`TossBrokerConfiguration` is activated only when a
`TossCredentialProvider` Bean exists:

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(TossCredentialProvider.class)
class TossBrokerConfiguration {
    // read-only Toss adapter beans
}
```

Production code provides no fallback `TossCredentialProvider`, no environment
credential provider, and no no-op adapter. If the application has no provider
Bean, no Toss adapter, token manager, or Toss HTTP client Bean is created.
Tests explicitly provide their test credential provider Bean.

## 12. Acceptance Criteria

- OpenAPI version `1.2.4` is pinned in code and contract tests.
- A checked-in version assertion fails if the official contract fixture's
  `info.version` differs from `1.2.4`; tests do not silently follow `latest`.
- The production adapter exposes no order mutation capability.
- Tokens are isolated by `brokerConnectionId`.
- Concurrent cache misses issue one token per connection.
- Token wait timeout is strictly longer than the token request maximum time.
- Redis or lock failure cannot trigger an unlocked token issuance.
- `getAccount()` keeps cash unknown.
- buying power remains a separate capacity snapshot.
- account, holdings, positions, quote, and capacity fixtures map without Toss DTO leakage.
- errors, rate limits, and unknown broker codes are normalized.
- successful call metadata carries available rate-limit headers to callers.
- no Toss adapter Bean exists without an explicit `TossCredentialProvider`
  Bean, and no fallback credential implementation exists.
- logs and exceptions contain no tested secrets or full account numbers.
- WireMock, Redis integration, and existing backend tests pass.
