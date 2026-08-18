#!/usr/bin/env bash
set -euo pipefail

workflow=".github/workflows/cd.yml"
ci_workflow=".github/workflows/ci.yml"

fail() {
  echo "trade CD contract: FAIL: $1" >&2
  exit 1
}

test -f "$workflow" || fail "missing $workflow"
test -f "$ci_workflow" || fail "missing $ci_workflow"

grep -q '^  deploy:' "$workflow" || fail "missing deploy job"
grep -q '^  deploy-vercel:' "$workflow" || fail "missing Vercel deploy job"
grep -q 'Deploy Spring backend and dashboard' "$workflow" ||
  fail "CD must identify the dashboard deployment"
grep -q 'Deploy dashboard to Vercel' "$workflow" ||
  fail "CD must identify the Vercel deployment"
grep -q '^  workflow_run:' "$workflow" || fail "CD must wait for CI workflow completion"
grep -q 'workflows: \[CI\]' "$workflow" || fail "CD must follow the CI workflow"
grep -q 'web-dashboard/' "$ci_workflow" ||
  fail "CI must detect web-dashboard changes"
grep -q '^  dashboard:' "$ci_workflow" || fail "missing dashboard CI job"
grep -q '^  backend:' "$ci_workflow" || fail "CI must include backend gates"
grep -q '^  analysis:' "$ci_workflow" || fail "CI must include analysis gates"
grep -q '^  dashboard:' "$ci_workflow" || fail "CI must include dashboard gates"
grep -q '^  stack:' "$ci_workflow" || fail "CI must include stack gates"
grep -q '^  ci-gate:' "$ci_workflow" || fail "CI must expose a single release gate"
grep -q 'cancel-in-progress: false' "$workflow" ||
  fail "an active backend deploy must not be cancelled by a newer push"
grep -q 'docker build --pull --tag "\$ANALYSIS_IMAGE" analysis-service' "$workflow" ||
  fail "analysis image must be built in the verified checkout"
grep -q 'docker build --pull --tag "\$DASHBOARD_IMAGE"' "$workflow" ||
  fail "dashboard image must be built in the verified checkout"
grep -q 'stream_image()' "$workflow" ||
  fail "runtime images must transfer without a registry secret"
grep -q 'stream_image "\$IMAGE_TAG"' "$workflow" || fail "backend image must transfer"
grep -q 'stream_image "\$ANALYSIS_IMAGE"' "$workflow" || fail "analysis image must transfer"
grep -q 'stream_image "\$DASHBOARD_IMAGE"' "$workflow" || fail "dashboard image must transfer"
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

grep -q 'up --no-build -d --wait migrate backend dashboard' "$workflow" ||
  fail "server deploy must use only the transferred images"
grep -q 'cleanup_on_exit()' "$workflow" ||
  fail "server deploy must define an exit cleanup"
grep -q 'trap cleanup_on_exit EXIT' "$workflow" ||
  fail "server deploy must clean unused images after success or failure"
grep -q "trap 'exit 143' INT TERM" "$workflow" ||
  fail "server deploy must clean images when interrupted"
grep -q 'exit_status=1' "$workflow" ||
  fail "server deploy must fail when image cleanup fails"
grep -q 'exit "\$exit_status"' "$workflow" ||
  fail "server deploy must propagate cleanup failures"
grep -q "docker inspect --format '{{.Config.Image}}'" "$workflow" ||
  fail "server deploy must preserve exact container image references"
if grep -q 'docker image rm --no-prune' "$workflow"; then
  fail "server deploy must prune untagged image parents"
fi
grep -q 'docker image rm "\$image"' "$workflow" ||
  fail "server deploy must remove unused images with parent pruning"
grep -q 'find -- "\$BACKEND_DEPLOY_PATH" -maxdepth 1 -type f' "$workflow" ||
  fail "server deploy must scope archive cleanup to the deploy path"
grep -Fq -- "-name '.[0-9a-f]*-trade-backend_*.tar.gz'" "$workflow" ||
  fail "server deploy must clean backend transfer archives"
grep -Fq -- "-name '.[0-9a-f]*-trade-analysis_*.tar.gz'" "$workflow" ||
  fail "server deploy must clean analysis transfer archives"
grep -Fq -- "-name '.[0-9a-f]*-trade-dashboard_*.tar.gz'" "$workflow" ||
  fail "server deploy must clean dashboard transfer archives"
if bash -c '
  cleanup_on_exit() {
    local exit_status=$?
    exit_status=1
    exit "$exit_status"
  }
  trap cleanup_on_exit EXIT
  true
'; then
  fail "cleanup failure must fail an otherwise successful shell"
fi
grep -q 'VERCEL_TOKEN' "$workflow" || fail "Vercel token must come from GitHub Secrets"
grep -q 'VERCEL_ORG_ID' "$workflow" || fail "Vercel org ID must be configured"
grep -q 'VERCEL_PROJECT_ID' "$workflow" || fail "Vercel project ID must be configured"
if rg -n 'vercel@58.5.1 (link|pull|build)' "$workflow" >/dev/null; then
  fail "Vercel project tokens must deploy without project-settings or local-build CLI calls"
fi
grep -q 'vercel@58.5.1 deploy --yes --prod' "$workflow" ||
  fail "Vercel deploy must use a non-interactive production deployment"
grep -q -- '--project "\$VERCEL_PROJECT_ID"' "$workflow" ||
  fail "Vercel deploy must target the configured project explicitly"

echo "trade CD contract: PASS"
