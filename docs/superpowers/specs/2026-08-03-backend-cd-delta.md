# Backend CD delta

## Goal

Deploy the Spring backend to the user's own Docker host automatically after the existing
release gates pass on `design/modular-monolith-architecture`.

## Contract

- Keep provider and Toss secrets out of GitHub Actions, Git, image layers, and logs.
- Build the backend image in GitHub Actions and stream it over a pinned SSH connection; no
  registry credential is required on the server.
- On the server, run the existing Doppler `trade/staging` Compose path with
  `compose.yaml`, `compose.staging.yaml`, and `compose.staging.credentialed.yaml`.
- Run the existing one-shot Flyway `migrate` service before the backend and wait for backend
  readiness on `127.0.0.1:8080`.
- Deploy only after a successful `Release Gates` run for
  `design/modular-monolith-architecture`.
- Require server host, SSH port, user, deploy path, SSH private key, and pinned known-hosts
  content as GitHub Secrets. Doppler authentication remains server-side.

## Acceptance

- The CD job cannot run before all existing release-gate jobs pass.
- The workflow has no hard-coded credential values and never prints secret-bearing commands.
- A static contract check verifies the image transfer, Doppler Compose invocation, readiness
  check, and SSH hardening options.
- The workflow remains fail-closed: a missing secret, failed migration, failed Compose wait,
  or failed readiness check stops the deployment.
