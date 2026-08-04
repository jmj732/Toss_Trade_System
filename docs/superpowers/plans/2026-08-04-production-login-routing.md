# Production Login Routing Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make OAuth success, failure, login, and expired-session routing safe and usable on staging and production dashboards.

**Architecture:** Spring remains the OAuth/session owner. A validated public dashboard URL builds all backend redirects, while a state-keyed session map carries only a validated relative dashboard path through the OAuth round trip. Next.js exposes a real `/login` page and builds same-origin backend authorization links without accepting external return URLs.

**Tech Stack:** Java 21, Spring Boot/Security 4.1/7.1, Next.js 16 App Router, React 19 without new dependencies, Node `node:test`.

---

## Chunk 1: Lock the routing contract

### Task 1: Add backend regression tests

**Files:**
- Modify: `trading-backend/src/test/java/com/jmj/trade/security/SecurityConfigurationTest.java`
- Create: `trading-backend/src/test/java/com/jmj/trade/security/LoginRedirectControllerTest.java`

- [ ] **Step 1: Write failing tests** for safe configured dashboard URLs, `/login` redirect output, state-keyed success return-to, invalid state fallback, access-denied error mapping, and malicious return-path rejection.
- [ ] Update the existing `OidcAuthSessionIntegrationTest` and `BrokerConnectionControllerIntegrationTest` assertions that currently expect `/login` 404; assert the configured dashboard redirect instead, while keeping expired `/api/**` requests at 401.
- [ ] **Step 2: Run `cd trading-backend && ./mvnw -q -Dtest='com.jmj.trade.security.SecurityConfigurationTest,com.jmj.trade.security.LoginRedirectControllerTest' test` and confirm the new assertions fail for the missing behavior.

### Task 2: Add dashboard regression tests

**Files:**
- Modify: `web-dashboard/test/login.test.mjs`
- Create: `web-dashboard/test/login-page.test.mjs`
- Modify: `web-dashboard/test/api.test.mjs`

- [ ] **Step 1: Write failing tests** for safe authorization URLs, malicious `returnTo` rejection, error-specific login rendering, session-expiry return-to links, and callback rewrites to Spring.
- [ ] **Step 2: Run `cd web-dashboard && node --test test/login.test.mjs test/login-page.test.mjs test/api.test.mjs` and confirm the new assertions fail for the missing route/UI behavior.

## Chunk 2: Implement backend routing

### Task 3: Centralize and validate dashboard redirects

**Files:**
- Create: `trading-backend/src/main/java/com/jmj/trade/security/DashboardRedirects.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/security/DashboardAuthorizationRequestResolver.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/security/SecurityConfiguration.java`
- Create: `trading-backend/src/main/java/com/jmj/trade/security/LoginRedirectController.java`

- [ ] **Step 1:** Implement absolute HTTP(S) public URL validation and allowlisted relative `returnTo` normalization.
- [ ] **Step 2:** Implement state-keyed session storage for `returnTo`, bounded to 8 concurrent login attempts, and consume-once behavior. Read `returnTo` before the resolver generates/stores OAuth state; never replace Spring's own authorization-request repository.
- [ ] **Step 3:** Route backend `/login` and OAuth failures to configured dashboard `/login`; map only safe error codes and use the configured dashboard root after success when no valid target exists.
- [ ] **Step 4:** Configure the resolver and handlers while preserving 401 responses for `/api/**`.
- [ ] **Step 5:** Run the targeted backend tests and make them pass.

## Chunk 3: Implement dashboard login surface

### Task 4: Add safe authorization URL and login page

**Files:**
- Modify: `web-dashboard/lib/login.js`
- Modify: `web-dashboard/app/auth/login/route.js`
- Create: `web-dashboard/app/login/page.js`
- Modify: `web-dashboard/app/page.js`
- Modify: `web-dashboard/app/route-workspace.js`
- Modify: `web-dashboard/app/globals.css`
- Modify: `web-dashboard/next.config.js`

- [ ] **Step 1:** Add same-origin relative-path normalization and authorization URL construction.
- [ ] **Step 2:** Render `/login` error messages and retry links to `/oauth2/authorization/{registrationId}` while preserving a safe `returnTo` path.
- [ ] **Step 3:** Link signed-out root and route-workspace states to `/login` with the current requested path, covering expired sessions.
- [ ] **Step 4:** Keep OAuth callback errors on the Spring rewrite so backend failure mapping and state consumption remain authoritative.
- [ ] **Step 5:** Run dashboard targeted tests, full `npm test`, and `npm run build`.

## Chunk 4: Environment and review

### Task 5: Align environment documentation and review the diff

**Files:**
- Modify: `.env.staging.example`
- Review: `compose.dev.yaml`, `compose.staging.credentialed.yaml`, `.github/workflows/release-gates.yml`

- [ ] **Step 1:** Document the staging public dashboard value while retaining `PUBLIC_DASHBOARD_URL` as the single source used by Spring. Use `http://localhost:3000` only as the validated local default; a blank or malformed configured value fails closed at startup even when OIDC is not configured.
- [ ] **Step 2:** Run a security-focused review for open redirects, leaked OAuth/provider errors, and accidental API redirect behavior.
- [ ] **Step 3:** Run `./scripts/test-local-stack.sh` and any relevant static checks.

## Chunk 5: Full verify and production handoff

### Task 6: Verify and deploy

- [ ] **Step 1:** Run backend `./mvnw clean verify`, analysis `pytest -q`, dashboard `npm test`, dashboard `npm run build`, and local-stack smoke checks.
- [ ] **Step 2:** Deploy the verified backend through `.github/workflows/release-gates.yml` on `design/modular-monolith-architecture` and deploy `web-dashboard/` with the installed Vercel project/environment (`vercel deploy --prod`, using existing Vercel auth/project linkage if present).
- [ ] **Step 3:** Verify production browser login success, provider denial/failure, invalid/expired state fallback, and session-expiry retry behavior without exposing credentials.
- [ ] **Step 4:** Re-read the final diff/status, squash merge into `design/modular-monolith-architecture`, push, and confirm remote CI/CD state.
