# CI Release Gates Delta

## Scope

- One GitHub Actions workflow (`.github/workflows/release-gates.yml`) runs the same
  verification already required manually: Spring backend (`./mvnw clean verify`), FastAPI
  analysis (`pytest`), Next.js dashboard (`npm test` + `npm run build`), `npm audit`, and the
  Compose mock-stack contract + smoke scripts.
- Triggers: `push` (any branch) and `workflow_dispatch`. No `pull_request` trigger — this repo
  has no PR workflow (direct push + squash merge to `design/modular-monolith-architecture`),
  and the instruction explicitly forbids depending on PR events.
- Compose smoke reuses `scripts/test-local-stack.sh` and `scripts/smoke-local-stack.sh`
  unchanged; both already build only `compose.yaml` + `compose.mock.yaml` and generate random
  secrets locally (`openssl rand`), so the workflow never references `secrets.*` for OIDC or
  Toss and never touches `compose.dev.yaml` (the real-provider config).
- No deploy step, no registry push, no environment.

## Minimal design

- Five parallel jobs, each independently checkoutable and cacheable:
  `spring-backend`, `fastapi-analysis`, `nextjs-dashboard`, `npm-audit`, `compose-smoke`.
- `spring-backend`: `actions/setup-java@v4` (temurin 21, `cache: maven`), `./mvnw clean verify`
  in `trading-backend/`; uploads `trading-backend/target/surefire-reports/` with
  `if: always()`.
- `fastapi-analysis`: `actions/setup-python@v5` (3.12, `cache: pip`), `pip install -e ".[test]"`,
  `pytest -q --junitxml=pytest-results.xml`; uploads the junit file with `if: always()`.
- `nextjs-dashboard`: `actions/setup-node@v4` (22, `cache: npm`), `npm ci`, `npm test`,
  `npm run build`; uploads captured test/build logs with `if: always()`.
- `npm-audit`: same Node setup, `npm ci`, `npm audit --omit=dev`.
- `compose-smoke`: no language setup (scripts are self-contained); runs both scripts, captures
  their output, uploads it with `if: always()`.
- Top-level `concurrency: group: ${{ github.workflow }}-${{ github.ref }}, cancel-in-progress:
  true` cancels superseded runs on the same ref.
- Top-level `permissions: contents: read` (no write access needed anywhere).
- Per-job `timeout-minutes` caps so a hang fails fast instead of burning CI hours.

## Plan

- [ ] Write the workflow file with all five jobs.
- [ ] Validate YAML syntax and job/step structure locally (`actionlint` if available, else
      manual structural review).
- [ ] Confirm no `pull_request` trigger and no `secrets.*` reference for OIDC/Toss exists.
- [ ] Perform exactly one review pass and fix findings.
- [ ] One feature commit, squash merge into base branch, push.
