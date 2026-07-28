# Staging Runtime Delta

## Scope

- `compose.staging.yaml`: production-like overlay on `compose.yaml` (+ `compose.mock.yaml`,
  never `compose.dev.yaml`). Pinned image tags (`image: ${BACKEND_IMAGE}` etc., `build:` kept
  as a local fallback so the drill script can produce a tag without a real registry), a
  one-shot `migrate` service so Flyway runs exactly once before any `backend` replica starts,
  secure-cookie and proxy-header settings, and per-service `deploy.resources.limits`.
- The Postgres password (the only genuinely file-injectable secret here) is supplied via
  Postgres's own `POSTGRES_PASSWORD_FILE` and the backend's `spring.datasource.password` via
  Spring Boot's **Config Tree** feature — both reading the same file. The broker credential
  key stays a plain container-level env var (see "Verified design decisions" below for why).
- Still mock-only: `PaperTradingBrokerAdapter` remains the only order path — no real order
  submission capability exists in this stack regardless of compose file.
- One deploy/rollback smoke drill script proves the operational mechanics: build once, tag
  twice, deploy tag A, verify healthy, "deploy" tag B, verify healthy, roll back to tag A by
  switching the tag reference only (no rebuild), verify healthy again.
- One ops runbook documents the manual procedure.
- No real registry push, no orchestrator, no CI wiring, no autoscaling — out of scope.

## Verified design decisions

Three assumptions were tested empirically against a real container, not just reasoned about,
because each one turned out to behave differently than the obvious guess:

1. **Config Tree binds `spring.datasource.password` (a `String`) but not
   `broker.credentials.keys.1` (a `Map<Integer,String>` entry)**, via either a flat dotted
   filename or a nested directory layout — both tested, both failed identically with
   `active credential key version is missing`. Changing the map's key type to work around
   this would touch application source beyond this delta's scope, so the credential key stays
   an env var — no worse than every prior delta's handling of it.
2. **A one-shot migrate container needs its own entrypoint, not just a property flag.**
   `spring.main.web-application-type=none` on the real app breaks: Spring Security's
   `securityFilterChain` bean needs a servlet web context's `HttpSecurity` bean, so the
   process exits 1. `com.jmj.trade.migrate.MigrateOnlyApplication` is a new, minimal
   `@SpringBootApplication` in its own package (component scan never reaches
   `SecurityConfiguration`), launched via `PropertiesLauncher` + `-Dloader.main=...` (the
   default `JarLauncher` silently ignores that override — confirmed it just booted the normal
   app instead). `pom.xml` gained one `<mainClass>` plugin config line so `spring-boot:repackage`
   doesn't fail on having two `@SpringBootApplication` classes.
3. **`compose.yaml`'s `${POSTGRES_PASSWORD:?required}` cannot be worked around from an
   overlay** — Compose interpolates each file's own required-variable expressions before
   merging, confirmed via two real `docker compose config` runs. Loosened to `${VAR:-}` in
   the shared base file; every existing caller already sets the variable explicitly, so this
   is a no-op for them and only staging takes advantage of the now-optional default.

All three were confirmed working together end-to-end: full stack up, `migrate` exits 0,
`backend` logs show zero Flyway activity (single execution confirmed), `docker inspect` /
`docker compose config` never show the real password, resource limits match
`HostConfig.Memory`/`NanoCpus` exactly, and a request with `X-Forwarded-Proto: https` gets
back `Secure; HttpOnly` on the session cookie plus a `Strict-Transport-Security` header
(proving the forwarded-header trust is live, not just configured).

## Plan

- [x] Verify Config Tree secret injection empirically before wiring the full compose file.
- [x] Verify the one-shot `migrate` container actually exits 0 after Flyway completes.
- [x] Write `compose.staging.yaml` and `.env.staging.example`.
- [x] Verify `deploy.resources.limits` actually apply (`docker inspect`).
- [x] Verify secure-cookie / forwarded-header behavior against a real request.
- [x] Write `scripts/staging-deploy-rollback-drill.sh`, run it locally, confirm PASS
      (including a mutation check that a broken readiness check correctly fails the drill).
- [x] Write `docs/ops/staging-deploy-rollback.md`.
- [ ] Perform exactly one review pass and fix findings.
- [ ] Run full verification (backend, analysis, dashboard, local-stack scripts, both drills).
- [ ] One feature commit, squash merge into base branch, push.
