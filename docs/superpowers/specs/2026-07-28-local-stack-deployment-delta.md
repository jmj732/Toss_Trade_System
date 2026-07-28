# Local Stack Deployment Delta Spec

## Decision deltas

- Add root `compose.yaml` for PostgreSQL, Redis, Spring, FastAPI, and Next.js.
- Keep Spring as ledger/auth owner; FastAPI remains analysis-only.
- Add `compose.dev.yaml` for real OIDC/Toss endpoints and `compose.mock.yaml`
  for local mock endpoints. Base stack contains neither external integration.
- Prefer Compose overlays over one conditional file: mock and real endpoints
  cannot be accidentally mixed.
- Use official PostgreSQL, Redis, WireMock, and NAV mock OAuth2 images.
- Add minimal multi-stage Dockerfiles; runtime images receive secrets only via
  environment variables.
- Add Spring Actuator readiness; readiness includes PostgreSQL and Redis.
- Add process-only readiness routes for FastAPI and Next.js.
- Use existing Spring analysis/Toss timeout properties; expose them as env vars.
- Apply `restart: unless-stopped`; dependency health gates startup ordering.
- Require secrets through `.env`; commit examples with placeholders only.
- Exclude `.env*` from Git and Docker contexts, except explicit examples.
- Toss mock exposes only OAuth plus existing read-only adapter endpoints.
- Add one shell E2E smoke test: build, start mock overlay, wait, probe all
  readiness endpoints, verify unauthenticated API rejection, then tear down.
- No Kubernetes, reverse proxy, TLS termination, observability stack, seed
  framework, production deployment config, or Toss order endpoint.

## Rejected

- App-embedded mocks: violates FastAPI analysis-only boundary.
- One Compose file with toggles: makes real/mock endpoint mixing easy.

## Plan

- [ ] Write failing local-stack contract test.
- [ ] Add secret/image/Git exclusions and env examples.
- [ ] Add service Dockerfiles.
- [ ] Add health/readiness endpoints and config.
- [ ] Add base, dev, and mock Compose files.
- [ ] Add read-only Toss mock mappings.
- [ ] Add and run E2E smoke test.
- [ ] Run one review and fix required findings.
- [ ] Run full verification, commit, squash merge, and push.
