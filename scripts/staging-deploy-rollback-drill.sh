#!/bin/sh
set -eu

# Proves the deploy/rollback mechanics of compose.staging.yaml end-to-end against an
# isolated, throwaway project: build once, tag it twice (:drill-a / :drill-b, standing in
# for two release tags a real pipeline would have pushed to a registry), deploy tag A,
# confirm healthy, "deploy" tag B by switching BACKEND_IMAGE only (no rebuild), confirm
# healthy, then roll back to tag A the same way and confirm healthy again. Mock-only:
# builds on compose.mock.yaml, never compose.dev.yaml, so no real OIDC/Toss credential is
# ever involved, and BrokerAdapter has no order-placement method regardless.

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

project="trade-staging-drill-$$"
# compose.staging.yaml bind-mounts the secret file into the postgres container, which
# requires the Docker DAEMON (not just this shell) to see the path. On Docker Desktop /
# colima on macOS, $TMPDIR and /tmp are not part of the daemon's VM filesystem, so the
# secret file must live under the repo, which already is.
secrets_dir="$(mktemp -d ./.staging-drill-secrets.XXXXXX)"
env_file="$(mktemp "${TMPDIR:-/tmp}/trade-staging-drill.XXXXXX" 2>/dev/null || mktemp ./.staging-drill-env.XXXXXX)"
base_port=$((22000 + $$ % 10000))

cleanup() {
  status=$?
  if test "$status" -ne 0; then
    docker compose --env-file "$env_file" -p "$project" \
      -f compose.yaml -f compose.mock.yaml -f compose.staging.yaml logs --no-color || true
  fi
  docker compose --env-file "$env_file" -p "$project" \
    -f compose.yaml -f compose.mock.yaml -f compose.staging.yaml down -v --remove-orphans >/dev/null 2>&1 || true
  rm -rf "$secrets_dir" "$env_file"
  exit "$status"
}
trap cleanup EXIT INT TERM

openssl rand -hex 24 >"$secrets_dir/db-password"

cat >"$env_file" <<EOF
CREDENTIAL_KEY_BASE64=$(openssl rand -base64 32)
AUTH_TOKEN_SIGNING_SECRET=$(openssl rand -hex 32)
STAGING_DB_PASSWORD_FILE=$secrets_dir/db-password
POSTGRES_PORT=$base_port
REDIS_PORT=$((base_port + 1))
ANALYSIS_PORT=$((base_port + 2))
BACKEND_PORT=$((base_port + 3))
DASHBOARD_PORT=$((base_port + 4))
OIDC_MOCK_PORT=$((base_port + 5))
TOSS_MOCK_PORT=$((base_port + 6))
EOF

compose() {
  docker compose --env-file "$env_file" -p "$project" \
    -f compose.yaml -f compose.mock.yaml -f compose.staging.yaml "$@"
}

readiness_up() {
  status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    "http://127.0.0.1:$((base_port + 3))/actuator/health/readiness")"
  test "$status" = "200"
}

deploy_and_verify() {
  tag="$1"
  BACKEND_IMAGE="trade-backend:$tag" compose up -d --wait postgres redis analysis migrate backend
  readiness_up || fail "backend readiness did not report UP after deploying $tag"
  echo "deployed $tag: readiness UP"
}

# Build once, tag twice — standing in for two already-pushed release tags.
docker build -q -t trade-backend:drill-a ./trading-backend >/dev/null
docker tag trade-backend:drill-a trade-backend:drill-b

deploy_and_verify drill-a
deploy_and_verify drill-b
deploy_and_verify drill-a

echo "staging deploy/rollback drill: PASS"
