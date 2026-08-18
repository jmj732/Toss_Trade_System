# Trade stack CD

The CD job lives in `.github/workflows/cd.yml`. A `web-dashboard/**` change is detected by
`.github/workflows/ci.yml`, runs the dashboard test/build and gate, then the CD workflow
builds and deploys the dashboard image after a successful push to `main`. The same deploy
also updates the Spring backend and analysis images from that verified commit. The separate
`deploy-vercel` job builds and promotes the same dashboard checkout to Vercel production after
the same successful CI completion. Deployments are serialized and an active deploy is not
cancelled by a newer push.

## One-time server setup

The target host must already have:

- Docker Engine and Docker Compose v2.
- A writable deploy path. CD syncs `compose.yaml`, `compose.staging.yaml`, and
  `compose.staging.credentialed.yaml` there on every deployment.
- Doppler CLI authenticated on the host for project `trade`, config `stg`.
  The deploy command also searches `$HOME/bin`, which supports a user-local CLI install.
- The existing database secret file and Doppler-backed provider/OIDC values. These stay on
  the server and are never sent through GitHub Actions.
- The reverse proxy forwarding the public HTTPS host to the backend's loopback port 8080.

The server-side command used by CD is equivalent to:

```sh
doppler run --project trade --config stg -- env \
  BACKEND_IMAGE=trade-backend:<commit> \
  ANALYSIS_IMAGE=trade-analysis:<commit> \
  DASHBOARD_IMAGE=trade-dashboard:<commit> \
  docker compose -f compose.yaml -f compose.staging.yaml \
    -f compose.staging.credentialed.yaml up --no-build -d --wait migrate backend dashboard
```

The backend, analysis, and dashboard images are built from the verified checkout, streamed
over SSH, and loaded on the host, so no registry token or remote source checkout is needed.
The deploy uses `--no-build` and removes unreferenced commit-tagged images, prunable untagged
parents, and transfer archives on both successful and failed exits; container image references
are preserved.

## GitHub Secrets

Add these repository or environment secrets before enabling the first deploy:

| Secret | Value |
|---|---|
| `BACKEND_DEPLOY_HOST` | Public server hostname or IP |
| `BACKEND_DEPLOY_PORT` | SSH port, for example `2222` |
| `BACKEND_DEPLOY_USER` | Dedicated deploy user, not root |
| `BACKEND_DEPLOY_PATH` | Writable server path for Compose files, for example `/home/deploy/trade` |
| `BACKEND_DEPLOY_SSH_KEY` | Private key for the deploy user |
| `BACKEND_DEPLOY_KNOWN_HOSTS` | Pinned output for the host from a trusted `ssh-keyscan -H` |
| `VERCEL_TOKEN` | Vercel production deployment token |
| `VERCEL_ORG_ID` | Vercel team/org ID for the linked project |
| `VERCEL_PROJECT_ID` | Vercel project ID for `web-dashboard` |

The workflow uses `StrictHostKeyChecking=yes`, `IdentitiesOnly=yes`, and the supplied pinned
known-hosts file. It does not log the key, provider credentials, Doppler token, or Compose
environment.

Disable Vercel's direct Git auto-deploy for this project. Otherwise Vercel can create a second
deployment that bypasses the repository's CI gate; the GitHub Actions `deploy-vercel` job is the
controlled production path.

## Rollback

Historical images are removed after deployment cleanup. Roll back by rerunning CD from a
known-good, verified commit so its images are rebuilt and transferred; do not rebuild from an
unverified working tree.
