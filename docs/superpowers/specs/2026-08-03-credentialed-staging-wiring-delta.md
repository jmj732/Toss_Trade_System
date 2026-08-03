# Credentialed staging wiring delta

## Decision deltas

- Keep `compose.mock.yaml` and `compose.staging.yaml` mock-only behavior unchanged.
- Add a separately invoked `compose.staging.credentialed.yaml` overlay. It contains only
  Doppler-backed interpolation and real provider contract wiring; it stores no secret value.
- Use official endpoints and response fields for FMP stable quote, FRED observations, SEC EDGAR
  submissions, and Gemini model metadata. The preflight prints only provider/status/freshness/
  degrade labels; raw responses and credentials stay in temporary files and are deleted.
- Set `BROKER_CREDENTIALS_ENABLED=true` and the encrypted vault key so Toss onboarding routes
  exist. Do not configure Toss client ID/secret in environment variables; onboarding request
  bodies are persisted through the existing encrypted vault path.
- Force `REAL_ORDER_ENABLED=false` and `REAL_ORDER_CANARY_ENABLED=false`. Preflight must report
  live order activation blocked until a registered Toss credential is validated and later safety
  gates are deliberately enabled.
- Load Doppler `trade/staging` through `scripts/credentialed-staging-preflight.sh`; Compose
  config validation and provider probes are the credentialed preflight. It does not start a
  production-like stack or place orders.

## Verification boundary

- Static contract test covers override separation, endpoint/auth field wiring, Doppler loading,
  no Toss client-secret env, and live-order-off defaults.
- Targeted backend/provider tests, shell contract, full Maven/pytest/dashboard checks, secret
  scan, and the credentialed preflight are run before merge. Missing Doppler credentials or
  unavailable external APIs remain explicit validation gaps; no secret is copied into evidence.
