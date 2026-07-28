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
  .env.example \
  trading-backend/Dockerfile \
  analysis-service/Dockerfile \
  web-dashboard/Dockerfile \
  scripts/smoke-local-stack.sh
do
  test -f "$file" || fail "missing $file"
done

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

test -f mocks/toss/mappings/read-only.json || fail "missing Toss read-only mappings"
if grep -RiqE '/(orders?|trades?)(/|")' mocks/toss; then
  fail "Toss mock must not expose order/trade endpoints"
fi

for context in trading-backend analysis-service web-dashboard; do
  test -f "$context/.dockerignore" || fail "missing $context/.dockerignore"
  grep -q '^\.env' "$context/.dockerignore" || fail "$context image context may include secrets"
done

echo "local stack contract: PASS"
