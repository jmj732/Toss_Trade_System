#!/bin/sh
set -eu

# Dumps the trade database via pg_dump inside the running postgres container.
# Contains only application data: no .env, no CREDENTIAL_KEY_BASE64, no POSTGRES_PASSWORD.
# Broker credentials are already ciphertext at the application layer, so the dump never
# contains a usable secret even though broker_connections rows are included.

output="${1:?usage: backup-postgres.sh <output-file>}"
project_args=""
if [ -n "${COMPOSE_PROJECT:-}" ]; then
  project_args="-p $COMPOSE_PROJECT"
fi

# shellcheck disable=SC2086
docker compose $project_args -f compose.yaml exec -T postgres \
  pg_dump -U trade -Fc -d trade >"$output"

echo "backup written: $output ($(wc -c <"$output") bytes)"
