#!/bin/sh
set -eu

# Restores a backup produced by backup-postgres.sh into the running postgres container.
# --clean --if-exists drops existing objects first so restore is safe to run against a
# non-empty database, not only a freshly created one.

input="${1:?usage: restore-postgres.sh <input-file>}"
test -f "$input" || {
  echo "FAIL: backup file not found: $input" >&2
  exit 1
}

project_args=""
if [ -n "${COMPOSE_PROJECT:-}" ]; then
  project_args="-p $COMPOSE_PROJECT"
fi

# shellcheck disable=SC2086
docker compose $project_args -f compose.yaml exec -T postgres \
  pg_restore -U trade -d trade --clean --if-exists <"$input"

echo "restore complete from: $input"
