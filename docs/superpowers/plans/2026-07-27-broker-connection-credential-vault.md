# Broker Connection Credential Vault Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist multi-user Toss broker connections with versioned AES-GCM credentials, enforce ownership and fail-closed access, and expose authenticated credential lifecycle and validation APIs without adding order submission.

**Architecture:** Spring Boot owns the `BrokerConnection` aggregate and encrypted credential storage. A production `TossCredentialProvider` first reads only the current credential revision, then decrypts only on a Redis token cache miss after the revision lock and cache recheck. REST mutations are user-scoped, CSRF-protected, transactionally persisted, and never return or log plaintext credentials.

**Compatibility note:** Task 4 introduces the `current`/`decrypt` split. Keep any temporary compatibility bridge strictly inside the broker stack so Tasks 4 and 5 both compile, and remove the bridge once Task 5 rewires token retrieval to the revision-aware path.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, PostgreSQL 17, Flyway, Spring Security, Spring MVC, Redis, JDK `Cipher` AES/GCM, WireMock, Testcontainers, Maven.

**Spec:** `docs/superpowers/specs/2026-07-27-broker-connection-credential-vault-design.md`

---

## Chunk 1: Persistence and domain

### Task 1: Flyway V4 and PostgreSQL constraints

**Files:**
- Create: `trading-backend/src/main/resources/db/migration/V4__create_broker_connections.sql`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionSchemaTest.java`

- [ ] **Step 1: Write the failing V4 schema tests**

Add PostgreSQL Testcontainers tests that:

- migrate empty DB through V4 and V3→V4 incrementally;
- insert a `users(id)` anchor and one valid `broker_connections` row;
- reject non-deleted rows with null key version, wrong nonce length, missing ciphertext, or deleted timestamp;
- reject deleted rows that retain ciphertext/nonce/key version or omit `deleted_at`;
- reject two non-deleted `TOSS_INVEST` connections for the same user;
- allow a new connection after the previous row is `DELETED`;
- confirm `credential_revision > 0` and `version >= 0`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
JAVA_HOME=/Users/jjm/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.8/Contents/Home \
DOCKER_HOST=unix:///Users/jjm/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./mvnw -q -Dtest=BrokerConnectionSchemaTest test
```

Expected: FAIL because Flyway V4 and the tables do not exist.

- [ ] **Step 3: Implement the minimal migration**

Create:

```sql
CREATE TABLE users (id UUID PRIMARY KEY);

CREATE TABLE broker_connections (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    broker_type VARCHAR(40) NOT NULL CHECK (broker_type = 'TOSS_INVEST'),
    status VARCHAR(30) NOT NULL CHECK (
        status IN ('UNVERIFIED', 'ACTIVE', 'INVALID', 'DELETED')
    ),
    credential_ciphertext BYTEA,
    credential_nonce BYTEA,
    credential_key_version INTEGER,
    credential_revision BIGINT NOT NULL CHECK (credential_revision > 0),
    last_validated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT ck_broker_connection_secret_shape CHECK (
        (status <> 'DELETED'
         AND credential_ciphertext IS NOT NULL
         AND octet_length(credential_ciphertext) > 16
         AND credential_nonce IS NOT NULL
         AND octet_length(credential_nonce) = 12
         AND credential_key_version IS NOT NULL
         AND credential_key_version > 0
         AND deleted_at IS NULL)
        OR
        (status = 'DELETED'
         AND credential_ciphertext IS NULL
         AND credential_nonce IS NULL
         AND credential_key_version IS NULL
         AND last_validated_at IS NULL
         AND deleted_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_broker_connection_active_user_broker
    ON broker_connections(user_id, broker_type)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_broker_connection_owner
    ON broker_connections(user_id, id);
```

- [ ] **Step 4: Run schema tests and verify GREEN**

Run the Task 1 command. Expected: all `BrokerConnectionSchemaTest` tests PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/src/main/resources/db/migration/V4__create_broker_connections.sql \
        trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionSchemaTest.java \
        trading-backend/src/test/java/com/jmj/trade/PostgresIntegrationTest.java
git commit -m "feat: add broker connection schema"
```

### Task 2: BrokerConnection aggregate, JPA mapping, and repository

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerType.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionStatus.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/EncryptedCredentials.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnection.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionMetadata.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionRepository.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionRepositoryIntegrationTest.java`

- [ ] **Step 1: Write failing aggregate and repository tests**

Cover:

- creation starts `UNVERIFIED`, revision 1, version 0;
- replace increments credential revision, resets validation, and keeps ciphertext defensive copies;
- validate/invalid commands require the expected revision;
- delete increments revision, clears all encrypted fields and validation timestamp, and is terminal;
- repository `findByIdAndUserId` hides another user's row;
- metadata projection reads revision without exposing ciphertext;
- exact-revision provider query fails after replacement/deletion;
- concurrent saves of the same entity produce one `ObjectOptimisticLockingFailureException`.

- [ ] **Step 2: Run tests and verify RED**

```bash
./mvnw -q -Dtest=BrokerConnectionTest,BrokerConnectionRepositoryIntegrationTest test
```

Expected: compilation/test failure because the aggregate does not exist.

- [ ] **Step 3: Implement the minimal domain**

Use explicit commands:

```java
static BrokerConnection create(
    UUID id, UUID userId, EncryptedCredentials encrypted, Instant now);
void replaceCredentials(EncryptedCredentials encrypted, Instant now);
void markValidated(long expectedRevision, Instant now);
void markInvalid(long expectedRevision, Instant now);
void delete(Instant now);
```

Map `version` with `@Version`. Keep the repository package-private and expose only
user-scoped lookup, metadata projection, and exact revision/provider queries.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 2 command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/src/main/java/com/jmj/trade/broker/connection \
        trading-backend/src/test/java/com/jmj/trade/broker/connection
git commit -m "feat: add broker connection aggregate"
```

## Chunk 2: Encryption and token integration

### Task 3: CredentialKeyring and AES-GCM CredentialCipher

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/CredentialVaultProperties.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/CredentialKeyring.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/CredentialCipher.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/CredentialKeyringTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/CredentialCipherTest.java`

- [ ] **Step 1: Write failing crypto tests**

Cover:

- Base64 keys decode to exactly 32 bytes;
- active version must exist;
- encrypt/decrypt round trip;
- repeated encryptions use different 12-byte nonces/ciphertext;
- ciphertext, nonce, AAD, key version, and payload format tampering fail closed;
- old key ciphertext decrypts while new encryption uses the active key;
- client ID and secret must be nonblank and at most 4 KiB UTF-8 each;
- exception messages and `toString()` do not contain canary secrets.

- [ ] **Step 2: Run tests and verify RED**

```bash
./mvnw -q -Dtest=CredentialKeyringTest,CredentialCipherTest test
```

Expected: compilation failure because crypto classes do not exist.

- [ ] **Step 3: Implement minimum JDK crypto**

Use `AES/GCM/NoPadding`, `SecureRandom`, 12-byte nonce, 128-bit tag, a length-prefixed
binary payload, and AAD containing connection ID, user ID, broker type, key version,
credential revision, and format version. Map all key/tag/format failures to one
secret-free `CredentialUnavailableException`.

- [ ] **Step 4: Run tests and verify GREEN**

Run Task 3 tests. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/src/main/java/com/jmj/trade/broker/connection \
        trading-backend/src/test/java/com/jmj/trade/broker/connection
git commit -m "feat: encrypt broker credentials"
```

### Task 4: Metadata/decrypt split TossCredentialProvider

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossCredentialProvider.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossCredentialMetadata.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/DatabaseTossCredentialProvider.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/DatabaseTossCredentialProviderIntegrationTest.java`
- Modify: existing Toss provider test doubles under `trading-backend/src/test/java/com/jmj/trade/broker/toss/`

- [ ] **Step 1: Write failing provider tests**

Verify:

- `current(id)` returns revision only and does not call `CredentialCipher.decrypt`;
- `decrypt(id, expectedRevision)` returns credentials only for exact current revision;
- replacement/deletion, wrong broker type, missing key, or corrupted ciphertext fail closed;
- known canary credentials never appear in exception strings.

- [ ] **Step 2: Run tests and verify RED**

```bash
./mvnw -q -Dtest=DatabaseTossCredentialProviderIntegrationTest test
```

Expected: compilation failure because the split provider contract is absent.

- [ ] **Step 3: Implement the split contract**

```java
public interface TossCredentialProvider {
    TossCredentialMetadata current(UUID brokerConnectionId);
    TossCredentials decrypt(UUID brokerConnectionId, long expectedRevision);

    // Task 4 compile bridge only; Task 5 removes it.
    default TossCredentials get(UUID brokerConnectionId) {
        var metadata = current(brokerConnectionId);
        return decrypt(brokerConnectionId, metadata.credentialRevision());
    }
}

public record TossCredentialMetadata(long credentialRevision) {}
```

The production provider lives in `broker.connection`, uses repository projections for
`current`, and invokes `CredentialCipher` only from `decrypt`. The temporary `get(UUID)`
default keeps the existing Task 4 token manager compiling; it is not the final contract.

- [ ] **Step 4: Run provider and existing Toss tests**

```bash
./mvnw -q -Dtest=DatabaseTossCredentialProviderIntegrationTest,TossBrokerConfigurationTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/src/main/java/com/jmj/trade/broker \
        trading-backend/src/test/java/com/jmj/trade/broker
git commit -m "feat: load Toss credentials by revision"
```

### Task 5: Credential-revision Redis token and lock keys

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/BrokerConnectionRef.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossTokenManager.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossApiClient.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/toss/TossResponseMapper.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/broker/BrokerContractTest.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/broker/toss/TossTokenManagerRedisIntegrationTest.java`
- Modify: Toss contract tests constructing `BrokerConnectionRef`

- [ ] **Step 1: Write failing broker/token tests**

Cover:

- `BrokerConnectionRef` has the single UUID constructor/accessor;
- cache hit calls `current()` but never `decrypt()` or OAuth;
- miss acquires the revision lock, rechecks cache, then decrypts once;
- exact revision mismatch before decrypt fails without OAuth;
- replacement/deletion before OAuth completion discards the token and writes no cache;
- old-revision late 401 invalidation cannot delete a newer-revision token;
- token/lock keys include connection ID and credential revision;
- token `toString()` is masked.

- [ ] **Step 2: Run tests and verify RED**

```bash
./mvnw -q -Dtest=BrokerContractTest,TossTokenManagerRedisIntegrationTest,TossApiClientContractTest test
```

Expected: compilation/assertion failures against the old provider and token contracts.

- [ ] **Step 3: Implement minimal revision-aware token flow**

Introduce a package-private:

```java
record TossAccessToken(String value, long credentialRevision) {
    @Override public String toString() { return "TossAccessToken[****]"; }
}
```

Change `TossTokenManager.getAccessToken` to return `TossAccessToken`, read metadata first,
derive `broker:toss:oauth:v2:{id}:{revision}`, return cache hits without decrypting, and
on miss lock/recheck/decrypt/issue/post-check before caching. `TossApiClient` carries the
token snapshot through one 401 refresh and uses `token.value()`.
This is a clean cutover: new code only reads/writes v2 revision-scoped keys; v1 keys are
left to expire and are not dual-read.
Remove the Task 4 `TossCredentialProvider.get(UUID)` compatibility default in this task;
the final provider contract contains only `current` and `decrypt`.

Change `BrokerConnectionRef` to:

```java
public record BrokerConnectionRef(UUID brokerConnectionId) {}
```

Remove Toss broker-string checks for connection references. Keep account reference validation.

- [ ] **Step 4: Run the broker/Toss tests and verify GREEN**

Run Task 5 tests plus:

```bash
./mvnw -q -Dtest='com.jmj.trade.broker.**' test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/src/main/java/com/jmj/trade/broker \
        trading-backend/src/test/java/com/jmj/trade/broker
git commit -m "feat: isolate Toss tokens by credential revision"
```

## Chunk 3: Lifecycle and validation services

### Task 6: Create, replace, and delete transaction service

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/UserAnchorRepository.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionService.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionView.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionException.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionServiceIntegrationTest.java`

- [ ] **Step 1: Write failing transaction tests**

Cover:

- create anchors authenticated user, encrypts, and returns no secret;
- duplicate active connection returns stable conflict and creates no partial row;
- replace is owner-scoped, increments revision, resets status/validation;
- delete is owner-scoped and atomically scrubs encrypted columns;
- another user's ID and absent ID both map to not found;
- crypto/flush/optimistic-lock failures roll back the whole mutation;
- concurrent replaces: one commit, one optimistic conflict;
- replace/delete race applies exactly one command.

- [ ] **Step 2: Run tests and verify RED**

```bash
./mvnw -q -Dtest=BrokerConnectionServiceIntegrationTest test
```

Expected: compilation failure because the service is absent.

- [ ] **Step 3: Implement explicit transaction commands**

```java
BrokerConnectionView createToss(UUID userId, String clientId, String clientSecret);
BrokerConnectionView replaceCredentials(
    UUID userId, UUID connectionId, String clientId, String clientSecret);
void delete(UUID userId, UUID connectionId);
```

Use `INSERT INTO users(id) ... ON CONFLICT DO NOTHING` only for the authenticated user.
Translate unique and optimistic failures to stable secret-free exceptions.

- [ ] **Step 4: Run tests and verify GREEN**

Run Task 6 tests. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/src/main/java/com/jmj/trade/broker/connection \
        trading-backend/src/test/java/com/jmj/trade/broker/connection
git commit -m "feat: manage broker connection credentials"
```

### Task 7: Validation service with separated transactions

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionValidationService.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionTransactions.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionValidationServiceIntegrationTest.java`

- [ ] **Step 1: Write failing validation tests**

Use a controlled fake `BrokerAdapter` and verify:

- Tx 1 loads owner/id/revision and closes before the adapter call;
- success calls `getAccounts(new BrokerConnectionRef(id))` and marks the same revision ACTIVE;
- broker authentication/authorization failure marks the same revision INVALID;
- network/rate-limit/5xx and local credential failures do not change status;
- replacement during the external call makes Tx 2 return conflict and never validates the new revision;
- another user's connection is never sent to the adapter.

- [ ] **Step 2: Run tests and verify RED**

```bash
./mvnw -q -Dtest=BrokerConnectionValidationServiceIntegrationTest test
```

Expected: compilation failure because validation services are absent.

- [ ] **Step 3: Implement two transaction boundaries**

`BrokerConnectionTransactions` owns short `@Transactional` methods:

```java
ValidationTarget loadOwnedTarget(UUID userId, UUID connectionId);
BrokerConnectionView markValidated(UUID userId, UUID id, long expectedRevision);
BrokerConnectionView markInvalid(UUID userId, UUID id, long expectedRevision);
```

`BrokerConnectionValidationService` remains non-transactional around
`BrokerAdapter.getAccounts(new BrokerConnectionRef(id))`.

- [ ] **Step 4: Run tests and verify GREEN**

Run Task 7 tests. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/src/main/java/com/jmj/trade/broker/connection \
        trading-backend/src/test/java/com/jmj/trade/broker/connection
git commit -m "feat: validate broker connections"
```

## Chunk 4: Security, REST, and end-to-end verification

### Task 8: Spring Security, REST API, and public error boundary

**Files:**
- Modify: `trading-backend/pom.xml`
- Modify: `trading-backend/src/main/java/com/jmj/trade/TradingBackendApplication.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/security/SecurityConfiguration.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionController.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionRequest.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionResponse.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionErrorHandler.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/broker/connection/CredentialVaultConfiguration.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionControllerIntegrationTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/security/SecurityConfigurationTest.java`

- [ ] **Step 1: Write failing security/controller tests**

Cover:

- unauthenticated requests return 401;
- authenticated mutation without CSRF returns 403;
- HTTP Basic/form login/generated default user are unavailable;
- non-UUID principal returns `403 AUTHENTICATED_USER_INVALID`;
- create/replace/verify/delete routes use principal UUID only;
- cross-user replace/verify/delete return the same 404 as missing;
- request/response/error serialization never contains canary credentials;
- Vault disabled means no management controller/provider/Toss adapter bean;
- Vault enabled with missing/invalid active key fails startup.

- [ ] **Step 2: Run tests and verify RED**

```bash
./mvnw -q -Dtest=BrokerConnectionControllerIntegrationTest,SecurityConfigurationTest test
```

Expected: compilation failure because web/security classes and dependencies are absent.

- [ ] **Step 3: Add only required dependencies and implement boundary**

Add:

- `spring-boot-starter-web`
- `spring-boot-starter-security`
- test-scope `spring-security-test`

Exclude `UserDetailsServiceAutoConfiguration`, require authentication for
`/api/v1/broker-connections/**`, keep CSRF enabled, disable Basic and form login, and
define no fallback users. Override request DTO `toString()` to mask both fields.

Expose exactly:

- `POST /api/v1/broker-connections/toss`
- `PUT /api/v1/broker-connections/{id}/credentials`
- `POST /api/v1/broker-connections/{id}/verify`
- `DELETE /api/v1/broker-connections/{id}`

- [ ] **Step 4: Run tests and verify GREEN**

Run Task 8 tests. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/pom.xml trading-backend/src/main/java trading-backend/src/test/java
git commit -m "feat: expose secured broker connection API"
```

### Task 9: Cross-cutting authorization, rollback, concurrency, and non-disclosure

**Files:**
- Create: `trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionSecurityIntegrationTest.java`
- Modify: relevant tests from Tasks 2–8 only when a missing acceptance case is found

- [ ] **Step 1: Write the acceptance tests before any repair**

One integrated suite must verify:

- two users cannot read or mutate each other's connection;
- plaintext canary is absent from DB text/byte columns, API bodies, public exceptions,
  `toString()`, and Redis credential keys/values;
- no claim/test attempts JVM heap zeroization;
- missing key and GCM tag failure cause zero broker HTTP calls;
- cache hit performs metadata lookup and zero decrypt/OAuth calls;
- CRUD rollback and optimistic-lock races leave one valid aggregate state;
- validation race cannot mark replaced credentials ACTIVE;
- order mutation methods/endpoints remain absent.

- [ ] **Step 2: Run tests and verify RED if a gap exists**

```bash
./mvnw -q -Dtest=BrokerConnectionSecurityIntegrationTest test
```

Expected: FAIL only for uncovered acceptance gaps. If every behavior already passes,
record that Task 9 is a pure acceptance suite and make no production change.

- [ ] **Step 3: Add the minimum repair for each observed gap**

Do not add audit/outbox, KMS, background rotation, sync workers, frontend, or order APIs.

- [ ] **Step 4: Run connection and broker suites**

```bash
./mvnw -q -Dtest='com.jmj.trade.broker.**' test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add trading-backend/src/test/java/com/jmj/trade/broker/connection \
        trading-backend/src/main/java/com/jmj/trade/broker
git commit -m "test: verify broker credential isolation"
```

### Task 10: Full verification and independent review

**Files:**
- Modify only files required to repair verified failures or review blockers.

- [ ] **Step 1: Run formatting/static checks**

```bash
git diff --check
rg -n 'placeOrder|modifyOrder|cancelOrder|/api/v1/orders' \
  trading-backend/src/main/java/com/jmj/trade/broker
```

Expected: clean diff; no order mutation implementation.

- [ ] **Step 2: Run the full build**

```bash
JAVA_HOME=/Users/jjm/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.8/Contents/Home \
DOCKER_HOST=unix:///Users/jjm/.colima/default/docker.sock \
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
TESTCONTAINERS_RYUK_DISABLED=true \
./mvnw -q clean verify
```

Expected: exit 0, zero failures/errors.

- [ ] **Step 3: Independently review spec compliance**

Review the full diff against the design spec and confirm every requested behavior,
explicit exclusion, and fail-closed boundary.

- [ ] **Step 4: Independently review code quality/security**

Check tenant isolation, crypto/AAD use, secret exposure, transaction boundaries,
Redis concurrency, exception normalization, dependency minimalism, and test adequacy.
Repair blockers through RED→GREEN and re-review.

- [ ] **Step 5: Final clean-worktree evidence**

```bash
git status --short --branch
git log -1 --format='%H %s'
```

Expected: feature branch, no uncommitted files. Report commit hash, test count, changed
files, independent review result, and worktree status.
