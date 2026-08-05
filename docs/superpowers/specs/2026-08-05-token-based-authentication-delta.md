# Token-based authentication delta

## Goal

Replace application session authentication with OIDC-issued short-lived access tokens and
rotating refresh-token sessions. The browser keeps the access token only in JavaScript memory;
the refresh token is a Secure, HttpOnly, SameSite cookie and only its SHA-256 hash is persisted.

## Decisions

- OIDC authorization-code login remains the identity proof. The callback creates an internal user
  mapping, issues an access token, rotates/creates one refresh session, and redirects to the
  validated dashboard path with the access token in the URL fragment. The dashboard consumes the
  fragment immediately and removes it with `history.replaceState`.
- Access tokens are signed HS256 tokens with `sub` (internal UUID), `sid` (refresh session UUID),
  `iat`, `exp`, `auth_time`, and `amr=oidc`. They expire after five minutes by default and are
  validated by a stateless Bearer filter. The signing secret is environment supplied in staging
  and production.
- Refresh sessions are stored in `auth_refresh_sessions`: session UUID, user UUID, family UUID,
  token hash, issued/used/expiry timestamps, replacement hash, and revocation/reuse timestamps.
  A database row lock makes rotation atomic. Reuse of a rotated or revoked token revokes the
  entire family. Expired sessions are rejected and revoked.
- `POST /api/v1/auth/refresh` rotates the cookie token and returns a new access token. It accepts
  no refresh token in the body or authorization header. `POST /api/v1/auth/logout` revokes the
  current refresh session; `POST /api/v1/auth/logout-all` revokes every session for the bearer
  user. Both logout forms clear the refresh cookie.
- Refresh and logout require an exact configured dashboard `Origin`; missing or foreign origins
  are rejected. The refresh cookie is `Secure; HttpOnly; SameSite=Strict; Path=/api/v1/auth`.
  The OAuth authorization-request cookie is separate, signed, HttpOnly, Secure, and SameSite=Lax
  because the provider callback is a cross-site navigation.
- CSRF and `JSESSIONID` application authentication are removed. The API is stateless and accepts
  only Bearer access tokens (the existing prediction ingestion API-key filter remains its own
  non-user integration path). All browser API calls use the in-memory access token.
- Existing controllers continue to receive the internal UUID as `Principal.getName()`. Step-up
  operations read `auth_time`/`amr` from the token principal and fail closed unless the OIDC
  reauthentication is fresh. Refresh preserves the original `auth_time`, so a stale session must
  perform OIDC again. No direct order placement or credential operation bypass is introduced.
- The dashboard retries one failed API request once after a single-flight refresh. It never uses
  localStorage, sessionStorage, cookies readable from JavaScript, or a second API retry loop.

## API contract

| Endpoint | Auth | Result |
|---|---|---|
| OIDC success callback | OIDC code | refresh cookie + dashboard redirect with fragment access token |
| `GET /api/v1/session` | Bearer access token | internal user ID and token authentication metadata |
| `POST /api/v1/auth/refresh` | refresh cookie + exact Origin | rotated access token + rotated refresh cookie |
| `POST /api/v1/auth/logout` | refresh cookie + exact Origin | current session revoked, cookie cleared, 204 |
| `POST /api/v1/auth/logout-all` | Bearer + refresh cookie + exact Origin | all user sessions revoked, cookie cleared, 204 |

## Acceptance and test scenarios

- OIDC success/failure/callback and safe return-path behavior remain covered; success produces no
  `JSESSIONID` and the access token is never in a query parameter.
- Missing, malformed, expired, wrong-signature, wrong-type, and wrong-user Bearer tokens return
  401; ordinary API state changes do not require CSRF.
- Refresh rotation returns a different token, persists no plaintext, rejects expired tokens,
  revokes the session on logout, revokes all sessions on global logout, and family-revokes on
  reuse.
- Two concurrent refresh requests cannot both succeed; the loser is treated as token reuse.
- Refresh/logout reject missing and foreign origins and emit the required cookie attributes.
- Step-up still requires fresh OIDC authentication; credentials, real-order, and API-key paths
  remain protected by that check.
- Dashboard consumes the callback fragment, sends Bearer headers, refreshes once on 401, retries
  the original request, returns to the requested path, and clears memory on logout.

## Rollout and rollback

The migration is additive and cleanup is application-level: deploy the new schema before the
application, set `AUTH_TOKEN_SIGNING_SECRET` consistently on every backend replica, and verify
readiness plus the mock OIDC/browser flow. Rollback uses the prior image and leaves the additive
refresh-session table unused; existing application sessions are intentionally not supported after
this branch.
