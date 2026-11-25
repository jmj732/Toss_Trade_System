#!/bin/sh
set -eu

DATA_DIR="${EV1_DATA_DIR:-/var/lib/ev1}"
UNIVERSE="${EV1_UNIVERSE:-/app/config/q1-universe.csv}"
PRICE_DIR="${EV1_PRICE_DIR:-$DATA_DIR/prices}"
OUT_DIR="${EV1_OUT_DIR:-$DATA_DIR/forward}"
REFRESH_MARKER="$DATA_DIR/.last-price-refresh"
PRICE_REFRESH_STATUS="$DATA_DIR/runner-health.json"
REFRESH_INTERVAL="${EV1_PRICE_REFRESH_INTERVAL_SECONDS:-86400}"
POLL_INTERVAL="${EV1_POLL_INTERVAL_SECONDS:-900}"
PRICE_START="${EV1_PRICE_START:-2020-01-01}"

: "${SEC_USER_AGENT:?SEC_USER_AGENT must be supplied by the deployment secret store}"
mkdir -p "$DATA_DIR" "$PRICE_DIR" "$OUT_DIR"

symbols() {
  awk -F, 'NR > 1 && $1 != "" { printf "%s%s", (n++ ? "," : ""), $1 }' "$UNIVERSE"
}

PRICE_REFRESH_ATTEMPTED=0
PRICE_REFRESH_FAILED=0

write_runner_status() {
  python3 - "$PRICE_REFRESH_STATUS" "$1" "$2" <<'PY'
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

path = Path(sys.argv[1])
payload = {
    "status": sys.argv[2],
    "component": sys.argv[3],
    "observed_at": datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
}
temporary = path.with_name(path.name + ".tmp")
temporary.write_text(json.dumps(payload, sort_keys=True) + "\n", encoding="utf-8")
os.replace(temporary, path)
PY
}

refresh_prices() {
  now=$(date +%s)
  last=0
  if [ -f "$REFRESH_MARKER" ]; then
    last=$(cat "$REFRESH_MARKER" 2>/dev/null || printf '0')
  fi
  case "$last" in *[!0-9]*|'') last=0 ;; esac
  if [ $((now - last)) -lt "$REFRESH_INTERVAL" ]; then
    return 0
  fi
  PRICE_REFRESH_ATTEMPTED=1
  end_exclusive=$(python3 -c 'from datetime import datetime, timedelta, timezone; print((datetime.now(timezone.utc) + timedelta(days=1)).date().isoformat())')
  python3 /app/scripts/collect-yahoo-daily.py \
    --symbols "$(symbols)" \
    --out "$PRICE_DIR" \
    --start "$PRICE_START" \
    --end-exclusive "$end_exclusive" \
    --refresh \
    --pause "${EV1_YAHOO_PAUSE_SECONDS:-0.15}" \
    --max-retries "${EV1_YAHOO_MAX_RETRIES:-4}" \
    --backoff "${EV1_YAHOO_BACKOFF_SECONDS:-2.0}"
  printf '%s\n' "$now" > "$REFRESH_MARKER"
}

while :; do
  PRICE_REFRESH_ATTEMPTED=0
  PRICE_REFRESH_FAILED=0
  if ! refresh_prices; then
    PRICE_REFRESH_FAILED=1
    write_runner_status "error" "yahoo_refresh" || true
    printf '%s\n' 'EV-1 Yahoo refresh failed; retained prior price files' >&2
  fi
  if python3 /app/scripts/ev1-recorder.py \
    --universe "$UNIVERSE" \
    --price-dir "$PRICE_DIR" \
    --out "$OUT_DIR" \
    --once \
    --min-interval "${EV1_SEC_MIN_INTERVAL_SECONDS:-0.2}" \
    --max-retries "${EV1_SEC_MAX_RETRIES:-4}" \
    --backoff "${EV1_SEC_BACKOFF_SECONDS:-2.0}"; then
    if [ "$PRICE_REFRESH_FAILED" -eq 0 ] && [ "$PRICE_REFRESH_ATTEMPTED" -eq 1 ]; then
      write_runner_status "healthy" "all" || true
    fi
  else
    write_runner_status "error" "sec_poll" || true
    printf '%s\n' 'EV-1 SEC poll failed; retained manifest and will retry' >&2
  fi
  sleep "$POLL_INTERVAL"
done
