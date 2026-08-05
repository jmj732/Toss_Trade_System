#!/bin/sh
set -eu

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# Doppler injects secrets into this process only. No env dump, Compose config dump, or raw
# provider response is printed. Set PREFLIGHT_SKIP_DOPPLER=1 only for a controlled test env.
if test "${PREFLIGHT_SKIP_DOPPLER:-0}" != "1" && test "${1:-}" != "--doppler-loaded"; then
  command -v doppler >/dev/null 2>&1 || fail "doppler CLI is required"
  exec doppler run \
    --project "${DOPPLER_PROJECT:-trade}" \
    --config "${DOPPLER_CONFIG:-staging}" \
    -- "$0" --doppler-loaded
fi
if test "${1:-}" = "--doppler-loaded"; then
  shift
fi

require_env() {
  test -n "$(printenv "$1" 2>/dev/null || true)" || fail "$1 is required"
}

for name in \
  CREDENTIAL_KEY_BASE64 STAGING_DB_PASSWORD_FILE AUTH_TOKEN_SIGNING_SECRET \
  OIDC_CLIENT_ID OIDC_CLIENT_SECRET OIDC_ISSUER_URI PUBLIC_DASHBOARD_URL \
  FMP_API_KEY FRED_API_KEY SEC_USER_AGENT GEMINI_API_KEY
do
  require_env "$name"
done
test -r "$STAGING_DB_PASSWORD_FILE" || fail "STAGING_DB_PASSWORD_FILE is not readable"
test -z "${TOSS_CLIENT_SECRET:-}" || fail "TOSS_CLIENT_SECRET must not be supplied"

case "${SEC_CIK:-0000320193}" in
  [0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;;
  *) fail "SEC_CIK must be ten digits" ;;
esac

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v python3 >/dev/null 2>&1 || fail "python3 is required"
command -v docker >/dev/null 2>&1 || fail "docker CLI is required"

if ! docker compose \
  -f compose.yaml -f compose.staging.yaml -f compose.staging.credentialed.yaml \
  config --quiet >/dev/null 2>/dev/null
then
  fail "credentialed Compose config is invalid"
fi

max_age_seconds="${PREFLIGHT_MAX_AGE_SECONDS:-604800}"
test "$max_age_seconds" -gt 0 2>/dev/null || fail "PREFLIGHT_MAX_AGE_SECONDS must be positive"
now_epoch="$(date +%s)"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/trade-credentialed-preflight.XXXXXX")"
umask 077
failures=0
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

probe() {
  provider="$1"
  kind="$2"
  url="$3"
  shift 3
  response="$tmp_dir/$provider.json"
  curl_config="$tmp_dir/$provider.curl"
  {
    printf 'url = "%s"\n' "$url"
    printf 'fail\nsilent\nshow-error\nmax-time = 15\noutput = "%s"\nwrite-out = "%%{http_code}"\n' "$response"
    while test "$#" -gt 0; do
      test "$1" = "--header" || fail "unsupported preflight curl option"
      shift
      printf 'header = "%s"\n' "$1"
      shift
    done
  } > "$curl_config"
  http_code="$(curl --config "$curl_config" 2>/dev/null || true)"
  if test "$http_code" != "200"; then
    echo "$provider status=UNAVAILABLE freshness=UNKNOWN degrade=HTTP_$http_code"
    failures=$((failures + 1))
    return
  fi

  if result="$(python3 - "$kind" "$response" "$now_epoch" "$max_age_seconds" <<'PY'
import datetime
import json
import sys

kind, path, now_text, max_age_text = sys.argv[1:]
now = int(now_text)
max_age = int(max_age_text)

with open(path, encoding="utf-8") as stream:
    body = json.load(stream)

def parse_date(value):
    if not isinstance(value, str) or not value:
        raise ValueError("missing timestamp")
    if len(value) == 10:
        return int(datetime.datetime.fromisoformat(value).replace(tzinfo=datetime.timezone.utc).timestamp())
    return int(datetime.datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp())

if kind == "fmp":
    row = body[0]
    if not row.get("symbol") or not isinstance(row.get("price"), (int, float)):
        raise ValueError("required quote fields missing")
    as_of = int(row["timestamp"])
elif kind == "fred":
    row = next(item for item in body["observations"] if item.get("value") not in (None, "", "."))
    if not row.get("date") or not row.get("value"):
        raise ValueError("required observation fields missing")
    as_of = parse_date(row["date"])
elif kind == "sec":
    recent = body["filings"]["recent"]
    if not recent["form"][0] or not recent["accessionNumber"][0] or not recent["acceptanceDateTime"][0]:
        raise ValueError("required filing fields missing")
    as_of = parse_date(recent["acceptanceDateTime"][0])
elif kind == "gemini":
    if not body.get("name") or "generateContent" not in body.get("supportedGenerationMethods", []):
        raise ValueError("model does not support generateContent")
    as_of = now
else:
    raise ValueError("unknown provider")

age = max(0, now - as_of)
print("status=%s freshness=%s degrade=NONE" % ("STALE" if age > max_age else "HEALTHY", "STALE" if age > max_age else "FRESH"))
PY
  )"; then
    echo "$provider $result"
    case "$result" in
      status=HEALTHY\ freshness=FRESH\ degrade=NONE) ;;
      *) failures=$((failures + 1)) ;;
    esac
  else
    echo "$provider status=DEGRADED freshness=UNKNOWN degrade=INVALID_RESPONSE"
    failures=$((failures + 1))
  fi
}

fmp_base="${FMP_BASE_URL:-https://financialmodelingprep.com/stable}"
probe FMP fmp "${fmp_base%/}/quote?symbol=${FMP_SYMBOL:-AAPL}" \
  --header "apikey: $FMP_API_KEY"

fred_series="${FRED_SERIES_ID:-DFF}"
case "$fred_series" in
  *[!A-Za-z0-9._-]*) fail "FRED_SERIES_ID contains invalid characters" ;;
esac
fred_base="${FRED_BASE_URL:-https://api.stlouisfed.org}"
probe FRED fred "${fred_base%/}/fred/series/observations?series_id=$fred_series&api_key=$FRED_API_KEY&file_type=json&limit=1&sort_order=desc"

sec_base="${SEC_BASE_URL:-https://data.sec.gov}"
probe SEC sec "${sec_base%/}/submissions/CIK${SEC_CIK:-0000320193}.json" \
  --header "User-Agent: $SEC_USER_AGENT"

gemini_base="${GEMINI_BASE_URL:-https://generativelanguage.googleapis.com/v1beta}"
model="${GEMINI_MODEL_ID:-gemini-2.5-flash}"
case "$model" in
  *[!A-Za-z0-9._-]*) fail "GEMINI_MODEL_ID contains invalid characters" ;;
esac
probe GEMINI gemini "${gemini_base%/}/models/$model" \
  --header "x-goog-api-key: $GEMINI_API_KEY"

# Onboarding is enabled; live order remains blocked until a user registers and validates Toss
# credentials through the vault. This is an expected safety state, not a failed provider probe.
echo "TOSS status=BLOCKED freshness=NOT_APPLICABLE degrade=CREDENTIAL_REGISTRATION_REQUIRED"
echo "LIVE_ORDER status=BLOCKED freshness=NOT_APPLICABLE degrade=REAL_ORDER_DISABLED"

test "$failures" -eq 0 || fail "credentialed provider preflight failed"
echo "credentialed staging preflight: PASS"
