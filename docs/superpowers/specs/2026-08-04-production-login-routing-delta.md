# Production login routing delta

## Background

The OAuth callback failure path currently depends on a dashboard root redirect and the
dashboard has no `/login` route. A failed callback can therefore land on a Vercel 404, while
the successful callback always discards the requested dashboard path.

## Decision deltas

- Treat `PUBLIC_DASHBOARD_URL` → Spring `public.dashboard-url` as the only environment-specific
  public dashboard source. Local/dev, staging, and production overlays must provide that same
  setting; application code contains no production hostname.
- Parse and validate the configured dashboard URL as an HTTP(S) origin with no user-info,
  query, or fragment. Reject malformed/open-redirect-like configuration at startup.
- Carry only a validated same-origin relative `returnTo` path through the OAuth authorization
  request. Store it in the backend session keyed by OAuth `state`; invalid or missing state
  falls back to the configured dashboard login page.
- Make backend `/login` and OAuth failures redirect to the configured dashboard `/login` with a
  small allowlisted error code. Successful authentication returns to the validated requested
  path, defaulting to the dashboard root.
- Add a real dashboard `/login` route. It renders safe messages for failed login, denied access,
  invalid/expired OAuth state, and expired application sessions; retry links point to the
  same-origin backend `/oauth2/authorization/{registrationId}` endpoint.
- Let OAuth callback errors rewrite to Spring so its failure handler maps the provider error and
  consumes the state-keyed return path; the dashboard `/login` route is the final error surface.

## Verification boundary

- Backend tests cover configured URL validation, safe `/login` redirects, successful return-to,
  invalid state, provider access denial, and the existing 401 session behavior.
- Dashboard tests cover relative authorization URL construction, malicious return paths, login
  error rendering, callback rewrites, and session-expiry login links.
- Full local verification remains backend `./mvnw clean verify`, analysis `pytest`, dashboard
  `npm test` + `npm run build`, local-stack contract/smoke checks, then production backend and
  Vercel deployment with real browser success/failure checks before squash merge and push.
