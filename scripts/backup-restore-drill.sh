#!/bin/sh
set -eu

# Proves backup-postgres.sh / restore-postgres.sh work end-to-end: seed data, back up, wipe
# both volumes, restore, and let the real backend boot against the restored database so
# Flyway's own startup validate-then-noop path is the re-validation. Redis is never backed up
# or restored — it is only started because backend's readiness depends on it, matching its
# cache/session role, not because its data matters here.

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

project="trade-drill-$$"
env_file="$(mktemp "${TMPDIR:-/tmp}/trade-drill.XXXXXX")"
backup_file="$(mktemp "${TMPDIR:-/tmp}/trade-drill-backup.XXXXXX.dump")"
base_port=$((21000 + $$ % 10000))
postgres_password="$(openssl rand -hex 24)"
canary_id="$(uuidgen | tr 'A-Z' 'a-z')"

cleanup() {
  status=$?
  if test "$status" -ne 0; then
    docker compose --env-file "$env_file" -p "$project" -f compose.yaml logs --no-color || true
  fi
  docker compose --env-file "$env_file" -p "$project" -f compose.yaml down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "$env_file" "$backup_file"
  exit "$status"
}
trap cleanup EXIT INT TERM

cat >"$env_file" <<EOF
POSTGRES_PASSWORD=$postgres_password
CREDENTIAL_KEY_BASE64=$(openssl rand -base64 32)
POSTGRES_PORT=$base_port
REDIS_PORT=$((base_port + 1))
ANALYSIS_PORT=$((base_port + 2))
BACKEND_PORT=$((base_port + 3))
DASHBOARD_PORT=$((base_port + 4))
EOF

export COMPOSE_PROJECT="$project"

docker compose --env-file "$env_file" -p "$project" -f compose.yaml \
  up --build -d --wait postgres redis analysis backend

docker compose --env-file "$env_file" -p "$project" -f compose.yaml exec -T postgres \
  psql -U trade -d trade -c "INSERT INTO users (id) VALUES ('$canary_id')" >/dev/null

COMPOSE_PROJECT="$project" ./scripts/backup-postgres.sh "$backup_file"

credential_key="$(grep CREDENTIAL_KEY_BASE64 "$env_file" | cut -d= -f2)"
if grep -qF "$postgres_password" "$backup_file"; then
  fail "backup file contains the Postgres password"
fi
if grep -qF "$credential_key" "$backup_file"; then
  fail "backup file contains the credential key"
fi

docker compose --env-file "$env_file" -p "$project" -f compose.yaml down -v

docker compose --env-file "$env_file" -p "$project" -f compose.yaml \
  up -d --wait postgres

COMPOSE_PROJECT="$project" ./scripts/restore-postgres.sh "$backup_file"

docker compose --env-file "$env_file" -p "$project" -f compose.yaml \
  up --build -d --wait redis analysis backend

row_count="$(docker compose --env-file "$env_file" -p "$project" -f compose.yaml exec -T postgres \
  psql -U trade -d trade -tAc "SELECT count(*) FROM users WHERE id = '$canary_id'")"
test "$(echo "$row_count" | tr -d '[:space:]')" = "1" ||
  fail "canary row did not survive backup and restore"

echo "backup restore drill: PASS"
