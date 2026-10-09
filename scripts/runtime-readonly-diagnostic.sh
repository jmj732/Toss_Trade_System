#!/usr/bin/env bash
set -euo pipefail
set +x

fail() {
  printf 'runtime diagnostic: %s\n' "$1" >&2
  exit 2
}

[[ -n "${BACKEND_DEPLOY_PATH:-}" ]] || fail 'missing deploy path'
[[ "${EXPECTED_SHA:-}" =~ ^($|[0-9a-fA-F]{40})$ ]] || fail 'expected SHA must be empty or 40 hexadecimal characters'
[[ "${INVESTMENT_OS_SHEET_USER_ID:-}" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]] ||
  fail 'configured snapshot owner is unavailable'

expected_sha="$(printf '%s' "$EXPECTED_SHA" | tr '[:upper:]' '[:lower:]')"
owner_id="$INVESTMENT_OS_SHEET_USER_ID"
cd -- "$BACKEND_DEPLOY_PATH"

compose() {
  docker compose \
    -f compose.yaml \
    -f compose.staging.yaml \
    -f compose.staging.credentialed.yaml \
    "$@"
}

single_container_id() {
  local service="$1" ids
  ids="$(compose ps -q "$service" 2>/dev/null)" || return 1
  [[ "$ids" =~ ^[0-9a-f]{12,64}$ ]] || return 1
  printf '%s' "$ids"
}

backend_tag='UNKNOWN'
backend_image_id='UNKNOWN'
backend_state='unknown'
backend_health='unknown'
backend_id=''
if backend_id="$(single_container_id backend)"; then
  inspect="$(docker inspect --format '{{.Config.Image}}{{"\t"}}{{.Image}}{{"\t"}}{{.State.Status}}{{"\t"}}{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$backend_id" 2>/dev/null || true)"
  IFS=$'\t' read -r candidate_tag candidate_image_id candidate_state candidate_health <<<"$inspect"
  if [[ "${candidate_tag:-}" =~ ^trade-backend:[0-9a-f]{40}$ ]]; then backend_tag="$candidate_tag"; fi
  if [[ "${candidate_image_id:-}" =~ ^sha256:[0-9a-f]{64}$ ]]; then backend_image_id="$candidate_image_id"; fi
  case "${candidate_state:-}" in
    created|running|paused|restarting|exited|dead) backend_state="$candidate_state" ;;
  esac
  case "${candidate_health:-}" in
    healthy|unhealthy|starting|none) backend_health="$candidate_health" ;;
  esac
fi

readiness_http='000'
readiness_status='UNKNOWN'
tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "$tmp_dir"' EXIT
if readiness_http="$(curl --silent --show-error --connect-timeout 3 --max-time 8 \
    --output "$tmp_dir/readiness.json" --write-out '%{http_code}' \
    http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null)"; then
  readiness_candidate="$(grep -oE '"status"[[:space:]]*:[[:space:]]*"[A-Z_]+"' "$tmp_dir/readiness.json" 2>/dev/null | head -n 1 | sed -E 's/.*"([A-Z_]+)"/\1/' || true)"
  if [[ "$readiness_candidate" =~ ^[A-Z_]+$ ]]; then readiness_status="$readiness_candidate"; fi
else
  readiness_http='000'
fi
[[ "$readiness_http" =~ ^[0-9]{3}$ ]] || readiness_http='000'

db_status='unavailable'
flyway_version=''
flyway_success='false'
analysis_input_snapshot_count='0'
security_snapshot_count='0'
price_snapshot_count='0'
thesis_count='0'
decision_count='0'
latest_security_projection_count='0'
latest_security_projection_sha256=''
projection_query_success='false'
db_id=''

if db_id="$(single_container_id postgres)"; then
  metadata_sql=$(cat <<'SQL'
BEGIN TRANSACTION READ ONLY;
SELECT
  coalesce((SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1), ''),
  coalesce((SELECT success::text FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1), 'false'),
  (SELECT count(*) FROM analysis_input_snapshots WHERE user_id = :'owner_id'::uuid),
  (SELECT count(*) FROM investment_security_snapshots WHERE user_id = :'owner_id'::uuid),
  (SELECT count(*) FROM investment_price_snapshots WHERE user_id = :'owner_id'::uuid),
  (SELECT count(*) FROM investment_thesis_states WHERE user_id = :'owner_id'::uuid),
  (SELECT count(*) FROM investment_decision_ledger WHERE user_id = :'owner_id'::uuid),
  (SELECT count(*) FROM (
    SELECT ticker, row_number() OVER (PARTITION BY ticker ORDER BY as_of DESC, id DESC) AS row_number
      FROM investment_security_snapshots
     WHERE user_id = :'owner_id'::uuid
       AND ticker IN ('AVT', 'CSTM', 'GOOGL', 'LUNR', 'RDW', 'VST')
  ) latest WHERE row_number = 1);
ROLLBACK;
SQL
)
  metadata="$(printf '%s\n' "$metadata_sql" | docker exec -i "$db_id" psql -X -q -t -A -w -F $'\t' \
    -v ON_ERROR_STOP=1 -v "owner_id=$owner_id" -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null || true)"
  IFS=$'\t' read -r candidate_version candidate_success candidate_input_count candidate_security_count \
    candidate_price_count candidate_thesis_count candidate_decision_count candidate_projection_count <<<"$metadata"
  if [[ "${candidate_version:-}" =~ ^[0-9]+([.][0-9A-Za-z_-]+)*$ &&
        "${candidate_success:-}" =~ ^(true|false)$ &&
        "${candidate_input_count:-}" =~ ^[0-9]+$ &&
        "${candidate_security_count:-}" =~ ^[0-9]+$ &&
        "${candidate_price_count:-}" =~ ^[0-9]+$ &&
        "${candidate_thesis_count:-}" =~ ^[0-9]+$ &&
        "${candidate_decision_count:-}" =~ ^[0-9]+$ &&
        "${candidate_projection_count:-}" =~ ^[0-9]+$ ]]; then
    flyway_version="$candidate_version"
    flyway_success="$candidate_success"
    analysis_input_snapshot_count="$candidate_input_count"
    security_snapshot_count="$candidate_security_count"
    price_snapshot_count="$candidate_price_count"
    thesis_count="$candidate_thesis_count"
    decision_count="$candidate_decision_count"
    latest_security_projection_count="$candidate_projection_count"
    db_status='available'

    # FINGERPRINT_CONTRACT_V1 tickers=AVT,CSTM,GOOGL,LUNR,RDW,VST; selection=latest_per_ticker(as_of_desc,id_desc); row_order=ticker_asc; null=#NULL#_raw; token=UTF8_HEX; token_separator=:; row_separator=LF_with_terminal_LF; hash=SHA256; timestamp=UTC_milliseconds; periodEnd=YYYY-MM-DD; money=2dp; price=4dp; shares_eps_growth=6dp; status=omitted.
    # Field order follows SecurityView's persisted price/fundamentals/consensus:
    # AVT,CSTM,GOOGL,LUNR,RDW,VST; latest per ticker by as_of DESC,id DESC; rows ticker ASC.
    # Field order: ticker, snapshot.asOf; price.{regularClose,regularCloseAsOf,latestPrice,
    # latestPriceAsOf,session,source,secondarySource}; fundamentals.{fiscalPeriod,fiscalYear,
    # fiscalPeriodCode,reportedAt,asOf,source,marketCap,marketCapFormula,marketCapAsOf,enterpriseValue,
    # enterpriseValueAsOf,enterpriseValueSource,cash,debt,basicShares,basicSharesBasis,
    # dilutedShares,dilutedSharesBasis,fullyDilutedMarketCap,fullyDilutedMarketCapAsOf,
    # fullyDilutedMarketCapFormula,balanceSheetAsOf,currency,
    # fieldProvenance.{marketCap,enterpriseValue,cash,debt,basicShares,dilutedShares,revenueTTM,
    # revenueGrowthYoY,ebitdaTTM,eps,fcfTTM,currency}.{source,asOf,period},revenueTTM,revenueGrowthYoY,
    # ebitdaTTM,ebitdaTTMType,ebitdaTTMFormula,ebitdaTTMSource,eps,fcfTTM}; consensus.{asOf,
    # horizon,source,estimateType,estimateLabel,periodEnd,currency,missingReason,epsAnalystCount,
    # revenueAnalystCount,revenueConsensus,epsConsensus,ebitdaConsensus,fcfConsensus}.
    # Timestamps use UTC milliseconds; periodEnd stays YYYY-MM-DD. Monetary values use 2 decimals,
    # prices 4, and shares/EPS/growth 6. Null is #NULL#; empty strings remain empty. UTF-8 hex
    # tokens joined by ':' and rows by LF are SHA-256 hashed. Status is omitted because freshness
    # status is recalculated when context is read. The hash is a comparison fingerprint, not auth.
    projection_sql=$(cat <<'SQL'
BEGIN TRANSACTION READ ONLY;
WITH latest_per_ticker AS (
  SELECT id, ticker, as_of, payload,
         row_number() OVER (PARTITION BY ticker ORDER BY as_of DESC, id DESC) AS row_number
    FROM investment_security_snapshots
   WHERE user_id = :'owner_id'::uuid
     AND ticker IN ('AVT', 'CSTM', 'GOOGL', 'LUNR', 'RDW', 'VST')
), selected AS (
  SELECT id, ticker, as_of, payload
    FROM latest_per_ticker
   WHERE row_number = 1
   ORDER BY ticker ASC
), normalized AS (
  SELECT ticker, ARRAY[
    ticker,
    to_char((payload->>'asOf')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    round((payload#>>'{price,regularClose}')::numeric, 4)::numeric(38,4)::text,
    to_char((payload#>>'{price,regularCloseAsOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    round((payload#>>'{price,latestPrice}')::numeric, 4)::numeric(38,4)::text,
    to_char((payload#>>'{price,latestPriceAsOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{price,session}', payload#>>'{price,source}', payload#>>'{price,secondarySource}',
    payload#>>'{fundamentals,fiscalPeriod}', payload#>>'{fundamentals,fiscalYear}',
    payload#>>'{fundamentals,fiscalPeriodCode}',
    to_char((payload#>>'{fundamentals,reportedAt}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    to_char((payload#>>'{fundamentals,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,source}',
    round((payload#>>'{fundamentals,marketCap}')::numeric, 2)::numeric(38,2)::text,
    payload#>>'{fundamentals,marketCapFormula}',
    to_char((payload#>>'{fundamentals,marketCapAsOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    round((payload#>>'{fundamentals,enterpriseValue}')::numeric, 2)::numeric(38,2)::text,
    to_char((payload#>>'{fundamentals,enterpriseValueAsOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,enterpriseValueSource}',
    round((payload#>>'{fundamentals,cash}')::numeric, 2)::numeric(38,2)::text,
    round((payload#>>'{fundamentals,debt}')::numeric, 2)::numeric(38,2)::text,
    round((payload#>>'{fundamentals,basicShares}')::numeric, 6)::numeric(38,6)::text,
    payload#>>'{fundamentals,basicSharesBasis}',
    round((payload#>>'{fundamentals,dilutedShares}')::numeric, 6)::numeric(38,6)::text,
    payload#>>'{fundamentals,dilutedSharesBasis}',
    round((payload#>>'{fundamentals,fullyDilutedMarketCap}')::numeric, 2)::numeric(38,2)::text,
    to_char((payload#>>'{fundamentals,fullyDilutedMarketCapAsOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fullyDilutedMarketCapFormula}',
    to_char((payload#>>'{fundamentals,balanceSheetAsOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,currency}',
    payload#>>'{fundamentals,fieldProvenance,marketCap,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,marketCap,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,marketCap,period}',
    payload#>>'{fundamentals,fieldProvenance,enterpriseValue,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,enterpriseValue,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,enterpriseValue,period}',
    payload#>>'{fundamentals,fieldProvenance,cash,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,cash,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,cash,period}',
    payload#>>'{fundamentals,fieldProvenance,debt,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,debt,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,debt,period}',
    payload#>>'{fundamentals,fieldProvenance,basicShares,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,basicShares,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,basicShares,period}',
    payload#>>'{fundamentals,fieldProvenance,dilutedShares,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,dilutedShares,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,dilutedShares,period}',
    payload#>>'{fundamentals,fieldProvenance,revenueTTM,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,revenueTTM,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,revenueTTM,period}',
    payload#>>'{fundamentals,fieldProvenance,revenueGrowthYoY,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,revenueGrowthYoY,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,revenueGrowthYoY,period}',
    payload#>>'{fundamentals,fieldProvenance,ebitdaTTM,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,ebitdaTTM,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,ebitdaTTM,period}',
    payload#>>'{fundamentals,fieldProvenance,eps,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,eps,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,eps,period}',
    payload#>>'{fundamentals,fieldProvenance,fcfTTM,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,fcfTTM,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,fcfTTM,period}',
    payload#>>'{fundamentals,fieldProvenance,currency,source}',
    to_char((payload#>>'{fundamentals,fieldProvenance,currency,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{fundamentals,fieldProvenance,currency,period}',
    round((payload#>>'{fundamentals,revenueTTM}')::numeric, 2)::numeric(38,2)::text,
    round((payload#>>'{fundamentals,revenueGrowthYoY}')::numeric, 6)::numeric(38,6)::text,
    round((payload#>>'{fundamentals,ebitdaTTM}')::numeric, 2)::numeric(38,2)::text,
    payload#>>'{fundamentals,ebitdaTTMType}', payload#>>'{fundamentals,ebitdaTTMFormula}',
    payload#>>'{fundamentals,ebitdaTTMSource}',
    round((payload#>>'{fundamentals,eps}')::numeric, 6)::numeric(38,6)::text,
    round((payload#>>'{fundamentals,fcfTTM}')::numeric, 2)::numeric(38,2)::text,
    to_char((payload#>>'{consensus,asOf}')::timestamptz AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
    payload#>>'{consensus,horizon}', payload#>>'{consensus,source}',
    payload#>>'{consensus,estimateType}', payload#>>'{consensus,estimateLabel}',
    payload#>>'{consensus,periodEnd}', payload#>>'{consensus,currency}', payload#>>'{consensus,missingReason}',
    payload#>>'{consensus,epsAnalystCount}', payload#>>'{consensus,revenueAnalystCount}',
    round((payload#>>'{consensus,revenueConsensus}')::numeric, 2)::numeric(38,2)::text,
    round((payload#>>'{consensus,epsConsensus}')::numeric, 6)::numeric(38,6)::text,
    round((payload#>>'{consensus,ebitdaConsensus}')::numeric, 2)::numeric(38,2)::text,
    round((payload#>>'{consensus,fcfConsensus}')::numeric, 2)::numeric(38,2)::text
  ] AS fingerprint_fields
  FROM selected
)
SELECT array_to_string(ARRAY(
  SELECT coalesce(encode(convert_to(field_value, 'UTF8'), 'hex'), '#NULL#')
    FROM unnest(fingerprint_fields) WITH ORDINALITY AS field(field_value, ordinal)
   ORDER BY ordinal
), ':')
  FROM normalized
 ORDER BY ticker ASC;
ROLLBACK;
SQL
)
    if projection_rows="$(printf '%s\n' "$projection_sql" | docker exec -i "$db_id" psql -X -q -t -A -w \
      -v ON_ERROR_STOP=1 -v "owner_id=$owner_id" -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null)"; then
      projection_query_success='true'
      if [[ -z "$projection_rows" ]]; then
        latest_security_projection_sha256="$(printf '' | sha256sum | cut -d ' ' -f 1)"
      else
        latest_security_projection_sha256="$(printf '%s\n' "$projection_rows" | sha256sum | cut -d ' ' -f 1)"
      fi
    fi
  fi
fi

expected_sha_matches='null'
if [[ -n "$expected_sha" ]]; then
  if [[ "$backend_tag" == "trade-backend:$expected_sha" ]]; then
    expected_sha_matches='true'
  else
    expected_sha_matches='false'
  fi
fi

ok='true'
[[ "$backend_state" == running && "$backend_health" == healthy ]] || ok='false'
[[ "$backend_tag" != UNKNOWN && "$backend_image_id" != UNKNOWN ]] || ok='false'
[[ "$readiness_http" == 200 && "$readiness_status" == UP ]] || ok='false'
[[ "$db_status" == available && "$flyway_success" == true ]] || ok='false'
[[ "$projection_query_success" == true ]] || ok='false'
[[ "$latest_security_projection_count" == 6 ]] || ok='false'
[[ "$latest_security_projection_sha256" =~ ^[0-9a-f]{64}$ ]] || ok='false'
[[ "$expected_sha_matches" != false ]] || ok='false'

if [[ "$flyway_version" =~ ^[0-9]+([.][0-9A-Za-z_-]+)*$ ]]; then
  flyway_version_json="\"$flyway_version\""
else
  flyway_version_json='null'
fi

printf '{"schemaVersion":1,"ok":%s,"expectedSha":' "$ok"
if [[ -n "$expected_sha" ]]; then printf '"%s"' "$expected_sha"; else printf 'null'; fi
printf ',"expectedShaMatchesImageTag":%s,"backend":{"imageTag":"%s","imageId":"%s","state":"%s","health":"%s"},' \
  "$expected_sha_matches" "$backend_tag" "$backend_image_id" "$backend_state" "$backend_health"
printf '"readiness":{"httpStatus":"%s","status":"%s"},"database":{"status":"%s","flywayVersion":%s,"flywaySuccess":%s,' \
  "$readiness_http" "$readiness_status" "$db_status" "$flyway_version_json" "$flyway_success"
printf '"analysisInputSnapshotCount":%s,"securitySnapshotCount":%s,"priceSnapshotCount":%s,"thesisCount":%s,"decisionCount":%s,' \
  "$analysis_input_snapshot_count" "$security_snapshot_count" "$price_snapshot_count" "$thesis_count" "$decision_count"
printf '"latestSecurityProjectionCount":%s,"latestSecurityProjectionSha256":"%s"}}\n' \
  "$latest_security_projection_count" "${latest_security_projection_sha256:-}"

[[ "$ok" == true ]]
