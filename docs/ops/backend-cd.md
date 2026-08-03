# Backend CD

The backend CD job lives in `.github/workflows/release-gates.yml`. A push to
`design/modular-monolith-architecture` deploys only after Spring, analysis, dashboard,
audit, and mock-stack gates pass. Deployments are serialized and an active deploy is not
cancelled by a newer push.

## One-time server setup

The target host must already have:

- Docker Engine and Docker Compose v2.
- A checkout at the deploy path containing `compose.yaml`, `compose.staging.yaml`, and
  `compose.staging.credentialed.yaml`.
- Doppler CLI authenticated on the host for project `trade`, config `staging`.
- The existing database secret file and Doppler-backed provider/OIDC values. These stay on
  the server and are never sent through GitHub Actions.
- The reverse proxy forwarding the public HTTPS host to the backend's loopback port 8080.

The server-side command used by CD is equivalent to:

```sh
doppler run --project trade --config staging -- env BACKEND_IMAGE=trade-backend:<commit> \
  docker compose -f compose.yaml -f compose.staging.yaml \
    -f compose.staging.credentialed.yaml up -d --wait migrate backend
```

The image is streamed over SSH and loaded on the host, so no registry token is needed.

## GitHub Secrets

Add these repository or environment secrets before enabling the first deploy:

| Secret | Value |
|---|---|
| `BACKEND_DEPLOY_HOST` | Public server hostname or IP |
| `BACKEND_DEPLOY_PORT` | SSH port, for example `2222` |
| `BACKEND_DEPLOY_USER` | Dedicated deploy user, not root |
| `BACKEND_DEPLOY_PATH` | Server checkout path, for example `/opt/trade` |
| `BACKEND_DEPLOY_SSH_KEY` | Private key for the deploy user |
| `BACKEND_DEPLOY_KNOWN_HOSTS` | Pinned output for the host from a trusted `ssh-keyscan -H` |

The workflow uses `StrictHostKeyChecking=yes`, `IdentitiesOnly=yes`, and the supplied pinned
known-hosts file. It does not log the key, provider credentials, Doppler token, or Compose
environment.

## Rollback

Each deployment keeps the commit-tagged Docker image on the server. Roll back by selecting a
known-good commit tag for `BACKEND_IMAGE` and repeating the server-side Compose command. Do
not rebuild from an unverified working tree.
