#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
script="$root/scripts/runtime-readonly-diagnostic.sh"
workflow="$root/.github/workflows/runtime-readonly-diagnostic.yml"
sanitizer="$root/scripts/sanitize-runtime-diagnostic.py"
contract="$root/scripts/runtime-readonly-fingerprint-v1.json"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT

fail() {
  printf 'read-only runtime diagnostic test: FAIL: %s\n' "$1" >&2
  exit 1
}

python3 - "$script" "$workflow" "$contract" "$0" <<'PY'
import json
import re
import sys
from pathlib import Path

script_path, workflow_path, contract_path, test_path = map(Path, sys.argv[1:])
script = script_path.read_text()
workflow = workflow_path.read_text()
fixture_test = test_path.read_text()
contract = json.loads(contract_path.read_text())
assert contract["version"] == 1
assert contract["tickers"] == ["AVT", "CSTM", "GOOGL", "LUNR", "RDW", "VST"]
assert contract["canonicalization"]["terminalRowSeparator"] is True
assert contract["canonicalization"]["nullToken"] == "#NULL#"
assert len(contract["fields"]) == len({field["path"] for field in contract["fields"]})
for field in contract["fields"]:
    path = field["path"]
    if path == "ticker":
        needle = "SELECT id, ticker, as_of, payload"
    elif path == "asOf":
        needle = "payload->>'asOf'"
    else:
        needle = "'{" + ",".join(path.split(".")) + "}'"
    assert needle in script, f"fingerprint descriptor path is not queried: {path}"

sql_blocks = re.findall(r"(?:metadata_sql|projection_sql)=\$\(cat <<'SQL'\n(.*?)\nSQL\n\)", script, re.S)
assert len(sql_blocks) == 2, "expected both read-only SQL blocks"
for sql in sql_blocks:
    assert sql.lstrip().startswith("BEGIN TRANSACTION READ ONLY;")
    assert sql.rstrip().endswith("ROLLBACK;")
    assert re.search(r"(?im)^\s*(INSERT|UPDATE|DELETE|ALTER|DROP|TRUNCATE|CREATE|GRANT|REVOKE)\b", sql) is None
projection_sql = re.search(r"projection_sql=\$\(cat <<'SQL'\n(.*?)\nSQL\n\)", script, re.S).group(1)
field_array = projection_sql.split("SELECT ticker, ARRAY[", 1)[1].split("] AS fingerprint_fields", 1)[0]
depth = 0
in_string = False
field_count = 1
index = 0
while index < len(field_array):
    char = field_array[index]
    if char == "'":
        if in_string and index + 1 < len(field_array) and field_array[index + 1] == "'":
            index += 1
        else:
            in_string = not in_string
    elif not in_string and char == "(":
        depth += 1
    elif not in_string and char == ")":
        depth -= 1
    elif not in_string and depth == 0 and char == ",":
        field_count += 1
    index += 1
assert field_count == len(contract["fields"]), "SQL projection and machine-readable field contract differ in length"
assert "docker compose" in script and "compose ps -q" in script
assert "docker inspect" in script and "127.0.0.1:8080/actuator/health/readiness" in script
assert "postgres_tcp_ready()" in fixture_test
assert "-h 127.0.0.1" in fixture_test and "-c 'SELECT 1'" in fixture_test
assert "pg" + "_isready -U trade -d trade" not in fixture_test
assert "StrictHostKeyChecking=yes" in workflow and "UserKnownHostsFile=" in workflow
assert "BACKEND_DEPLOY_SSH_KEY" in workflow and "BACKEND_DEPLOY_KNOWN_HOSTS" in workflow
assert "workflow_dispatch:" in workflow and "workflow_run:" not in workflow
assert "sanitize-runtime-diagnostic.py" in workflow
for source_name, source in (("workflow", workflow), ("remote script", script)):
    for forbidden in ("docker compose up", "docker compose restart", "docker compose down", "docker restart", "docker stop"):
        assert forbidden not in source.lower(), f"mutating operation present in {source_name}: {forbidden}"
PY

bash -n "$script"
python3 - "$sanitizer" <<'PY'
import sys
from pathlib import Path

compile(Path(sys.argv[1]).read_text(), sys.argv[1], "exec")
PY

mock_bin="$tmp/mock-bin"
mkdir -p "$mock_bin" "$tmp/remote"
cat >"$mock_bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$MOCK_DOCKER_LOG"
case "${1:-}" in
  compose)
    service="${@: -1}"
    [[ "$*" == *" ps -q $service" ]] || exit 91
    case "$service" in
      backend) printf '%s\n' "$MOCK_BACKEND_CONTAINER_ID" ;;
      postgres) printf '%s\n' "$MOCK_DB_CONTAINER_ID" ;;
      *) exit 92 ;;
    esac
    ;;
  inspect)
    printf '%s\tsha256:%s\t%s\t%s\n' \
      "$MOCK_IMAGE_TAG" "$MOCK_IMAGE_DIGEST" "$MOCK_BACKEND_STATE" "$MOCK_BACKEND_HEALTH"
    ;;
  exec)
    [[ "${2:-}" == -i ]] || exit 93
    sql="$(cat)"
    printf '%s\n' "$sql" >>"$MOCK_SQL_LOG"
    [[ "$sql" == *"BEGIN TRANSACTION READ ONLY;"* && "$sql" == *"ROLLBACK;"* ]] || exit 94
    if grep -Eqi '^[[:space:]]*(INSERT|UPDATE|DELETE|ALTER|DROP|TRUNCATE|CREATE|GRANT|REVOKE)\b' <<<"$sql"; then
      exit 95
    fi
    if [[ "$sql" == *flyway_schema_history* ]]; then
      [[ "${MOCK_FAIL_METADATA:-0}" == 0 ]] || exit 96
      printf '56\t%s\t12\t7\t9\t0\t0\t6\n' "$MOCK_FLYWAY_SUCCESS"
    elif [[ "$sql" == *latest_per_ticker* ]]; then
      [[ "${MOCK_FAIL_PROJECTION:-0}" == 0 ]] || exit 97
      printf '%s\n' raw-financial-sentinel raw-provenance-sentinel raw-symbol-sentinel
    else
      exit 98
    fi
    ;;
  *)
    exit 99
    ;;
esac
DOCKER
cat >"$mock_bin/curl" <<'CURL'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$MOCK_CURL_LOG"
out=''
while (($#)); do
  if [[ "$1" == --output ]]; then out="$2"; shift 2; else shift; fi
done
printf '{"status":"%s","secret":"raw-secret-sentinel","container":"raw-container-id-sentinel"}' \
  "$MOCK_READINESS_STATUS" >"$out"
printf '%s' "$MOCK_READINESS_HTTP"
CURL
chmod +x "$mock_bin/docker" "$mock_bin/curl"

owner_id='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
expected_sha='0123456789abcdef0123456789abcdef01234567'
image_digest='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'

mock_run() {
  local output="$1" sha="$2"
  shift 2
  env \
    PATH="$mock_bin:$PATH" \
    BACKEND_DEPLOY_PATH="$tmp/remote" \
    EXPECTED_SHA="$sha" \
    INVESTMENT_OS_SHEET_USER_ID="$owner_id" \
    MOCK_DOCKER_LOG="$tmp/docker.log" \
    MOCK_SQL_LOG="$tmp/sql.log" \
    MOCK_CURL_LOG="$tmp/curl.log" \
    MOCK_BACKEND_CONTAINER_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    MOCK_DB_CONTAINER_ID=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb \
    MOCK_IMAGE_TAG="${MOCK_IMAGE_TAG:-trade-backend:$expected_sha}" \
    MOCK_IMAGE_DIGEST="$image_digest" \
    MOCK_BACKEND_STATE="${MOCK_BACKEND_STATE:-running}" \
    MOCK_BACKEND_HEALTH="${MOCK_BACKEND_HEALTH:-healthy}" \
    MOCK_READINESS_HTTP="${MOCK_READINESS_HTTP:-200}" \
    MOCK_READINESS_STATUS="${MOCK_READINESS_STATUS:-UP}" \
    MOCK_FLYWAY_SUCCESS="${MOCK_FLYWAY_SUCCESS:-true}" \
    MOCK_FAIL_METADATA="${MOCK_FAIL_METADATA:-0}" \
    MOCK_FAIL_PROJECTION="${MOCK_FAIL_PROJECTION:-0}" \
    bash "$script" >"$output" 2>"$output.stderr"
}

if mock_run "$tmp/valid.json" "$expected_sha"; then status=0; else
  status=$?
  cat "$tmp/valid.json.stderr" >&2
fi
[[ "$status" == 0 ]] || fail "healthy mock did not pass (exit $status)"
python3 "$sanitizer" "$tmp/valid.json" "$tmp/safe.json" || fail "valid diagnostic was rejected by the allowlist"
python3 - "$tmp/safe.json" "$expected_sha" "$image_digest" <<'PY'
import json
import sys
from pathlib import Path

report = json.loads(Path(sys.argv[1]).read_text())
assert report["ok"] is True
assert report["expectedSha"] == sys.argv[2]
assert report["expectedShaMatchesImageTag"] is True
assert report["backend"]["imageTag"] == f"trade-backend:{sys.argv[2]}"
assert report["backend"]["imageId"] == f"sha256:{sys.argv[3]}"
assert report["database"]["flywayVersion"] == "56"
assert report["database"]["flywaySuccess"] is True
assert report["database"]["latestSecurityProjectionCount"] == 6
assert len(report["database"]["latestSecurityProjectionSha256"]) == 64
raw = Path(sys.argv[1]).read_text()
for sentinel in ("raw-financial-sentinel", "raw-provenance-sentinel", "raw-symbol-sentinel", "raw-secret-sentinel", "raw-container-id-sentinel", "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"):
    assert sentinel not in raw
PY

MOCK_IMAGE_TAG="trade-backend:ffffffffffffffffffffffffffffffffffffffff" \
  mock_run "$tmp/sha-mismatch.json" "$expected_sha" && fail "mismatched SHA passed"
grep -q '"expectedShaMatchesImageTag":false' "$tmp/sha-mismatch.json" || fail "image tag SHA mismatch was not reported"

MOCK_READINESS_HTTP=503 MOCK_READINESS_STATUS=DOWN \
  mock_run "$tmp/readiness-failure.json" '' && fail "readiness failure passed"
grep -q '"httpStatus":"503","status":"DOWN"' "$tmp/readiness-failure.json" ||
  fail "readiness status was not recorded"

MOCK_FLYWAY_SUCCESS=false mock_run "$tmp/flyway-failure.json" '' && fail "failed Flyway migration passed"
grep -q '"flywaySuccess":false' "$tmp/flyway-failure.json" || fail "Flyway failure was not recorded"

: >"$tmp/docker.log"
if mock_run "$tmp/invalid-sha.json" bad; then fail "invalid expected SHA passed"; fi
[[ ! -s "$tmp/docker.log" ]] || fail "invalid SHA reached Docker"

printf '{"schemaVersion":1,"ok":true,"secret":"raw-secret-sentinel"}\n' >"$tmp/untrusted.json"
if python3 "$sanitizer" "$tmp/untrusted.json" "$tmp/rejected.json"; then
  fail "unexpected remote output passed the allowlist"
fi
grep -q 'UNTRUSTED_REMOTE_OUTPUT' "$tmp/rejected.json" || fail "sanitizer did not write generic failure JSON"
if grep -q 'raw-secret-sentinel' "$tmp/rejected.json"; then fail "sanitizer leaked unexpected remote output"; fi

python3 - "$script" "$tmp" <<'PY'
import re
import sys
from pathlib import Path

script = Path(sys.argv[1]).read_text()
tmp = Path(sys.argv[2])
for name in ("metadata_sql", "projection_sql"):
    match = re.search(rf"{name}=\$\(cat <<'SQL'\n(.*?)\nSQL\n\)", script, re.S)
    assert match, f"missing SQL block {name}"
    (tmp / f"{name}.sql").write_text(match.group(1) + "\n")
PY

if docker info >/dev/null 2>&1; then
  pg_container="runtime-readonly-test-$RANDOM-$$"
  pg_owner='11111111-2222-4333-8444-555555555555'
  pg_password='read-only-test-only'
  cleanup_pg() { docker rm -f "$pg_container" >/dev/null 2>&1 || true; }
  trap 'cleanup_pg; rm -rf -- "$tmp"' EXIT
  docker run --rm -d --name "$pg_container" \
    -e POSTGRES_USER=trade -e "POSTGRES_PASSWORD=$pg_password" -e POSTGRES_DB=trade \
    postgres:16-alpine >/dev/null
  postgres_tcp_ready() {
    local result
    result="$(docker exec -e "PGPASSWORD=$pg_password" "$pg_container" \
      psql -X -q -t -A -w -v ON_ERROR_STOP=1 -U trade -d trade -h 127.0.0.1 \
      -c 'SELECT 1' 2>/dev/null)" || return 1
    [[ "$result" == 1 ]]
  }
  ready=0
  for _ in {1..30}; do
    if postgres_tcp_ready; then ready=1; break; fi
    sleep 1
  done
  [[ "$ready" == 1 ]] || fail "local PostgreSQL TCP fixture did not become ready"
  docker exec -i "$pg_container" psql -X -q -v ON_ERROR_STOP=1 -U trade -d trade >/dev/null <<SQL
CREATE TABLE flyway_schema_history (installed_rank integer, version text, success boolean);
CREATE TABLE analysis_input_snapshots (id integer, user_id uuid);
CREATE TABLE investment_security_snapshots (id uuid, user_id uuid, ticker text, as_of timestamptz, payload jsonb);
CREATE TABLE investment_price_snapshots (id integer, user_id uuid);
CREATE TABLE investment_thesis_states (id integer, user_id uuid);
CREATE TABLE investment_decision_ledger (id integer, user_id uuid);
INSERT INTO flyway_schema_history VALUES (55, '55', true), (56, '56', true);
INSERT INTO analysis_input_snapshots VALUES (1, '$pg_owner'), (2, '$pg_owner'), (3, '99999999-2222-4333-8444-555555555555');
INSERT INTO investment_price_snapshots VALUES (1, '$pg_owner'), (2, '$pg_owner'), (3, '$pg_owner');
WITH payload AS (
  SELECT jsonb_build_object(
    'asOf', '2026-10-08T20:00:00Z',
    'price', jsonb_build_object('regularClose', 100.123456, 'regularCloseAsOf', '2026-10-08T20:00:00Z',
      'latestPrice', 101.123456, 'latestPriceAsOf', '2026-10-08T20:00:00Z', 'session', 'REGULAR_CLOSE',
      'source', 'TOSS', 'secondarySource', NULL),
    'fundamentals', jsonb_build_object('fiscalPeriod', 'FY2025', 'fiscalYear', '2025', 'fiscalPeriodCode', 'FY',
      'reportedAt', '2026-02-01T00:00:00Z', 'asOf', '2026-02-01T00:00:00Z', 'source', 'SEC',
      'marketCap', 123456.789, 'marketCapFormula', 'TOSS_REGULAR_CLOSE * BASIC_SHARES',
      'marketCapAsOf', '2026-10-08T20:00:00Z', 'enterpriseValue', 123000.12,
      'enterpriseValueAsOf', '2026-02-01T00:00:00Z', 'enterpriseValueSource', 'SEC',
      'cash', 1000.12, 'debt', 2000.34, 'basicShares', 123.456789, 'basicSharesBasis', 'BASIC_SHARES',
      'dilutedShares', 130.123456, 'dilutedSharesBasis', 'DILUTED_SHARES',
      'fullyDilutedMarketCap', 13000.00, 'fullyDilutedMarketCapAsOf', '2026-10-08T20:00:00Z',
      'fullyDilutedMarketCapFormula', 'TOSS_REGULAR_CLOSE * DILUTED_SHARES',
      'balanceSheetAsOf', '2026-02-01T00:00:00Z', 'currency', 'USD', 'revenueTTM', 9000.12,
      'revenueGrowthYoY', 0.123456, 'ebitdaTTM', 1000.12, 'ebitdaTTMType', 'REPORTED',
      'ebitdaTTMFormula', 'REPORTED_EBITDA', 'ebitdaTTMSource', 'SEC', 'eps', 1.234567,
      'fcfTTM', 800.12, 'fieldProvenance', jsonb_build_object(
        'cash', jsonb_build_object('source', 'SEC', 'asOf', '2026-02-01T00:00:00Z', 'period', 'FY2025'),
        'marketCap', jsonb_build_object('source', 'TOSS', 'asOf', '2026-10-08T20:00:00Z', 'period', 'REGULAR_CLOSE'))),
    'consensus', jsonb_build_object('asOf', '2026-10-08T20:00:00Z', 'horizon', 'FY1', 'source', 'ALPHA_VANTAGE',
      'estimateType', 'ANNUAL', 'estimateLabel', 'FY1', 'periodEnd', '2027-12-31', 'currency', 'USD',
      'missingReason', NULL, 'epsAnalystCount', 4, 'revenueAnalystCount', 5,
      'revenueConsensus', 10000.12, 'epsConsensus', 2.123456, 'ebitdaConsensus', 2000.12, 'fcfConsensus', 900.12)
  ) AS payload
), tickers(ticker, id) AS (
  VALUES
    ('AVT', '00000000-0000-4000-8000-000000000001'),
    ('CSTM', '00000000-0000-4000-8000-000000000002'),
    ('GOOGL', '00000000-0000-4000-8000-000000000003'),
    ('LUNR', '00000000-0000-4000-8000-000000000004'),
    ('RDW', '00000000-0000-4000-8000-000000000005'),
    ('VST', '00000000-0000-4000-8000-000000000006')
)
INSERT INTO investment_security_snapshots
SELECT tickers.id::uuid, '$pg_owner', ticker, '2026-10-08T20:00:00Z', payload.payload
  FROM tickers CROSS JOIN payload;
INSERT INTO investment_security_snapshots
SELECT '00000000-0000-4000-8000-000000000007', '$pg_owner', 'AVT', '2026-10-07T20:00:00Z', payload
  FROM investment_security_snapshots WHERE ticker = 'AVT' LIMIT 1;
SQL

  read_sql='BEGIN TRANSACTION READ ONLY; SELECT (SELECT count(*) FROM analysis_input_snapshots)::text || '"'"'|'"'"' || (SELECT count(*) FROM investment_security_snapshots)::text || '"'"'|'"'"' || (SELECT count(*) FROM investment_price_snapshots)::text; ROLLBACK;'
  before="$(docker exec "$pg_container" psql -X -q -t -A -U trade -d trade -c "$read_sql")"
  metadata="$(docker exec -i "$pg_container" psql -X -q -t -A -w -F $'\t' -v ON_ERROR_STOP=1 \
    -v "owner_id=$pg_owner" -U trade -d trade -h /var/run/postgresql -f - <"$tmp/metadata_sql.sql")"
  IFS=$'\t' read -r version success input_count security_count price_count thesis_count decision_count projection_count <<<"$metadata"
  [[ "$version" == 56 && "$success" == true && "$input_count" == 2 && "$security_count" == 7 &&
     "$price_count" == 3 && "$thesis_count" == 0 && "$decision_count" == 0 && "$projection_count" == 6 ]] ||
    fail "real PostgreSQL metadata query returned unexpected scoped counts"
  projections="$(docker exec -i "$pg_container" psql -X -q -t -A -w -v ON_ERROR_STOP=1 \
    -v "owner_id=$pg_owner" -U trade -d trade -h /var/run/postgresql -f - <"$tmp/projection_sql.sql")"
  [[ "$(printf '%s\n' "$projections" | wc -l | tr -d ' ')" == 6 ]] || fail "real PostgreSQL fingerprint query did not return six rows"
  [[ "$projections" != *"100.123456"* ]] || fail "fingerprint query emitted unnormalized raw financial value"
  fingerprint="$(printf '%s\n' "$projections" | sha256sum | cut -d ' ' -f 1)"
  [[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || fail "real PostgreSQL fingerprint is not SHA-256"
  printf '%s\n' "$projections" >"$tmp/projections.txt"
  python3 - "$tmp/projections.txt" "$contract" <<'PY'
import json
import sys
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

rows_path, contract_path = map(Path, sys.argv[1:])
contract = json.loads(contract_path.read_text())
payload = {
    "asOf": "2026-10-08T20:00:00Z",
    "price": {
        "regularClose": Decimal("100.123456"),
        "regularCloseAsOf": "2026-10-08T20:00:00Z",
        "latestPrice": Decimal("101.123456"),
        "latestPriceAsOf": "2026-10-08T20:00:00Z",
        "session": "REGULAR_CLOSE",
        "source": "TOSS",
        "secondarySource": None,
    },
    "fundamentals": {
        "fiscalPeriod": "FY2025", "fiscalYear": "2025", "fiscalPeriodCode": "FY",
        "reportedAt": "2026-02-01T00:00:00Z", "asOf": "2026-02-01T00:00:00Z", "source": "SEC",
        "marketCap": Decimal("123456.789"), "marketCapFormula": "TOSS_REGULAR_CLOSE * BASIC_SHARES",
        "marketCapAsOf": "2026-10-08T20:00:00Z", "enterpriseValue": Decimal("123000.12"),
        "enterpriseValueAsOf": "2026-02-01T00:00:00Z", "enterpriseValueSource": "SEC",
        "cash": Decimal("1000.12"), "debt": Decimal("2000.34"),
        "basicShares": Decimal("123.456789"), "basicSharesBasis": "BASIC_SHARES",
        "dilutedShares": Decimal("130.123456"), "dilutedSharesBasis": "DILUTED_SHARES",
        "fullyDilutedMarketCap": Decimal("13000.00"),
        "fullyDilutedMarketCapAsOf": "2026-10-08T20:00:00Z",
        "fullyDilutedMarketCapFormula": "TOSS_REGULAR_CLOSE * DILUTED_SHARES",
        "balanceSheetAsOf": "2026-02-01T00:00:00Z", "currency": "USD",
        "fieldProvenance": {
            "cash": {"source": "SEC", "asOf": "2026-02-01T00:00:00Z", "period": "FY2025"},
            "marketCap": {"source": "TOSS", "asOf": "2026-10-08T20:00:00Z", "period": "REGULAR_CLOSE"},
        },
        "revenueTTM": Decimal("9000.12"), "revenueGrowthYoY": Decimal("0.123456"),
        "ebitdaTTM": Decimal("1000.12"), "ebitdaTTMType": "REPORTED",
        "ebitdaTTMFormula": "REPORTED_EBITDA", "ebitdaTTMSource": "SEC",
        "eps": Decimal("1.234567"), "fcfTTM": Decimal("800.12"),
    },
    "consensus": {
        "asOf": "2026-10-08T20:00:00Z", "horizon": "FY1", "source": "ALPHA_VANTAGE",
        "estimateType": "ANNUAL", "estimateLabel": "FY1", "periodEnd": "2027-12-31",
        "currency": "USD", "missingReason": None, "epsAnalystCount": 4, "revenueAnalystCount": 5,
        "revenueConsensus": Decimal("10000.12"), "epsConsensus": Decimal("2.123456"),
        "ebitdaConsensus": Decimal("2000.12"), "fcfConsensus": Decimal("900.12"),
    },
}

def at_path(root, path):
    value = root
    for part in path.split("."):
        if not isinstance(value, dict) or part not in value:
            return None
        value = value[part]
    return value

def normalize(value, field):
    if value is None:
        return None
    if field["type"] == "decimal":
        quantum = Decimal(1).scaleb(-field["scale"])
        return format(Decimal(value).quantize(quantum, rounding=ROUND_HALF_UP), f".{field['scale']}f")
    if field["type"] == "timestamp":
        moment = datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
        return moment.strftime("%Y-%m-%dT%H:%M:%S.%f")[:23] + "Z"
    if field["type"] in {"date", "integer"}:
        return str(value)
    return value

tickers = ["AVT", "CSTM", "GOOGL", "LUNR", "RDW", "VST"]
expected = []
for ticker in tickers:
    values = {"ticker": ticker, **payload}
    tokens = []
    for field in contract["fields"]:
        normalized = normalize(at_path(values, field["path"]), field)
        tokens.append("#NULL#" if normalized is None else normalized.encode("utf-8").hex())
    expected.append(":".join(tokens))
actual = rows_path.read_text().splitlines()
assert actual == expected, "real PostgreSQL fingerprint tokens differ from the descriptor-based canonical projection"
PY
  after="$(docker exec "$pg_container" psql -X -q -t -A -U trade -d trade -c "$read_sql")"
  [[ "$before" == "$after" ]] || fail "read-only SQL changed fixture row counts"
  printf 'read-only runtime diagnostic: mock checks and real PostgreSQL SQL checks passed\n'
else
  if [[ "${CI:-}" == true ]]; then fail "Docker is required for the real PostgreSQL SQL check in CI"; fi
  printf 'read-only runtime diagnostic: mock checks passed; real PostgreSQL check skipped (Docker unavailable)\n'
fi
