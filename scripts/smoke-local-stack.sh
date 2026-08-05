#!/bin/sh
set -eu

project="trade-smoke-$$"
env_file="$(mktemp "${TMPDIR:-/tmp}/trade-smoke.XXXXXX")"
base_port=$((20000 + $$ % 10000))

cleanup() {
  status=$?
  if test "$status" -ne 0; then
    docker compose --env-file "$env_file" -p "$project" \
      -f compose.yaml -f compose.mock.yaml logs --no-color || true
  fi
  docker compose --env-file "$env_file" -p "$project" \
    -f compose.yaml -f compose.mock.yaml down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "$env_file"
  exit "$status"
}
trap cleanup EXIT INT TERM

cat >"$env_file" <<EOF
POSTGRES_PASSWORD=$(openssl rand -hex 24)
CREDENTIAL_KEY_BASE64=$(openssl rand -base64 32)
AUTH_TOKEN_SIGNING_SECRET=$(openssl rand -hex 32)
POSTGRES_PORT=$base_port
REDIS_PORT=$((base_port + 1))
ANALYSIS_PORT=$((base_port + 2))
BACKEND_PORT=$((base_port + 3))
DASHBOARD_PORT=$((base_port + 4))
OIDC_MOCK_PORT=$((base_port + 5))
TOSS_MOCK_PORT=$((base_port + 6))
EOF

docker compose --env-file "$env_file" -p "$project" \
  -f compose.yaml -f compose.mock.yaml up --build --wait -d

curl -fsS --max-time 5 \
  "http://127.0.0.1:$((base_port + 2))/internal/v1/ready" |
  grep -q '"status":"READY"'
curl -fsS --max-time 5 \
  "http://127.0.0.1:$((base_port + 3))/actuator/health/readiness" |
  grep -q '"status":"UP"'
curl -fsS --max-time 5 \
  "http://127.0.0.1:$((base_port + 4))/health" |
  grep -q '"status":"READY"'
curl -fsS --max-time 5 \
  "http://127.0.0.1:$((base_port + 5))/isalive" >/dev/null
curl -fsS --max-time 5 \
  "http://127.0.0.1:$((base_port + 6))/__admin/mappings" >/dev/null

status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
  "http://127.0.0.1:$((base_port + 4))/api/v1/session")"
test "$status" = 401

echo "local stack smoke: PASS"
