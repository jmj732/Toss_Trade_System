# Token-based authentication Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace session/CSRF authentication with OIDC-backed short-lived Bearer access tokens and rotating, hashed refresh-token sessions across the backend and dashboard.

**Architecture:** Keep OIDC as the login proof, but store OAuth authorization transactions in a signed Secure cookie instead of `HttpSession`. Issue signed access tokens and persist only refresh-token hashes in a family/session table. A stateless Bearer filter authenticates the existing internal UUID principal contract; the dashboard keeps the access token in a module closure and single-flights refresh/retry.

**Tech Stack:** Java 21, Spring Boot 4.1/Spring Security servlet filters, PostgreSQL/Flyway/JdbcTemplate, Next.js 16 App Router, React 19, Node built-in tests, Docker Compose.

---

## Chunk 1: Contract and failing tests

### Task 1: Add the delta contract

**Files:**
- Create: `docs/superpowers/specs/2026-08-05-token-based-authentication-delta.md`
- Create: `docs/superpowers/plans/2026-08-05-token-based-authentication.md`

- [x] Record token lifetimes, cookie attributes, endpoint semantics, origin policy, step-up claims, dashboard retry behavior, migration order, and rollback boundary.

### Task 2: Add backend red tests

**Files:**
- Create: `trading-backend/src/test/java/com/jmj/trade/security/AccessTokenServiceTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/security/RefreshTokenServiceTest.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/security/SecurityConfigurationTest.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/security/OidcMaxAgeAuthorizationRequestTest.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/security/OidcAuthSessionIntegrationTest.java`

- [x] Test signed-token issue/parse, expiry, tamper rejection, internal UUID principal, and `auth_time` propagation.
- [x] Test refresh rotation, plaintext absence, expired/revoked/reused token rejection, family revocation, session-only revocation, all-session revocation, and concurrent refresh serialization.
- [x] Test stateless API Bearer auth, CSRF absence, exact Origin checks, refresh/logout cookie attributes, and no `JSESSIONID` after OIDC success.
- [x] Run the focused tests and confirm they fail because the token flow is absent, then make them pass.

### Task 3: Add dashboard red tests

**Files:**
- Modify: `web-dashboard/test/api.test.mjs`
- Modify: `web-dashboard/test/login.test.mjs`
- Create: `web-dashboard/test/auth-memory.test.mjs`

- [x] Test fragment capture/removal, memory-only token storage, Bearer headers, one single-flight refresh after 401, retry of the original request, refresh failure, and logout memory clearing.
- [x] Run the dashboard test suite after the new assertions were added.

## Chunk 2: Backend token/session core (TDD green)

### Task 4: Add the refresh-session schema

**Files:**
- Create: `trading-backend/src/main/resources/db/migration/V38__create_auth_refresh_sessions.sql`
- Modify: `trading-backend/src/test/java/com/jmj/trade/security/OidcUserIdentitySchemaTest.java`

- [x] Add the user FK, family/session IDs, SHA-256 token hash uniqueness, timestamps, replacement/revocation/reuse columns, and indexes needed by token lookup and user-wide logout.
- [x] Assert the migration head and constraints with PostgreSQL.

### Task 5: Move OAuth transaction state out of HttpSession

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/security/CookieAuthorizationRequestRepository.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/security/DashboardAuthorizationRequestResolver.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/security/DashboardRedirects.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/security/SecurityConfiguration.java`

- [x] Serialize only the required OAuth request fields into a signed HttpOnly Secure SameSite=Lax cookie.
- [x] Carry validated `returnTo` in the signed transaction and consume it once on callback.
- [x] Keep success/failure redirects safe and ensure callback success can issue tokens without a server session.
- [x] Add a regression assertion that the OAuth authorization and callback path never creates `JSESSIONID`.

### Task 6: Implement signed access tokens and Bearer authentication

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/security/AccessTokenService.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/security/AccessTokenAuthenticationFilter.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/security/AuthenticatedUser.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/security/SecurityConfiguration.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/security/SessionController.java`
- Modify: `trading-backend/src/main/resources/application.yml`

- [x] Implement compact signed access tokens with fixed claims and constant-time signature comparison.
- [x] Reject malformed, expired, wrong-algorithm, wrong-signature, and invalid-subject tokens before the controller chain.
- [x] Set `SessionCreationPolicy.STATELESS`, `NullSecurityContextRepository`, disable request caching/CSRF/form/basic/session logout, keep `/api/**` at 401, and place the bearer filter before anonymous authentication.
- [x] Return only bearer authentication metadata from `GET /api/v1/session`; remove `csrfHeaderName` and `csrfToken` from the contract.
- [x] Preserve `Principal.getName()` as the internal UUID and expose authentication time for step-up.

### Task 7: Implement refresh rotation, reuse detection, and auth endpoints

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/security/RefreshTokenService.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/security/AuthController.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/security/OriginPolicy.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/security/SecurityConfiguration.java`

- [x] Generate high-entropy refresh tokens, persist only SHA-256 hashes, rotate under a row lock, and family-revoke on reuse.
- [x] Implement refresh, current-session logout, and all-session logout with cookie clearing and safe 401/403 responses.
- [x] Require the exact configured dashboard Origin on refresh/logout and emit Secure/HttpOnly/SameSite cookie attributes.
- [x] Issue an access token on OIDC success and preserve `auth_time`/step-up semantics across refresh.

## Chunk 3: Remove session authentication and update the OAuth boundary

### Task 8: Preserve step-up authorization under token principals

**Files:**
- Modify: `trading-backend/src/main/java/com/jmj/trade/order/PaperOrderWorkflowController.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/order/LiveOrderActivationController.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/order/KillSwitchController.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/order/RealOrderCanaryController.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionController.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/PredictionIngestionApiKeyController.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/prediction/PredictionModelRegistryController.java`
- Modify: related order/security integration tests

- [x] Read fresh OIDC `auth_time` and optional step-up claims from `AuthenticatedUser`.
- [x] Keep real-order, credential mutation, and API-key management fail-closed when reauthentication is stale/missing.
- [x] Add red tests for credential create/replace and API-key/model mutation with missing/stale `auth_time`.
- [x] Replace OIDC-session-specific controller injection with the token authentication contract.

## Chunk 4: Dashboard memory flow (TDD green)

### Task 9: Add the in-memory token client

**Files:**
- Create: `web-dashboard/lib/auth.js`
- Modify: `web-dashboard/lib/api.js`
- Modify: `web-dashboard/app/page.js`
- Modify: `web-dashboard/app/route-workspace.js`
- Modify: `web-dashboard/next.config.js`
- Modify: `web-dashboard/test/api.test.mjs`
- Modify: `web-dashboard/test/login-page.test.mjs`
- Modify: `web-dashboard/test/route-surface.test.mjs`

- [x] Consume and clear the callback fragment, keep the access token in a module closure only, and never serialize it to browser storage or query strings.
- [x] Route all API calls through one Bearer-aware fetch helper; refresh once after 401, single-flight concurrent refreshes, retry once, and mark the session expired on failure.
- [x] Use `/api/v1/auth/logout` and `/api/v1/auth/logout-all`, clear memory on logout, and retain login error/return-path rendering.
- [x] Remove CSRF/session-token assumptions from UI calls while preserving step-up request UX.
- [x] Remove the `/logout` session rewrite and test the `/api/v1/auth/*` path through the existing `/api/:path*` rewrite.

## Chunk 5: Verification and delivery

### Task 10: Run focused and full verification

**Files:**
- Modify: `compose.yaml`, `compose.dev.yaml`, `compose.staging.credentialed.yaml`, `.env.example`, `.env.staging.example`, `scripts/test-local-stack.sh`, and deployment docs only as required by the new secret/cookie contract.

- [ ] Run the staging deploy/rollback drill where Docker is available; backend `clean verify`, dashboard tests/build, `pytest`, and local-stack contract are complete.
- [x] Add the signing-secret wiring to local/staging/prod-like compose without logging or baking secrets into images.
- [x] Require `AUTH_TOKEN_SIGNING_SECRET` at staging/production startup, keep it identical across replicas, remove obsolete `SERVER_SERVLET_SESSION_COOKIE_*` settings, and smoke auth cookie/origin/JSESSIONID behavior.
- [x] Update `ReleaseWorkflowE2EIntegrationTest.java`, `ReleaseSecurityRegressionIntegrationTest.java`, and all `/api/v1/session` test helpers from CSRF/session bootstrap to Bearer bootstrap.

### Task 11: Independent security review and fix pass

- [x] Run independent `code-reviewer` and `architect` lanes against the complete diff.
- [x] Fix every CRITICAL/HIGH finding and rerun the affected tests; record any WATCH concern in the final synthesis.

### Task 12: Production deployment validation and delivery

- [ ] Validate the production image/config path, Flyway migration, readiness, cookie policy, mock OIDC callback, refresh/reuse/logout flows, and rollback path. The local drill was blocked by the execution approval quota.
- [x] Record the production target/credential gap instead of claiming production success; no external production credentials or target were available.
- [ ] Commit in the repository's Korean format, squash merge into `design/modular-monolith-architecture`, push, and confirm CI status.
