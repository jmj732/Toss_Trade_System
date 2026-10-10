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
ai_policy_deployment_enabled='null'
if backend_id="$(single_container_id backend)"; then
  inspect="$(docker inspect --format '{{.Config.Image}}{{"\t"}}{{.Image}}{{"\t"}}{{.State.Status}}{{"\t"}}{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}{{"\t"}}{{range .Config.Env}}{{if eq (index (split . "=") 0) "INVESTMENT_THESIS_AI_POLICY_ENABLED"}}{{join (slice (split . "=") 1) "="}}{{end}}{{end}}' "$backend_id" 2>/dev/null || true)"
  IFS=$'\t' read -r candidate_tag candidate_image_id candidate_state candidate_health \
    candidate_ai_policy_enabled <<<"$inspect"
  if [[ "${candidate_tag:-}" =~ ^trade-backend:[0-9a-f]{40}$ ]]; then backend_tag="$candidate_tag"; fi
  if [[ "${candidate_image_id:-}" =~ ^sha256:[0-9a-f]{64}$ ]]; then backend_image_id="$candidate_image_id"; fi
  case "${candidate_state:-}" in
    created|running|paused|restarting|exited|dead) backend_state="$candidate_state" ;;
  esac
  case "${candidate_health:-}" in
    healthy|unhealthy|starting|none) backend_health="$candidate_health" ;;
  esac
  case "$(printf '%s' "${candidate_ai_policy_enabled:-}" | tr '[:upper:]' '[:lower:]' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')" in
    true|yes|on|1) ai_policy_deployment_enabled='true' ;;
    false|no|off|0) ai_policy_deployment_enabled='false' ;;
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
portfolio_query_success='false'
portfolio_observed_at='#NULL#'
portfolio_latest_status='#NULL#'
portfolio_latest_attempted_at='#NULL#'
portfolio_latest_error_code='#NULL#'
portfolio_succeeded_24h='0'
portfolio_partial_24h='0'
portfolio_failed_24h='0'
portfolio_accepted_status='#NULL#'
portfolio_accepted_attempted_at='#NULL#'
portfolio_account1_as_of='#NULL#'
portfolio_session_reason='#NULL#'
portfolio_session_reference_at='#NULL#'
portfolio_next_declared_interval_starts_at='#NULL#'
portfolio_manual_status='#NULL#'
portfolio_manual_as_of='#NULL#'
portfolio_manual_read_at='#NULL#'
portfolio_price_synced_present='false'
portfolio_price_synced_min='#NULL#'
portfolio_price_synced_max='#NULL#'
ai_verification_events_available='false'
ai_verification_count='0'
ai_approved_count='0'
ai_blocked_count='0'
ai_rejected_count='0'
ai_policy_revisions_available='false'
ai_policy_revision_count='0'
ai_policy_owner_scope_configured='false'
[[ -n "$owner_id" ]] && ai_policy_owner_scope_configured='true'

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

    ai_schema_sql=$(cat <<'SQL'
BEGIN TRANSACTION READ ONLY;
SELECT
  CASE WHEN to_regclass('public.investment_thesis_ai_verification_events') IS NULL THEN 'false' ELSE 'true' END,
  CASE WHEN to_regclass('public.investment_thesis_ai_policy_revisions') IS NULL THEN 'false' ELSE 'true' END;
ROLLBACK;
SQL
)
    ai_schema_metadata="$(printf '%s\n' "$ai_schema_sql" | docker exec -i "$db_id" psql -X -q -t -A -w -F $'\t' \
      -v ON_ERROR_STOP=1 -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null || true)"
    IFS=$'\t' read -r candidate_ai_events_schema candidate_ai_policy_schema <<<"$ai_schema_metadata"

    if [[ "${candidate_ai_events_schema:-}" == true ]]; then
      ai_verification_count_sql=$(cat <<'SQL'
BEGIN TRANSACTION READ ONLY;
SELECT count(*),
       count(*) FILTER (WHERE outcome = 'AUTO_APPROVED'),
       count(*) FILTER (WHERE outcome = 'BLOCKED'),
       count(*) FILTER (WHERE outcome = 'REJECTED')
  FROM investment_thesis_ai_verification_events
 WHERE user_id = :'owner_id'::uuid;
ROLLBACK;
SQL
)
      ai_event_counts="$(printf '%s\n' "$ai_verification_count_sql" | docker exec -i "$db_id" psql -X -q -t -A -w -F $'\t' \
        -v ON_ERROR_STOP=1 -v "owner_id=$owner_id" -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null || true)"
      IFS=$'\t' read -r candidate_ai_verification_count candidate_ai_approved_count \
        candidate_ai_blocked_count candidate_ai_rejected_count <<<"$ai_event_counts"
      if [[ "${candidate_ai_verification_count:-}" =~ ^[0-9]+$ &&
            "${candidate_ai_approved_count:-}" =~ ^[0-9]+$ &&
            "${candidate_ai_blocked_count:-}" =~ ^[0-9]+$ &&
            "${candidate_ai_rejected_count:-}" =~ ^[0-9]+$ ]]; then
        ai_verification_events_available='true'
        ai_verification_count="$candidate_ai_verification_count"
        ai_approved_count="$candidate_ai_approved_count"
        ai_blocked_count="$candidate_ai_blocked_count"
        ai_rejected_count="$candidate_ai_rejected_count"
      fi
    fi

    if [[ "${candidate_ai_policy_schema:-}" == true ]]; then
      ai_policy_revision_count_sql=$(cat <<'SQL'
BEGIN TRANSACTION READ ONLY;
SELECT count(*)
  FROM investment_thesis_ai_policy_revisions
 WHERE user_id = :'owner_id'::uuid;
ROLLBACK;
SQL
)
      ai_policy_revision_count_result="$(printf '%s\n' "$ai_policy_revision_count_sql" | docker exec -i "$db_id" psql -X -q -t -A -w \
        -v ON_ERROR_STOP=1 -v "owner_id=$owner_id" -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null || true)"
      if [[ "$ai_policy_revision_count_result" =~ ^[0-9]+$ ]]; then
        ai_policy_revisions_available='true'
        ai_policy_revision_count="$ai_policy_revision_count_result"
      fi
    fi

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

  # Portfolio freshness facts only: statuses, reason codes, timestamps, booleans and counts from
  # investment_os_portfolio_snapshots. Sheet cells (amounts, quantities, prices, account and sheet
  # identifiers) never leave PostgreSQL; only "Price Synced At" cells are read, as validated timestamps.
  # Every column is non-empty (#NULL# for missing) so tab-separated parsing cannot shift fields.
  portfolio_sql=$(cat <<'SQL'
BEGIN TRANSACTION READ ONLY;
WITH pattern AS (
  SELECT '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}(:[0-9]{2}([.][0-9]{1,9})?)?(Z|[+-][0-9]{2}:[0-9]{2})$'::text AS iso,
         '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'::text AS day,
         '^[A-Z0-9_]{1,120}$'::text AS code,
         'YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'::text AS utc_ms
), attempts AS (
  SELECT id, attempt_status, attempted_at, created_at, error_code, payload
    FROM investment_os_portfolio_snapshots
   WHERE user_id = :'owner_id'::uuid
), latest AS (
  SELECT attempt_status, attempted_at, error_code
    FROM attempts
   ORDER BY attempted_at DESC, created_at DESC, id DESC
   LIMIT 1
), counts AS (
  SELECT count(*) FILTER (WHERE attempt_status = 'SUCCEEDED' AND attempted_at >= now() - interval '24 hours') AS succeeded,
         count(*) FILTER (WHERE attempt_status = 'PARTIAL' AND attempted_at >= now() - interval '24 hours') AS partial,
         count(*) FILTER (WHERE attempt_status = 'FAILED' AND attempted_at >= now() - interval '24 hours') AS failed
    FROM attempts
), accepted AS (
  SELECT attempt_status, attempted_at,
         nullif(btrim(payload->>'account1AsOf'), '') AS account1_as_of,
         nullif(btrim(payload->>'sessionReason'), '') AS session_reason,
         nullif(btrim(payload->>'sessionReferenceAt'), '') AS session_reference_at,
         nullif(btrim(payload->>'nextDeclaredIntervalStartsAt'), '') AS next_declared_interval_starts_at,
         nullif(btrim(payload->>'manualStatus'), '') AS manual_status,
         nullif(btrim(payload->>'manualAsOf'), '') AS manual_as_of,
         nullif(btrim(payload->>'manualReadAt'), '') AS manual_read_at,
         CASE WHEN jsonb_typeof(payload->'accountState') = 'object' THEN payload->'accountState' END AS account_state
    FROM attempts
   WHERE attempt_status IN ('SUCCEEDED', 'PARTIAL') AND jsonb_typeof(payload) = 'object'
   ORDER BY attempted_at DESC, created_at DESC, id DESC
   LIMIT 1
), accepted_typed AS (
  SELECT accepted.attempt_status, accepted.attempted_at,
         CASE WHEN account1_as_of ~ pattern.iso AND pg_input_is_valid(account1_as_of, 'timestamptz')
              THEN account1_as_of::timestamptz END AS account1_as_of,
         CASE WHEN session_reason IS NULL THEN '#NULL#' WHEN session_reason ~ pattern.code
              THEN session_reason ELSE 'REDACTED' END AS session_reason,
         CASE WHEN session_reference_at ~ pattern.iso AND pg_input_is_valid(session_reference_at, 'timestamptz')
              THEN session_reference_at::timestamptz END AS session_reference_at,
         CASE WHEN next_declared_interval_starts_at ~ pattern.iso
                   AND pg_input_is_valid(next_declared_interval_starts_at, 'timestamptz')
              THEN next_declared_interval_starts_at::timestamptz END AS next_declared_interval_starts_at,
         CASE WHEN manual_status IS NULL THEN '#NULL#' WHEN manual_status ~ pattern.code
              THEN manual_status ELSE 'REDACTED' END AS manual_status,
         CASE WHEN manual_as_of ~ pattern.day AND pg_input_is_valid(manual_as_of, 'date')
              THEN manual_as_of::date END AS manual_as_of,
         CASE WHEN manual_read_at ~ pattern.iso AND pg_input_is_valid(manual_read_at, 'timestamptz')
              THEN manual_read_at::timestamptz END AS manual_read_at,
         account_state
    FROM accepted CROSS JOIN pattern
), price_synced_column AS (
  SELECT min(header.ordinal)::int - 1 AS position
    FROM accepted_typed,
         jsonb_array_elements_text(CASE WHEN jsonb_typeof(account_state->'headers') = 'array'
                                        THEN account_state->'headers' ELSE '[]'::jsonb END)
           WITH ORDINALITY AS header(name, ordinal)
   WHERE regexp_replace(lower(header.name), '[^a-z0-9]', '', 'g') = 'pricesyncedat'
), price_synced_cells AS (
  SELECT nullif(btrim(sheet_row.value->>price_synced_column.position), '') AS synced_text
    FROM accepted_typed
         CROSS JOIN price_synced_column
         CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(account_state->'rows') = 'array'
                                                      THEN account_state->'rows' ELSE '[]'::jsonb END) AS sheet_row(value)
   WHERE price_synced_column.position IS NOT NULL AND jsonb_typeof(sheet_row.value) = 'array'
), price_synced AS (
  SELECT count(*) AS valid_count,
         min(CASE WHEN synced_text ~ pattern.iso AND pg_input_is_valid(synced_text, 'timestamptz')
                  THEN synced_text::timestamptz END) AS earliest,
         max(CASE WHEN synced_text ~ pattern.iso AND pg_input_is_valid(synced_text, 'timestamptz')
                  THEN synced_text::timestamptz END) AS latest
    FROM price_synced_cells CROSS JOIN pattern
   WHERE synced_text ~ pattern.iso AND pg_input_is_valid(synced_text, 'timestamptz')
)
SELECT
  to_char(now() AT TIME ZONE 'UTC', pattern.utc_ms),
  coalesce(latest.attempt_status, '#NULL#'),
  coalesce(to_char(latest.attempted_at AT TIME ZONE 'UTC', pattern.utc_ms), '#NULL#'),
  CASE WHEN latest.error_code IS NULL THEN '#NULL#' WHEN latest.error_code ~ pattern.code
       THEN latest.error_code ELSE 'REDACTED' END,
  counts.succeeded, counts.partial, counts.failed,
  coalesce(accepted_typed.attempt_status, '#NULL#'),
  coalesce(to_char(accepted_typed.attempted_at AT TIME ZONE 'UTC', pattern.utc_ms), '#NULL#'),
  coalesce(to_char(accepted_typed.account1_as_of AT TIME ZONE 'UTC', pattern.utc_ms), '#NULL#'),
  coalesce(accepted_typed.session_reason, '#NULL#'),
  coalesce(to_char(accepted_typed.session_reference_at AT TIME ZONE 'UTC', pattern.utc_ms), '#NULL#'),
  coalesce(to_char(accepted_typed.next_declared_interval_starts_at AT TIME ZONE 'UTC', pattern.utc_ms), '#NULL#'),
  coalesce(accepted_typed.manual_status, '#NULL#'),
  coalesce(to_char(accepted_typed.manual_as_of, 'YYYY-MM-DD'), '#NULL#'),
  coalesce(to_char(accepted_typed.manual_read_at AT TIME ZONE 'UTC', pattern.utc_ms), '#NULL#'),
  (price_synced.valid_count > 0)::text,
  coalesce(to_char(price_synced.earliest AT TIME ZONE 'UTC', pattern.utc_ms), '#NULL#'),
  coalesce(to_char(price_synced.latest AT TIME ZONE 'UTC', pattern.utc_ms), '#NULL#')
  FROM pattern
       CROSS JOIN counts
       CROSS JOIN price_synced
       LEFT JOIN latest ON true
       LEFT JOIN accepted_typed ON true;
ROLLBACK;
SQL
)
  portfolio_row="$(printf '%s\n' "$portfolio_sql" | docker exec -i "$db_id" psql -X -q -t -A -w -F $'\t' \
    -v ON_ERROR_STOP=1 -v "owner_id=$owner_id" -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null || true)"
  IFS=$'\t' read -r candidate_observed_at candidate_latest_status candidate_latest_attempted_at \
    candidate_latest_error_code candidate_succeeded_24h candidate_partial_24h candidate_failed_24h \
    candidate_accepted_status candidate_accepted_attempted_at candidate_account1_as_of \
    candidate_session_reason candidate_session_reference_at candidate_next_declared_interval_starts_at \
    candidate_manual_status candidate_manual_as_of candidate_manual_read_at \
    candidate_price_synced_present candidate_price_synced_min candidate_price_synced_max \
    candidate_portfolio_extra <<<"$portfolio_row"
  # Structural fields must all validate or the whole row is discarded; free-form fields are validated
  # individually when the JSON is written (timestamps/dates -> null, unsafe codes -> REDACTED).
  if [[ "${candidate_latest_status:-}" =~ ^(SUCCEEDED|PARTIAL|FAILED|#NULL#)$ &&
        "${candidate_succeeded_24h:-}" =~ ^[0-9]+$ &&
        "${candidate_partial_24h:-}" =~ ^[0-9]+$ &&
        "${candidate_failed_24h:-}" =~ ^[0-9]+$ &&
        "${candidate_accepted_status:-}" =~ ^(SUCCEEDED|PARTIAL|#NULL#)$ &&
        "${candidate_price_synced_present:-}" =~ ^(true|false)$ &&
        -n "${candidate_price_synced_max:-}" &&
        -z "${candidate_portfolio_extra:-}" ]]; then
    portfolio_query_success='true'
    portfolio_observed_at="$candidate_observed_at"
    portfolio_latest_status="$candidate_latest_status"
    portfolio_latest_attempted_at="$candidate_latest_attempted_at"
    portfolio_latest_error_code="$candidate_latest_error_code"
    portfolio_succeeded_24h="$candidate_succeeded_24h"
    portfolio_partial_24h="$candidate_partial_24h"
    portfolio_failed_24h="$candidate_failed_24h"
    portfolio_accepted_status="$candidate_accepted_status"
    portfolio_accepted_attempted_at="$candidate_accepted_attempted_at"
    portfolio_account1_as_of="$candidate_account1_as_of"
    portfolio_session_reason="$candidate_session_reason"
    portfolio_session_reference_at="$candidate_session_reference_at"
    portfolio_next_declared_interval_starts_at="$candidate_next_declared_interval_starts_at"
    portfolio_manual_status="$candidate_manual_status"
    portfolio_manual_as_of="$candidate_manual_as_of"
    portfolio_manual_read_at="$candidate_manual_read_at"
    portfolio_price_synced_present="$candidate_price_synced_present"
    portfolio_price_synced_min="$candidate_price_synced_min"
    portfolio_price_synced_max="$candidate_price_synced_max"
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
printf '"latestSecurityProjectionCount":%s,"latestSecurityProjectionSha256":"%s","aiVerificationPolicy":{"verificationEventsAvailable":%s,"verificationCount":%s,"approvedCount":%s,"blockedCount":%s,"rejectedCount":%s,"policyRevisionsAvailable":%s,"aiPolicyRevisionCount":%s,"deploymentEnabled":%s,"ownerScopeConfigured":%s}},' \
  "$latest_security_projection_count" "${latest_security_projection_sha256:-}" \
  "$ai_verification_events_available" "$ai_verification_count" "$ai_approved_count" \
  "$ai_blocked_count" "$ai_rejected_count" "$ai_policy_revisions_available" \
  "$ai_policy_revision_count" "$ai_policy_deployment_enabled" "$ai_policy_owner_scope_configured"

json_timestamp() {
  if [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[.][0-9]{3}Z$ ]]; then
    printf '"%s"' "$1"
  else
    printf 'null'
  fi
}

json_date() {
  if [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then printf '"%s"' "$1"; else printf 'null'; fi
}

json_code() {
  if [[ -z "$1" || "$1" == '#NULL#' ]]; then
    printf 'null'
  elif [[ "$1" =~ ^[A-Z0-9_]{1,120}$ ]]; then
    printf '"%s"' "$1"
  else
    printf '"REDACTED"'
  fi
}

json_status() {
  if [[ "$1" =~ ^(SUCCEEDED|PARTIAL|FAILED)$ ]]; then printf '"%s"' "$1"; else printf 'null'; fi
}

printf '"portfolioFreshness":{"querySuccess":%s,"observedAt":%s,' \
  "$portfolio_query_success" "$(json_timestamp "$portfolio_observed_at")"
printf '"latestAttempt":{"status":%s,"attemptedAt":%s,"errorCode":%s},' \
  "$(json_status "$portfolio_latest_status")" "$(json_timestamp "$portfolio_latest_attempted_at")" \
  "$(json_code "$portfolio_latest_error_code")"
printf '"attemptCountsLast24h":{"SUCCEEDED":%s,"PARTIAL":%s,"FAILED":%s},' \
  "$portfolio_succeeded_24h" "$portfolio_partial_24h" "$portfolio_failed_24h"
printf '"latestAccepted":{"status":%s,"attemptedAt":%s,"account1AsOf":%s,"sessionReason":%s,' \
  "$(json_status "$portfolio_accepted_status")" "$(json_timestamp "$portfolio_accepted_attempted_at")" \
  "$(json_timestamp "$portfolio_account1_as_of")" "$(json_code "$portfolio_session_reason")"
printf '"sessionReferenceAt":%s,"nextDeclaredIntervalStartsAt":%s,"manualStatus":%s,"manualAsOf":%s,' \
  "$(json_timestamp "$portfolio_session_reference_at")" \
  "$(json_timestamp "$portfolio_next_declared_interval_starts_at")" \
  "$(json_code "$portfolio_manual_status")" "$(json_date "$portfolio_manual_as_of")"
printf '"manualReadAt":%s,"positionsPriceSyncedAtPresent":%s,"positionsPriceSyncedAtMin":%s,"positionsPriceSyncedAtMax":%s}}}\n' \
  "$(json_timestamp "$portfolio_manual_read_at")" "$portfolio_price_synced_present" \
  "$(json_timestamp "$portfolio_price_synced_min")" "$(json_timestamp "$portfolio_price_synced_max")"

[[ "$ok" == true ]]
