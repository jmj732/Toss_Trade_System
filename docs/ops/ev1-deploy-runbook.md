# EV-1 production deployment runbook

## Production shape

EV-1 is a separate, read-only Compose service. It has no database, broker, order, approval, or risk-path dependency.

- Image: `scripts/ev1/Dockerfile`; runner: `scripts/ev1-runner.sh`.
- State volume: named Compose volume `ev1-data`, mounted at `/var/lib/ev1`.
- Recorder manifest: `/var/lib/ev1/forward/manifest.json`.
- Yahoo daily files: `/var/lib/ev1/prices`; the runner refreshes them through the public chart endpoint.
- Backend reads the manifest read-only at `/var/lib/ev1/forward/manifest.json` and exposes MCP tool `get_ev1_status` with `connector:read` only.
- The base service has the `ev1` profile, so local default `docker compose up` does not start it. Credentialed staging explicitly supplies `SEC_USER_AGENT` from Doppler.

The recorder persists a non-secret SEC CIK/SIC mapping in `universe-metadata.json` on first start when the legacy full SEC manifest is absent. It uses only SEC `company_tickers.json` and issuer submissions for that mapping; later XBRL/companyfacts is never used as an event signal. Existing accession-keyed events, raw hashes, `observed_at`, parser status, controls, and metrics remain in the manifest and resume after restart.

## PR → main → CD flow

1. Create a feature branch and PR against `main`.
2. Run applicable CI gates, including the offline EV-1 tests and Compose/CD static contract test.
3. Review the service profile, volume, healthcheck, Doppler reference, read-only MCP scope, and rollback plan. Production merge remains human-approved.
4. Merge the PR into `main` only after review and required checks pass.
5. The existing `.github/workflows/cd.yml` runs after successful CI on the `main` push. It builds/loads backend, analysis, dashboard, and EV-1 images tagged with the commit SHA, streams each image through the existing pinned SSH path, and transfers the three Compose files.
6. On the host, CD runs the existing pinned command with server-side Doppler:

   ```sh
   doppler run --project trade --config stg -- env \
     BACKEND_IMAGE=trade-backend:<commit> \
     ANALYSIS_IMAGE=trade-analysis:<commit> \
     DASHBOARD_IMAGE=trade-dashboard:<commit> \
     EV1_IMAGE=trade-ev1:<commit> \
     docker compose -f compose.yaml -f compose.staging.yaml \
       -f compose.staging.credentialed.yaml --profile ev1 \
       up --no-build -d --wait migrate backend dashboard ev1
   ```

   Doppler supplies `SEC_USER_AGENT`; no credential is written to YAML, the workflow, the image, or the manifest.
7. CD waits for Compose health and checks backend readiness plus the EV-1 status command. It prunes unused EV-1 image archives/images using the same cleanup path as the other images.

## Health and status commands

On the deployment host, use the service health and read-only status command. Do not read or print secret environment values.

```sh
docker compose -f compose.yaml -f compose.staging.yaml \
  -f compose.staging.credentialed.yaml --profile ev1 ps ev1

docker compose -f compose.yaml -f compose.staging.yaml \
  -f compose.staging.credentialed.yaml --profile ev1 exec -T ev1 \
  python3 /app/scripts/ev1-status.py \
  --manifest /var/lib/ev1/forward/manifest.json \
  --runner-status /var/lib/ev1/runner-health.json --max-age-seconds 3600
```

A healthy status has `status=healthy`, `health=green`, `error_count=0`, a recent `last_poll`, a metrics object, and `runner_status.status=healthy` when the marker exists. A runner marker with `status=error` makes the result red even if the manifest itself is recent; this surfaces Yahoo/SEC dependency failures without rewriting event data. `NO_DATA` before the first future event is valid and must not be changed to a zero return. The MCP equivalent is the authenticated read-only `get_ev1_status` tool; it never refreshes providers or writes state.

## First-event verification

After the first post-cutoff event, verify the manifest contains:

- SEC acceptance timestamp and recorder `observed_at` separately.
- Raw SEC document hashes and parser status.
- EPS_STRONG25 and CORE_EARNINGS classification, with material concurrent exclusions preserved.
- Ready time, entry bar, primary 5-day and secondary 3/10/20-day exits.
- 30/40/70bp net returns and matched same-date SIC2/liquidity-tercile controls.
- No duplicate accession after a second poll or service restart.
- No broker, order, approval, or risk endpoint activity.

## Restart, recovery, and rollback

- Restart the service without removing `ev1-data`; the manifest and raw evidence are the resume point.
- SEC/Yahoo transient failures remain recorded and are retried on later polls; do not replace missing values with synthetic bars.
- A malformed or stale manifest is unhealthy and must be investigated, not deleted.
- Roll back the image and Compose revision together using the existing CD rollback procedure. Keep the named `ev1-data` volume unless a separately approved data-recovery action exists.
- After rollback, rerun the status command and compare the accession set and manifest hash before and after. Never roll back by creating an empty manifest.

## Boundaries

- EV-1 preregistration remains `claudedocs/event-independent-validation-registration.md`.
- No parameter changes, new strategy, DB migration, order-system change, paper/live order, or approval bypass is part of this runbook.
- Do not deploy or merge without human approval.
