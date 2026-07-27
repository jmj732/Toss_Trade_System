# Toss OpenAPI 1.2.4 Read-Only Broker Adapter Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a multi-user Toss OpenAPI 1.2.4 read-only adapter with connection-isolated OAuth tokens, Redis single issuance, explicit rate-limit metadata, safe domain mapping, and no order capability.

**Architecture:** `com.jmj.trade.broker` owns the broker-neutral read contract. `com.jmj.trade.broker.toss` owns hand-written Toss DTOs, HTTP calls, token coordination, normalization, and mapping. A conditional Spring configuration creates the Toss stack only when the application supplies a `TossCredentialProvider`; production code supplies no credential implementation.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring `RestClient`, Jackson 3, Spring Data Redis, Redis 7, WireMock 3.12.1, Testcontainers, JUnit 5.

---

## Chunk 1: Read-only contract and conditional configuration

### Task 1: Add only the dependencies required by the approved design

**Files:**
- Modify: `trading-backend/pom.xml`

- [ ] **Step 1: Add compile-time dependencies**

Add:

- `spring-boot-starter-restclient`
- `spring-boot-starter-json`
- `spring-boot-starter-data-redis`

Use the existing Spring Boot dependency management. Do not add an HTTP SDK,
retry library, Redis lock library, or code-generation plugin.

- [ ] **Step 2: Add the WireMock test dependency**

Add:

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.12.1</version>
    <scope>test</scope>
</dependency>
```

Reuse the already available Testcontainers core transitively provided by the
existing PostgreSQL Testcontainers dependency.

- [ ] **Step 3: Verify dependency resolution**

Run:

```bash
cd trading-backend
./mvnw -q -DskipTests compile
```

Expected: compile succeeds without production code changes.

### Task 2: Define the broker-neutral read contract

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerAdapter.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerConnectionRef.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerAccountRef.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerAccountView.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerResponse.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerCallMetadata.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/RateLimitSnapshot.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/Currency.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/MoneyByCurrency.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/CashBalanceStatus.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/AccountSnapshot.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/AccountCapacitySnapshot.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/Position.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/Quote.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerErrorCategory.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerException.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/BrokerContractTest.java`

- [ ] **Step 1: Write failing contract tests**

Cover:

- all references reject null identifiers
- `BrokerAccountRef` accepts only a masked display number
- `BrokerResponse` always contains a value and call metadata
- absent rate-limit headers remain absent, not zero
- `AccountSnapshot` requires `CashBalanceStatus.UNKNOWN` and exposes no cash amount
- `AccountCapacitySnapshot` is a separate type and has no total-assets operation
- `Position.costTax` is nullable while `costCommission` is required
- money is held per currency and has no cross-currency addition method
- `BrokerAdapter` exposes only accounts, account, positions, quote, and capacity methods

- [ ] **Step 2: Run the test and verify RED**

```bash
./mvnw -q -Dtest=BrokerContractTest test
```

Expected: compilation fails because the contract types do not exist.

- [ ] **Step 3: Implement the minimum immutable records and interface**

Use records and compact constructors for boundary validation. Use
`BigDecimal` for quantities, prices, rates, and money. Use `Instant` for
observed timestamps and `Optional` only at accessor boundaries where absence
is part of the contract.

The exact interface is:

```java
public interface BrokerAdapter {
    BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection);
    BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account);
    BrokerResponse<List<Position>> getPositions(BrokerAccountRef account);
    BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol);
    BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(
            BrokerAccountRef account, Currency currency);
}
```

Do not add order methods, broker DTOs, persistence entities, factories, or
service-layer aliases.

- [ ] **Step 4: Run the contract test and verify GREEN**

```bash
./mvnw -q -Dtest=BrokerContractTest test
```

Expected: all contract tests pass.

### Task 3: Add credential port, validated properties, and conditional beans

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossCredentialProvider.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossCredentials.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossApiProperties.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossBrokerConfiguration.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/toss/TossBrokerConfigurationTest.java`

- [ ] **Step 1: Write failing conditional-configuration tests**

Use `ApplicationContextRunner` to prove:

- without a `TossCredentialProvider`, there is no Toss adapter, token manager,
  OAuth client, or API client Bean
- a test-only provider plus valid properties binds and validates
  `TossApiProperties`
- production sources contain no fallback `TossCredentialProvider`
- startup fails when `tokenWaitTimeout <= tokenRequestTimeout`
- startup fails when `tokenLockTtl <= tokenRequestTimeout`
- the approved defaults bind as 2s connect, 5s read, 5s token request, 10s
  lock TTL, 7s wait, and 60s expiry skew

- [ ] **Step 2: Run the test and verify RED**

```bash
./mvnw -q -Dtest=TossBrokerConfigurationTest test
```

Expected: compilation fails because the Toss configuration does not exist.

- [ ] **Step 3: Implement the credential boundary and properties**

Define only:

```java
public interface TossCredentialProvider {
    TossCredentials get(UUID brokerConnectionId);
}
```

`TossCredentials` stores client ID and client secret, validates non-blank
values, and redacts both from `toString()`.

Use `@ConfigurationProperties("broker.toss")` with constructor validation for:

```text
base-url
connect-timeout
read-timeout
token-request-timeout
token-lock-ttl
token-wait-timeout
token-expiry-skew
```

Register only validated properties at this stage. Do not create temporary
production collaborators or a partial adapter graph. Task 8 adds the guarded
`@ConditionalOnBean(TossCredentialProvider.class)` graph after all real
collaborators exist.

- [ ] **Step 4: Run configuration and contract tests**

```bash
./mvnw -q -Dtest='BrokerContractTest,TossBrokerConfigurationTest' test
```

Expected: all pass.

- [ ] **Step 5: Commit Chunk 1**

```bash
git add trading-backend/pom.xml \
  trading-backend/src/main/java/com/jmj/trade/broker \
  trading-backend/src/test/java/com/jmj/trade/broker
git commit -m "feat: define Toss read-only broker contract"
```

## Chunk 2: OAuth and Redis single-token issuance

### Task 4: Implement safe masking and OAuth response handling

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossSensitiveDataMasker.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossApiDtos.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossOAuthClient.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/toss/TossSensitiveDataMaskerTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/toss/TossOAuthClientContractTest.java`

- [ ] **Step 1: Write failing security and OAuth contract tests**

Use WireMock to verify:

- `POST /oauth2/token`
- `application/x-www-form-urlencoded`
- `grant_type=client_credentials`, `client_id`, and `client_secret`
- access token and positive `expires_in` mapping
- OAuth top-level errors for 400, 401, 403, and 429
- token request exceeds `tokenRequestTimeout` and fails as `NETWORK`
- exception messages and credential `toString()` contain no client secret,
  access token, raw body, or full account number
- masker returns `Bearer ***`, `***`, and only the final four account digits

- [ ] **Step 2: Run and verify RED**

```bash
./mvnw -q -Dtest='TossSensitiveDataMaskerTest,TossOAuthClientContractTest' test
```

Expected: compilation fails because the OAuth classes do not exist.

- [ ] **Step 3: Implement OAuth with `RestClient`**

Use one `RestClient` backed by `JdkClientHttpRequestFactory`:

- Java HTTP client connect timeout = `connectTimeout`
- request timeout = `tokenRequestTimeout`
- form-urlencoded body
- no automatic retry

Keep used response DTOs as package-private nested records in
`TossApiDtos.java`; do not create one public file per Toss DTO.

- [ ] **Step 4: Run and verify GREEN**

```bash
./mvnw -q -Dtest='TossSensitiveDataMaskerTest,TossOAuthClientContractTest' test
```

Expected: all pass.

### Task 5: Implement Redis token cache, lock, and compare-delete

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossTokenManager.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/toss/TossTokenManagerRedisIntegrationTest.java`

- [ ] **Step 1: Write failing Redis integration tests**

Start Redis with a plain `GenericContainer` and OAuth WireMock. Prove:

- concurrent cache misses for one `brokerConnectionId` issue exactly one token
- different connection IDs issue and cache separate tokens
- a lock loser waits for the winner's cached token
- waiting stops at `tokenWaitTimeout` and never issues an unlocked token
- Redis unavailability fails closed
- cache TTL is `max(1 second, expires_in - tokenExpirySkew)`
- lock release uses owner-checked compare-delete
- `invalidateIfCurrent` cannot delete a newer token

- [ ] **Step 2: Run and verify RED**

```bash
./mvnw -q -Dtest=TossTokenManagerRedisIntegrationTest test
```

Expected: compilation fails because `TossTokenManager` does not exist.

- [ ] **Step 3: Implement the minimum token manager**

Use `StringRedisTemplate` only. Redis keys are:

```text
broker:toss:oauth:v1:{brokerConnectionId}
broker:toss:oauth:v1:{brokerConnectionId}:lock
```

Algorithm:

1. read cached token
2. acquire lock with a random owner and `tokenLockTtl`
3. winner double-checks cache, loads credentials, issues and caches token
4. winner releases via one compare-delete Lua script
5. loser polls briefly until token appears or `tokenWaitTimeout` expires
6. timeout/Redis failure throws normalized `TEMPORARY`; no fallback issuance

Keep polling local and bounded; do not add messaging, pub/sub, Redisson, or a
custom scheduler.

- [ ] **Step 4: Run Redis and configuration tests**

```bash
./mvnw -q -Dtest='TossTokenManagerRedisIntegrationTest,TossBrokerConfigurationTest' test
```

Expected: all pass.

- [ ] **Step 5: Commit Chunk 2**

```bash
git add trading-backend/src/main/java/com/jmj/trade/broker/toss \
  trading-backend/src/test/java/com/jmj/trade/broker/toss
git commit -m "feat: coordinate Toss OAuth tokens with Redis"
```

## Chunk 3: Read HTTP client, error normalization, and rate-limit metadata

### Task 6: Pin the selected OpenAPI 1.2.4 contract

**Files:**
- Create: `trading-backend/src/test/resources/contracts/toss-openapi-1.2.4-manifest.json`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/toss/TossOpenApiVersionTest.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossOpenApiContract.java`

- [ ] **Step 1: Write a failing version test**

The checked-in reduced manifest contains only:

- `info.version = 1.2.4`
- `/oauth2/token`
- `/api/v1/accounts`
- `/api/v1/holdings`
- `/api/v1/prices`
- `/api/v1/buying-power`

Assert the production constant is `1.2.4` and every selected path is present.

- [ ] **Step 2: Run and verify RED**

```bash
./mvnw -q -Dtest=TossOpenApiVersionTest test
```

Expected: failure because the pinned constant and fixture do not exist.

- [ ] **Step 3: Add the constant and reduced manifest**

Do not check in the complete generated OpenAPI document and do not download
`latest` during tests.

- [ ] **Step 4: Run and verify GREEN**

```bash
./mvnw -q -Dtest=TossOpenApiVersionTest test
```

Expected: pass.

### Task 7: Implement one read client with explicit metadata delivery

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossErrorNormalizer.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossApiClient.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/toss/TossApiClientContractTest.java`

- [ ] **Step 1: Write failing WireMock contract tests**

Cover:

- Bearer header on every read request
- `X-Tossinvest-Account` only on holdings and buying-power calls
- prices use the `symbols` query parameter
- successful headers map through the package-private `TossApiClient` response
  envelope's metadata:
  `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`,
  `Retry-After`
- missing success headers produce absent rate-limit metadata
- successful metadata also carries request ID and local observation time
- regular error envelope preserves safe request ID and unknown error code
- 403, broker validation, 404, 429, 5xx, timeout, and malformed-success
  responses map to the approved categories and retriable flags
- 429 preserves `Retry-After`
- no 429, 5xx, or network retry occurs
- first invalid/expired 401 invalidates only the rejected token and retries once
- second authentication failure is returned without another retry

- [ ] **Step 2: Run and verify RED**

```bash
./mvnw -q -Dtest=TossApiClientContractTest test
```

Expected: compilation fails because the API client and normalizer do not exist.

- [ ] **Step 3: Implement the read client**

Use one authenticated `RestClient` with:

- connect timeout = `connectTimeout`
- request timeout = `readTimeout`
- `ResponseEntity` access so success headers are not lost
- one common execution method for response parsing, metadata, and errors
- no interceptor that stores metadata in thread-local state

The client returns a small package-private response envelope containing the
decoded Toss DTO plus public `BrokerCallMetadata`. It calls only the five
approved read endpoints.

- [ ] **Step 4: Implement normalization**

Parse regular API errors separately from OAuth errors. `BrokerException`
contains safe category/status/code/request ID/retry-after/retriable fields and
never the raw body. Unknown broker codes remain raw safe identifiers.

- [ ] **Step 5: Run client, OAuth, and version tests**

```bash
./mvnw -q -Dtest='TossApiClientContractTest,TossOAuthClientContractTest,TossOpenApiVersionTest' test
```

Expected: all pass.

- [ ] **Step 6: Commit Chunk 3**

```bash
git add trading-backend/src/main/java/com/jmj/trade/broker/toss \
  trading-backend/src/test/java/com/jmj/trade/broker/toss \
  trading-backend/src/test/resources/contracts
git commit -m "feat: normalize Toss read API responses"
```

## Chunk 4: Adapter mapping and regression verification

### Task 8: Implement account, holdings, quote, capacity, and conditional graph

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossResponseMapper.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossInvestBrokerAdapter.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/toss/TossInvestBrokerAdapterContractTest.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossBrokerConfiguration.java`

- [ ] **Step 1: Write failing end-to-end adapter contract tests**

Use WireMock plus test credentials and Redis. Cover:

- account discovery masks `accountNo` to final four digits and retains
  `accountSeq` and unknown raw account type
- account snapshot maps holdings totals by KRW/USD, keeps cash `UNKNOWN`, and
  performs no currency conversion
- positions return only `marketCountry=US`
- position commission is required and tax is nullable
- quote input is uppercased, matches `^[A-Z0-9.-]+$`, and exactly matches one
  returned symbol
- quote broker timestamp may be null while local observation time is present
- buying power maps to `AccountCapacitySnapshot` and never appears in account
  totals
- invalid or missing required decimals fail as `CONTRACT`, never zero
- Toss DTOs do not cross the `BrokerAdapter` boundary
- every adapter method preserves `BrokerCallMetadata`, including successful
  rate-limit data
- no request reaches an order endpoint

- [ ] **Step 2: Run and verify RED**

```bash
./mvnw -q -Dtest=TossInvestBrokerAdapterContractTest test
```

Expected: compilation fails because mapper and adapter do not exist.

- [ ] **Step 3: Implement strict manual mapping**

Keep DTOs package-private. Parse decimal strings through one mapper helper.
Reject missing required fields. Preserve nullable tax and quote timestamp.
Do not infer cash, FX rates, account total assets, or buying-power valuation.

- [ ] **Step 4: Wire and test the real conditional Bean graph**

Extend `TossBrokerConfigurationTest` now that all collaborators exist. Prove
that a provider Bean activates exactly one read-only adapter stack and that no
provider creates no Toss adapter, token manager, OAuth client, or API client
Bean.

Guard `TossBrokerConfiguration` with
`@ConditionalOnBean(TossCredentialProvider.class)`. With a provider Bean,
construct:

- OAuth `RestClient`
- read API `RestClient`
- `TossOAuthClient`
- `TossTokenManager`
- `TossApiClient`
- `TossResponseMapper`
- `TossInvestBrokerAdapter`

Without a provider Bean, construct none of them.

- [ ] **Step 5: Run all new broker tests**

```bash
./mvnw -q -Dtest='com.jmj.trade.broker.**' test
```

If Surefire does not expand the package glob, run:

```bash
./mvnw -q -Dtest='BrokerContractTest,TossBrokerConfigurationTest,TossSensitiveDataMaskerTest,TossOAuthClientContractTest,TossTokenManagerRedisIntegrationTest,TossOpenApiVersionTest,TossApiClientContractTest,TossInvestBrokerAdapterContractTest' test
```

Expected: all pass.

### Task 9: Verify the complete backend and safety boundary

- [ ] **Step 1: Run all PostgreSQL, Redis, WireMock, and unit tests**

```bash
cd trading-backend
DOCKER_HOST=unix:///Users/jjm/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
JAVA_HOME=/Users/jjm/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.8/Contents/Home \
./mvnw -q clean verify
```

Expected: all existing order-ledger and new broker tests pass with zero
failures and errors.

- [ ] **Step 2: Verify the read-only and secret boundaries**

```bash
rg -n 'placeOrder|modifyOrder|cancelOrder|/api/v1/orders' \
  src/main/java/com/jmj/trade/broker
rg -n 'implements TossCredentialProvider' src/main/java
```

Expected: both commands return no matches.

- [ ] **Step 3: Verify build artifacts and diff quality**

```bash
test -f target/trading-backend-0.0.1-SNAPSHOT.jar
git diff --check
git status --short
```

Expected: JAR exists, no whitespace errors, and only planned broker files are
changed.

- [ ] **Step 4: Request independent final code review**

Review for:

- accidental order capability
- unlocked token issuance or cross-connection token reuse
- token timing invariant violations
- lost success rate-limit metadata
- credential/account leakage
- cash or buying-power misclassification
- retry behavior outside the approved single 401 refresh

Fix every Critical or Important finding and rerun the smallest failing test
plus `clean verify`.

- [ ] **Step 5: Commit Chunk 4**

```bash
git add trading-backend
git commit -m "feat: add Toss read-only broker adapter"
```
