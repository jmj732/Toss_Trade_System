#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/release-gates.yml"

fail() {
  echo "backend CD contract: FAIL: $1" >&2
  exit 1
}

test -f "$workflow" || fail "missing $workflow"

grep -q '^  backend-cd:' "$workflow" || fail "missing backend-cd job"
grep -q 'spring-backend' "$workflow" || fail "backend CD must depend on Spring gates"
grep -q 'fastapi-analysis' "$workflow" || fail "backend CD must depend on analysis gates"
grep -q 'nextjs-dashboard' "$workflow" || fail "backend CD must depend on dashboard gates"
grep -q 'npm-audit' "$workflow" || fail "backend CD must depend on audit gates"
grep -q 'compose-smoke' "$workflow" || fail "backend CD must depend on compose gates"
grep -q 'cancel-in-progress: false' "$workflow" ||
  fail "an active backend deploy must not be cancelled by a newer push"
grep -q 'docker build --pull --tag "\$ANALYSIS_IMAGE" analysis-service' "$workflow" ||
  fail "analysis image must be built in the verified checkout"
grep -q 'docker build --pull --tag "\$DASHBOARD_IMAGE"' "$workflow" ||
  fail "dashboard image must be built in the verified checkout"
grep -q 'docker save "\$IMAGE_TAG" "\$ANALYSIS_IMAGE" "\$DASHBOARD_IMAGE"' "$workflow" ||
  fail "all runtime images must transfer without a registry secret"
grep -q 'tar -czf - compose.yaml' "$workflow" ||
  fail "deploy must sync the compose files to the server"
grep -q 'tar -xzf - -C' "$workflow" ||
  fail "server must extract the synced compose files"
grep -q 'BACKEND_DEPLOY_PORT' "$workflow" || fail "SSH port must be configurable"
grep -q -- '-p \"\$BACKEND_DEPLOY_PORT\"' "$workflow" || fail "SSH must use the configured port"
grep -q 'StrictHostKeyChecking=yes' "$workflow" || fail "SSH host verification must stay enabled"
grep -q 'UserKnownHostsFile=' "$workflow" || fail "SSH must use pinned known hosts"
grep -q 'doppler run --project trade --config stg' "$workflow" ||
  fail "server deploy must load trade/stg through Doppler"
grep -q 'ANALYSIS_IMAGE="\$ANALYSIS_IMAGE"' "$workflow" ||
  fail "server deploy must select the transferred analysis image"
grep -q 'DASHBOARD_IMAGE="\$DASHBOARD_IMAGE"' "$workflow" ||
  fail "server deploy must select the transferred dashboard image"
grep -q 'PATH="\$HOME/bin:\$PATH"' "$workflow" ||
  fail "server deploy must find a user-local Doppler CLI"
grep -q 'compose.staging.credentialed.yaml' "$workflow" ||
  fail "server deploy must use the credentialed staging overlay"
grep -q 'migrate backend' "$workflow" || fail "server deploy must run migration before backend"
grep -q '/actuator/health/readiness' "$workflow" || fail "server deploy must verify readiness"
grep -q 'BACKEND_DEPLOY_SSH_KEY' "$workflow" || fail "SSH key must come from GitHub Secrets"
grep -q 'BACKEND_DEPLOY_KNOWN_HOSTS' "$workflow" ||
  fail "known hosts must come from GitHub Secrets"

if rg -n 'BEGIN (RSA|OPENSSH) PRIVATE KEY|ghp_[A-Za-z0-9]|DOPPLER_TOKEN=' \
  "$workflow" docs/ops/backend-cd.md docs/superpowers/specs/2026-08-03-backend-cd-delta.md >/dev/null; then
  fail "credential-like value found in CD configuration"
fi

echo "backend CD contract: PASS"
