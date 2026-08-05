# Staging Deploy and Rollback

## Scope

- `compose.staging.yaml` is a production-like overlay on `compose.yaml`. The existing mock
  profile layers on `compose.mock.yaml`, **never** `compose.dev.yaml`; it remains credential-free
  for OIDC/Toss and is the rollback-drill profile.
- Deploy:

  ```sh
  docker compose -f compose.yaml -f compose.mock.yaml -f compose.staging.yaml \
    --env-file .env.staging up -d --wait
  ```

- `.env.staging` (copy from `.env.staging.example`) pins `BACKEND_IMAGE` / `ANALYSIS_IMAGE` /
  `DASHBOARD_IMAGE` to registry tags and points `STAGING_DB_PASSWORD_FILE` at a local file. A
  real deploy pushes those tags to a registry ahead of time and never passes `--build`; `up`
  reuses whatever is already tagged.

## Mock staging secrets

- The real secret values in this stack are the Postgres password, the broker credential
  encryption key, and `AUTH_TOKEN_SIGNING_SECRET`.
- `AUTH_TOKEN_SIGNING_SECRET` is required in staging, must be at least 32 bytes, and must be
  identical across backend replicas. The backend fails closed when it is missing; it is not
  committed, baked into an image, or printed by the deploy scripts.
- The Postgres password is never a plain environment variable in staging. It's injected as a
  file: Postgres itself via its own `POSTGRES_PASSWORD_FILE` support, and the backend via
  Spring Boot's **Config Tree** feature (`spring.config.import=optional:configtree:/run/secrets/`)
  reading the same file mounted at `/run/secrets/spring.datasource.password` — the filename
  *is* the Spring property key. Confirmed empirically: with no `POSTGRES_PASSWORD` /
  `DATABASE_PASSWORD` set anywhere, the backend connects using only the config-tree file, and
  neither `docker inspect` nor `docker compose config` ever shows the real value.
- `compose.yaml`'s `POSTGRES_PASSWORD` / `DATABASE_PASSWORD` were loosened from a hard
  `${VAR:?required}` to `${VAR:-}` (optional) specifically so this works — Compose
  interpolates each file's own `${...:?}` expressions before merging overlays, so there is no
  way for an overlay to override away a required-variable failure in the base file. Every
  other caller (local dev, mock, CI, the other drill scripts) always sets the variable
  explicitly already, so this is a no-op for them.
- The broker credential key (`BROKER_CREDENTIALS_KEYS_1` / `CREDENTIAL_KEY_BASE64`) stays a
  plain environment variable. Spring Boot's Config Tree binds scalar `String` properties
  cleanly (verified), but does **not** bind into the `Map<Integer,String>` this key is part
  of via either a flat dotted filename or a nested directory layout (also verified, two real
  attempts, both failed with `active credential key version is missing`). Changing the map's
  key type to accommodate this would touch application source code broader than this
  delta's scope. It remains at least as secure as every prior delta's handling of it: never
  committed, never baked into an image layer, set only at the container level.

## Credentialed staging

Credentialed staging is a separate, opt-in overlay. It uses Doppler `trade/staging` and never
layers on `compose.mock.yaml`:

```sh
./scripts/credentialed-staging-preflight.sh
doppler run --project trade --config staging -- docker compose \
  -f compose.yaml -f compose.staging.yaml -f compose.staging.credentialed.yaml up -d --wait
```

The overlay wires the official FMP, FRED, SEC, and Gemini endpoints and fields. Provider keys,
SEC User-Agent, OIDC values, the database password file, and the credential-vault key come from
Doppler or the mounted secret file; no value is committed or printed. Toss client credentials
are accepted only through the authenticated onboarding endpoint and encrypted in the vault —
there is no Toss client-secret environment variable. `REAL_ORDER_ENABLED` and
`REAL_ORDER_CANARY_ENABLED` are explicitly false until onboarding and separate order activation
are completed.

## Migration runs exactly once

- A one-shot `migrate` service (same pinned backend image, different entrypoint — see
  `com.jmj.trade.migrate.MigrateOnlyApplication`) runs Flyway and exits before `backend`
  starts. `backend` itself has `SPRING_FLYWAY_ENABLED=false`, so scaling `backend` to
  multiple replicas never causes concurrent migration attempts.
- Why not just set `spring.main.web-application-type=none` on the real app? Verified this
  breaks: `SecurityConfiguration`'s `securityFilterChain` bean requires a servlet web
  context's `HttpSecurity` bean, which doesn't exist in a non-web run — the container exits
  1, not 0. `MigrateOnlyApplication` lives in its own package specifically so
  `@SpringBootApplication`'s component scan never reaches `SecurityConfiguration` or anything
  else that needs a web context; only `DataSourceAutoConfiguration` +
  `FlywayAutoConfiguration` run.
- It's launched via `-Dloader.main=com.jmj.trade.migrate.MigrateOnlyApplication -cp app.jar
  org.springframework.boot.loader.launch.PropertiesLauncher` — the default `JarLauncher`
  ignores `-Dloader.main` entirely (that override only works with `PropertiesLauncher`);
  `pom.xml` pins the packaged jar's default `Start-Class` to the real application so
  `spring-boot:repackage` doesn't fail on having two `@SpringBootApplication` classes.

## Secure cookies and proxy headers

- `SERVER_FORWARD_HEADERS_STRATEGY=framework` makes the backend trust `X-Forwarded-Proto` /
  `X-Forwarded-Host` from the reverse proxy that terminates TLS in front of it.
- Refresh tokens are stored only in the `trade_refresh_token` cookie, with `Secure`,
  `HttpOnly`, `SameSite=Strict`, and `Path=/api/v1/auth`; the plaintext value is never stored
  in the database. Refresh and logout also require the exact configured dashboard `Origin`.
- OIDC authorization transactions use a separate signed, short-lived `trade_oidc_request`
  cookie with `Secure`, `HttpOnly`, `SameSite=Lax`, and no server session.
- There is no JSESSIONID/session-cookie dependency. Access tokens are short-lived Bearer
  tokens kept by the dashboard only in module memory.
- Verified with a real request carrying `X-Forwarded-Proto: https`: the response's
  security headers reflect HTTPS, confirming forwarded-header trust is taking effect.

## Resource limits

- Every service has `deploy.resources.limits` (memory + cpus), applied by plain
  `docker compose up` without Swarm mode. Verified via `docker inspect`
  (`HostConfig.Memory` / `HostConfig.NanoCpus`) matching the configured defaults exactly.

## Deploy/rollback drill (automated)

```sh
./scripts/staging-deploy-rollback-drill.sh
```

Builds the backend image once, tags it twice (standing in for two already-pushed release
tags), deploys tag A, confirms `/actuator/health/readiness` is `200`, "deploys" tag B by
switching `BACKEND_IMAGE` only (no rebuild), confirms healthy, then rolls back to tag A the
same way and confirms healthy again. This is what to run before trusting that a real
tag-pinned deploy/rollback will work operationally. Exits `0` and prints
`staging deploy/rollback drill: PASS` on success; on failure it dumps the stack's container
logs and exits non-zero.

## What this does not cover

- No real registry push, no orchestrator (Kubernetes/Swarm/ECS), no CI wiring for staging
  deploys, no autoscaling, no canary/blue-green strategy — this is the compose-level
  mechanics only.
- The mock rollback profile does not cover real OIDC/provider connectivity or Toss onboarding;
  use the credentialed overlay and preflight for that separate environment.
