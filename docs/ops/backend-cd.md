# Backend CD

The backend CD job lives in `.github/workflows/release-gates.yml`. A push to
`design/modular-monolith-architecture` deploys only after Spring, analysis, dashboard,
audit, and mock-stack gates pass. Deployments are serialized and an active deploy is not
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
    -f compose.staging.credentialed.yaml up -d --wait migrate backend
```

The backend, analysis, and dashboard images are built from the verified checkout, streamed
over SSH, and loaded on the host, so no registry token or remote source checkout is needed.

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

The workflow uses `StrictHostKeyChecking=yes`, `IdentitiesOnly=yes`, and the supplied pinned
known-hosts file. It does not log the key, provider credentials, Doppler token, or Compose
environment.

## Rollback

Each deployment keeps the commit-tagged Docker image on the server. Roll back by selecting a
known-good commit tag for `BACKEND_IMAGE` and repeating the server-side Compose command. Do
not rebuild from an unverified working tree.
