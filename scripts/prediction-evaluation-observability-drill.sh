#!/bin/sh
set -eu

project="trade-prediction-observability-$$"
env_file="$(mktemp "${TMPDIR:-/tmp}/trade-prediction-observability.XXXXXX")"
metrics_file="$(mktemp "${TMPDIR:-/tmp}/trade-prediction-metrics.XXXXXX")"
base_port=$((30000 + $$ % 10000))

cleanup() {
  status=$?
  if test "$status" -ne 0; then
    docker compose --env-file "$env_file" -p "$project" \
      -f compose.yaml -f compose.mock.yaml logs --no-color || true
  fi
  docker compose --env-file "$env_file" -p "$project" \
    -f compose.yaml -f compose.mock.yaml down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "$env_file" "$metrics_file"
  exit "$status"
}
trap cleanup EXIT INT TERM

cat >"$env_file" <<EOF
POSTGRES_PASSWORD=$(openssl rand -hex 24)
CREDENTIAL_KEY_BASE64=$(openssl rand -base64 32)
POSTGRES_PORT=$base_port
REDIS_PORT=$((base_port + 1))
ANALYSIS_PORT=$((base_port + 2))
BACKEND_PORT=$((base_port + 3))
DASHBOARD_PORT=$((base_port + 4))
OIDC_MOCK_PORT=$((base_port + 5))
TOSS_MOCK_PORT=$((base_port + 6))
PREDICTION_EVALUATION_ENABLED=false
EOF

docker compose --env-file "$env_file" -p "$project" \
  -f compose.yaml -f compose.mock.yaml up --build --wait -d backend

curl -fsS --max-time 10 \
  "http://127.0.0.1:$((base_port + 3))/actuator/prometheus" >"$metrics_file"

for metric in \
  trade_prediction_evaluation_backlog \
  trade_prediction_evaluation_max_lag_ms \
  trade_prediction_evaluation_attempted_total \
  trade_prediction_evaluation_succeeded_total \
  trade_prediction_evaluation_quote_failed_total \
  trade_prediction_evaluation_lease_failure_total \
  trade_prediction_evaluation_early_stop_total
do
  grep -q "^${metric}" "$metrics_file"
done

echo "prediction evaluation observability drill: PASS"
