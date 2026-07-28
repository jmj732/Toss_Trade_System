# Web Dashboard Foundation Delta Spec

- Add one `web-dashboard` Next.js App Router application.
- Keep Spring Boot as OIDC, authorization, CSRF, and `HttpSession` owner.
- Next.js proxies same-origin `/api`, `/oauth2`, `/login/oauth2`, and `/logout`
  paths to Spring Boot; it does not terminate OIDC or mint tokens.
- The Spring OIDC registration redirect URI targets the dashboard's public
  `/login/oauth2/code/{registrationId}` callback; forwarded headers stay off.
- `/auth/login` redirects to the configured Spring OIDC registration.
- Add authenticated `GET /api/v1/session` returning internal user UUID plus the
  CSRF header name and token required by browser mutations.
- Never return OIDC access/ID tokens or Toss credentials from the session API.
- The browser stores no broker, OIDC, or application bearer token.
- The foundation accepts a broker connection UUID and reads the existing
  dashboard read model for that owned connection.
- One page renders portfolio, analysis, pending events, and pending proposals.
- Every section visibly renders `stale`, `unknown`, and `unavailable` quality.
- Portfolio shows account totals, positions, and separate KRW/USD buying power.
- Analysis shows currency totals and position weights.
- Events remain read-only.
- Proposal approval/cancellation calls only the existing channel-neutral
  endpoints with `channel=WEB`, CSRF, and a fresh idempotency key.
- No direct submit endpoint, broker credential UI, state library, UI kit,
  WebSocket, polling, or new backend read model.
- Tests cover session response, frontend API paths/headers/bodies, quality
  rendering, login routing, and production build.
