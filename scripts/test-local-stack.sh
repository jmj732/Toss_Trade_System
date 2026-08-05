#!/bin/sh
set -eu

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

for file in \
  compose.yaml \
  compose.dev.yaml \
  compose.mock.yaml \
  compose.staging.credentialed.yaml \
  .env.example \
  .env.staging.example \
  trading-backend/Dockerfile \
  analysis-service/Dockerfile \
  web-dashboard/Dockerfile \
  scripts/smoke-local-stack.sh
do
  test -f "$file" || fail "missing $file"
done

test -x scripts/credentialed-staging-preflight.sh ||
  fail "credentialed staging preflight must be executable"
grep -q 'doppler run' scripts/credentialed-staging-preflight.sh ||
  fail "credentialed preflight must load Doppler trade/staging"
grep -q 'financialmodelingprep.com/stable' compose.staging.credentialed.yaml ||
  fail "missing FMP stable endpoint"
grep -q 'api.stlouisfed.org' compose.staging.credentialed.yaml &&
grep -q '"path":"/fred/series/observations"' compose.staging.credentialed.yaml ||
  fail "missing FRED observations endpoint"
grep -q 'data.sec.gov' compose.staging.credentialed.yaml ||
  fail "missing SEC data endpoint"
grep -q 'generativelanguage.googleapis.com/v1beta' compose.staging.credentialed.yaml ||
  fail "missing Gemini endpoint"
grep -q '"as-of-path":"/0/timestamp"' compose.staging.credentialed.yaml ||
  fail "missing FMP freshness field"
grep -q '"fields":{"macro.value":"/observations/0/value"}' compose.staging.credentialed.yaml ||
  fail "missing FRED value field"
grep -q '/filings/recent/acceptanceDateTime/0' compose.staging.credentialed.yaml ||
  fail "missing SEC freshness field"
grep -q '"api-key-query-parameter":"apikey"' compose.staging.credentialed.yaml ||
  fail "missing FMP API-key query configuration"
grep -q '"api-key-query-parameter":"api_key"' compose.staging.credentialed.yaml ||
  fail "missing FRED API-key query configuration"
grep -q 'x-goog-api-key' scripts/credentialed-staging-preflight.sh ||
  fail "missing Gemini API-key header configuration"
grep -q 'curl --config "\$curl_config"' scripts/credentialed-staging-preflight.sh ||
  fail "preflight must keep provider credentials out of curl argv"
if grep -Eq 'curl .*FMP_API_KEY|curl .*FRED_API_KEY|curl .*GEMINI_API_KEY' scripts/credentialed-staging-preflight.sh; then
  fail "preflight must not pass provider credentials as curl arguments"
fi
grep -q 'doppler run --project trade --config staging -- docker compose' \
  docs/ops/staging-deploy-rollback.md ||
  fail "credentialed deploy must retain Doppler injection"
grep -q 'BROKER_CREDENTIALS_ENABLED: "true"' compose.staging.credentialed.yaml ||
  fail "credentialed staging must expose broker onboarding"
grep -q 'REAL_ORDER_ENABLED: "false"' compose.staging.credentialed.yaml ||
  fail "credentialed staging must block real order activation"
grep -q 'REAL_ORDER_CANARY_ENABLED: "false"' compose.staging.credentialed.yaml ||
  fail "credentialed staging must block live canary"
grep -q '^      PUBLIC_DASHBOARD_URL: ' compose.staging.credentialed.yaml ||
  fail "credentialed staging must pass dashboard URL into Spring"
grep -q 'SECURITY_ACCESS_TOKEN_SIGNING_SECRET' compose.staging.credentialed.yaml ||
  fail "credentialed staging must pass the token signing secret"
if grep -q 'TOSS_CLIENT_SECRET' compose.staging.credentialed.yaml; then
  fail "Toss client secret must not be an environment setting"
fi

git check-ignore -q .env || fail ".env is not ignored"
git check-ignore -q .env.local || fail ".env.local is not ignored"
git check-ignore -q .env.smoke || fail ".env.smoke is not ignored"

for service in postgres redis analysis backend dashboard; do
  grep -q "^  $service:" compose.yaml || fail "missing $service service"
done

test "$(grep -c 'restart: unless-stopped' compose.yaml)" -eq 5 ||
  fail "every base service needs restart policy"
test "$(grep -c 'healthcheck:' compose.yaml)" -eq 5 ||
  fail "every base service needs a healthcheck"

grep -q '^  oidc-mock:' compose.mock.yaml || fail "missing OIDC mock"
grep -q '^  toss-mock:' compose.mock.yaml || fail "missing Toss mock"
grep -q 'BROKER_TOSS_BASE_URL' compose.dev.yaml || fail "missing dev Toss config"
grep -q 'PROVIDER_OIDC_ISSUER_URI' compose.dev.yaml || fail "missing dev OIDC config"
grep -q 'BROKER_TOSS_BASE_URL' compose.mock.yaml || fail "missing mock Toss config"
grep -q 'PROVIDER_OIDC_ISSUER_URI' compose.mock.yaml || fail "missing mock OIDC config"

grep -q 'PREDICTION_EVALUATION_ENABLED:.*false' compose.yaml ||
  fail "prediction evaluation must default to disabled"
for property in \
  INTERVAL \
  INITIAL_DELAY \
  LOCK_TTL \
  BATCH_SIZE \
  MAX_PER_TICK \
  MAX_RUNTIME \
  METRICS_CACHE_TTL
do
  grep -q "PREDICTION_EVALUATION_$property" compose.yaml ||
    fail "missing prediction evaluation $property mapping"
done
grep -q 'MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,prometheus' compose.yaml ||
  fail "backend must expose Prometheus through the existing Actuator server"
grep -q '"127.0.0.1:${BACKEND_PORT:-8080}:8080"' compose.yaml ||
  fail "backend port must remain loopback-bound"
if grep -q 'MANAGEMENT_SERVER_PORT' compose.yaml; then
  fail "prediction metrics must not open a separate management port"
fi
grep -q '^PREDICTION_EVALUATION_ENABLED=false' .env.example ||
  fail ".env.example must keep prediction evaluation disabled"
grep -q '^PREDICTION_EVALUATION_ENABLED=false' .env.staging.example ||
  fail ".env.staging.example must keep prediction evaluation disabled"

test -f mocks/toss/mappings/read-only.json || fail "missing Toss read-only mappings"
if grep -RiqE '/(orders?|trades?)(/|")' mocks/toss; then
  fail "Toss mock must not expose order/trade endpoints"
fi

for context in trading-backend analysis-service web-dashboard; do
  test -f "$context/.dockerignore" || fail "missing $context/.dockerignore"
  grep -q '^\.env' "$context/.dockerignore" || fail "$context image context may include secrets"
done

echo "local stack contract: PASS"
