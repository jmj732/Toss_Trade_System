#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/backend-cd.yml"

fail() {
  echo "backend CD contract: FAIL: $1" >&2
  exit 1
}

test -f "$workflow" || fail "missing $workflow"

grep -q 'workflow_run:' "$workflow" || fail "CD must be triggered by a completed workflow"
grep -q 'workflows: \["Release Gates"\]' "$workflow" ||
  fail "CD must follow the Release Gates workflow"
grep -q "github.event.workflow_run.conclusion == 'success'" "$workflow" ||
  fail "CD must require a successful release gate run"
grep -q 'cancel-in-progress: false' "$workflow" ||
  fail "an active backend deploy must not be cancelled by a newer push"
grep -q 'docker save' "$workflow" || fail "backend image must transfer without a registry secret"
grep -q 'BACKEND_DEPLOY_PORT' "$workflow" || fail "SSH port must be configurable"
grep -q -- '-p \"\$BACKEND_DEPLOY_PORT\"' "$workflow" || fail "SSH must use the configured port"
grep -q 'StrictHostKeyChecking=yes' "$workflow" || fail "SSH host verification must stay enabled"
grep -q 'UserKnownHostsFile=' "$workflow" || fail "SSH must use pinned known hosts"
grep -q 'doppler run --project trade --config staging' "$workflow" ||
  fail "server deploy must load trade/staging through Doppler"
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
