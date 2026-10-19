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
owner_id="$(printf '%s' "$INVESTMENT_OS_SHEET_USER_ID" | tr '[:upper:]' '[:lower:]')"
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
ai_policy_deployment_source='UNKNOWN'
sheet_enabled='null'
sheet_enabled_source='UNKNOWN'
sheet_owner_matches='null'
sheet_owner_source='UNKNOWN'
deployment_max_evidence_age_seconds='null'
deployment_minimum_source_count='null'
deployment_max_trigger_below_price_pct='null'
deployment_threshold_source='UNKNOWN'
owner_policy_stored_override_present='null'
owner_policy_stored_override_enabled='null'
owner_policy_effective_enabled='null'
owner_policy_source='UNKNOWN'
owner_policy_max_evidence_age_seconds='null'
owner_policy_minimum_source_count='null'
owner_policy_max_trigger_below_price_pct='null'
owner_policy_source_thresholds='UNKNOWN'
thesis_revision_diagnostics_available='false'
thesis_with_revision_count='0'
thesis_without_revision_count='0'
thesis_revision_count='0'
legacy_baseline_revision_count='0'
latest_thesis_snapshot_match_count='0'
latest_thesis_snapshot_mismatch_count='0'
current_theses_with_proposal_run_id_count='0'
current_theses_without_proposal_run_id_count='0'
current_thesis_snapshot_match_status='UNKNOWN'

safe_bool() {
  case "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')" in
    true|yes|on|1) printf 'true' ;;
    false|no|off|0) printf 'false' ;;
    *) return 1 ;;
  esac
}

safe_duration_seconds() {
  local value="$1" hours minutes seconds total
  [[ "$value" =~ ^PT(([0-9]{1,6})H)?(([0-9]{1,6})M)?(([0-9]{1,6})S)?$ ]] || return 1
  hours="${BASH_REMATCH[2]:-0}"
  minutes="${BASH_REMATCH[4]:-0}"
  seconds="${BASH_REMATCH[6]:-0}"
  [[ -n "${BASH_REMATCH[2]:-}${BASH_REMATCH[4]:-}${BASH_REMATCH[6]:-}" ]] || return 1
  total=$((10#$hours * 3600 + 10#$minutes * 60 + 10#$seconds))
  (( total >= 60 && total <= 604800 )) || return 1
  printf '%s' "$total"
}

safe_source_count() {
  [[ "$1" =~ ^([1-9]|10)$ ]] || return 1
  printf '%s' "$1"
}

safe_trigger_pct() {
  local fraction scaled
  [[ "$1" =~ ^0([.]([0-9]{1,6}))?$ ]] || return 1
  fraction="${BASH_REMATCH[2]:-}"
  [[ -n "$fraction" ]] || fraction='0'
  while (( ${#fraction} < 6 )); do fraction="${fraction}0"; done
  scaled=$((10#$fraction))
  (( scaled <= 500000 )) || return 1
  printf '0.%06d' "$scaled"
}

tmp_dir="$(mktemp -d)"
trap 'rm -rf -- "$tmp_dir"' EXIT

runtime_available='false'
runtime_oom_killed='null'
runtime_restart_count='null'
runtime_started_at='null'
runtime_memory_current='null'
runtime_memory_limit='null'
runtime_pids_current='null'
runtime_pids_limit='null'
healthcheck_exit_codes=''
healthcheck_count=0
thread_counts_available='false'
thread_acceptor_count='null'
thread_poller_count='null'
thread_exec_count='null'
thread_total_count='null'
wchan_counts_available='false'
wchan_futex_wait_count='null'
wchan_ep_poll_count='null'
wchan_inet_csk_accept_count='null'
wchan_pipe_write_count='null'
wchan_other_count='null'
port_8080_available='false'
port_8080_listener_count='null'
port_8080_rx_queue='null'
fd_diagnostics_available='false'
fd_count='null'
max_open_files_soft='null'
max_open_files_hard='null'
jcmd_available='null'
jstack_available='null'
java_tools_available='false'
exceptions_available='false'
exception_counts='0 0 0 0 0'

probe_http='000'
probe_exit='null'
probe_category='UNKNOWN'
probe_duration='null'
probe_one() {
  local location="$1" name="$2" expected="$3" curl_exit status elapsed payload stderr_file response timing category started_ms ended_ms
  status='000'
  curl_exit=0
  elapsed='null'
  response=''
  timing=''
  category='UNKNOWN'
  if [[ "$location" == host ]]; then
    if [[ "$name" == mcpUnauthenticated ]]; then
      payload='{"jsonrpc":"2.0","id":1,"method":"ping"}'
      writeout="%{http_code}\t%{time_total}"
      if response="$(curl --silent --show-error --connect-timeout 1 --max-time 3 \
          --output /dev/null --write-out "$writeout" --header 'Content-Type: application/json' \
          --data "$payload" "http://127.0.0.1:8080/api/v1/connector/mcp" 2>/dev/null)"; then
        status="${response%%$'\t'*}"
        timing="${response#*$'\t'}"
      else
        curl_exit=$?
      fi
    else
      if response="$(curl --silent --show-error --connect-timeout 1 --max-time 3 \
          --output /dev/null --write-out '%{http_code}\t%{time_total}' \
          "http://127.0.0.1:8080/actuator/health/$name" 2>/dev/null)"; then
        status="${response%%$'\t'*}"
        timing="${response#*$'\t'}"
      else
        curl_exit=$?
      fi
    fi
    status="${response%%$'\t'*}"
    timing="${response#*$'\t'}"
    if [[ "$timing" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
      elapsed="$(awk -v seconds="$timing" 'BEGIN { printf "%d", seconds * 1000 }')"
    fi
  else
    stderr_file="$tmp_dir/container-probe.$name.stderr"
    started_ms="$(date +%s%3N 2>/dev/null || true)"
    if [[ "$name" == mcpUnauthenticated ]]; then
      if timeout 3s docker exec "$backend_id" wget -T 2 -S -O /dev/null \
          --header='Content-Type: application/json' --post-data='{"jsonrpc":"2.0","id":1,"method":"ping"}' \
          http://127.0.0.1:8080/api/v1/connector/mcp >/dev/null 2>"$stderr_file"; then
        curl_exit=0
      else
        curl_exit=$?
      fi
    else
      if timeout 3s docker exec "$backend_id" wget -T 2 -S -O /dev/null \
          "http://127.0.0.1:8080/actuator/health/$name" >/dev/null 2>"$stderr_file"; then
        curl_exit=0
      else
        curl_exit=$?
      fi
    fi
    status="$(sed -nE 's/.*HTTP\/[0-9.]+[[:space:]]+([0-9]{3}).*/\1/p' "$stderr_file" | tail -n 1)"
    [[ "$status" =~ ^[0-9]{3}$ ]] || status='000'
    ended_ms="$(date +%s%3N 2>/dev/null || true)"
    if [[ "$started_ms" =~ ^[0-9]{13}$ && "$ended_ms" =~ ^[0-9]{13}$ ]] && (( ended_ms >= started_ms )); then
      elapsed=$((ended_ms - started_ms))
    fi
    if (( curl_exit == 124 )); then category='TIMEOUT'
    elif [[ "$status" == 000 ]]; then category='CONNECTION_ERROR'
    fi
  fi
  [[ "$status" =~ ^[0-9]{3}$ ]] || status='000'
  if [[ "$category" == UNKNOWN ]]; then
    if [[ "$status" == "$expected" ]]; then category='EXPECTED_HTTP'
    elif [[ "$status" == 000 ]]; then
      case "$curl_exit" in
        6) category='DNS_ERROR' ;;
        7) category='CONNECTION_REFUSED' ;;
        28|124) category='TIMEOUT' ;;
        35) category='TLS_ERROR' ;;
        52|56) category='CONNECTION_CLOSED' ;;
        *) category='CURL_ERROR' ;;
      esac
    else category='UNEXPECTED_HTTP'; fi
  fi
  probe_http="$status"
  probe_exit="$curl_exit"
  probe_category="$category"
  probe_duration="$elapsed"
}

probe_json() {
  local location="$1" name="$2" expected="$3"
  probe_one "$location" "$name" "$expected"
  printf '{"expectedHttpStatus":"%s","httpStatus":"%s","exitCode":%s,"category":"%s","durationMs":%s}' \
    "$expected" "$probe_http" "$probe_exit" "$probe_category" "$probe_duration"
}

if backend_id="$(single_container_id backend)"; then
  inspect="$(docker inspect --format '{{.Config.Image}}{{"\t"}}{{.Image}}{{"\t"}}{{.State.Status}}{{"\t"}}{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}{{"\n"}}{{range .Config.Env}}{{if eq (index (split . "=") 0) "INVESTMENT_THESIS_AI_POLICY_ENABLED"}}{{println .}}{{end}}{{if eq (index (split . "=") 0) "INVESTMENT_OS_SHEET_ENABLED"}}{{println .}}{{end}}{{if eq (index (split . "=") 0) "INVESTMENT_OS_SHEET_USER_ID"}}{{println .}}{{end}}{{if eq (index (split . "=") 0) "INVESTMENT_THESIS_AI_POLICY_MAX_EVIDENCE_AGE"}}{{println .}}{{end}}{{if eq (index (split . "=") 0) "INVESTMENT_THESIS_AI_POLICY_MINIMUM_SOURCE_COUNT"}}{{println .}}{{end}}{{if eq (index (split . "=") 0) "INVESTMENT_THESIS_AI_POLICY_MAX_TRIGGER_BELOW_PRICE_PCT"}}{{println .}}{{end}}{{end}}{{printf "runtime\t%t\t%d\t%s\t%v\n" .State.OOMKilled .RestartCount .State.StartedAt .HostConfig.PidsLimit}}{{if .State.Health}}{{range .State.Health.Log}}{{printf "healthcheck\t%d\n" .ExitCode}}{{end}}{{end}}' "$backend_id" 2>/dev/null || true)"
  inspect_header="${inspect%%$'\n'*}"
  IFS=$'\t' read -r candidate_tag candidate_image_id candidate_state candidate_health <<<"$inspect_header"
  inspect_env=''
  if [[ "$inspect" == *$'\n'* ]]; then inspect_env="${inspect#*$'\n'}"; fi
  candidate_ai_policy_enabled=''
  candidate_sheet_enabled=''
  candidate_sheet_owner_id=''
  candidate_sheet_owner_seen='false'
  candidate_max_evidence_age=''
  candidate_minimum_source_count=''
  candidate_max_trigger_pct=''
  candidate_runtime_line=''
  while IFS= read -r config_entry; do
    case "$config_entry" in
      runtime$'\t'*) candidate_runtime_line="$config_entry" ;;
      healthcheck$'\t'*)
        healthcheck_code="${config_entry#*$'\t'}"
        if [[ "$healthcheck_code" =~ ^[0-9]+$ && "$healthcheck_count" -lt 20 ]]; then
          healthcheck_exit_codes+="${healthcheck_exit_codes:+,}$healthcheck_code"
          healthcheck_count=$((healthcheck_count + 1))
        fi
        ;;
      INVESTMENT_THESIS_AI_POLICY_ENABLED=*) candidate_ai_policy_enabled="${config_entry#*=}" ;;
      INVESTMENT_OS_SHEET_ENABLED=*) candidate_sheet_enabled="${config_entry#*=}" ;;
      INVESTMENT_OS_SHEET_USER_ID=*) candidate_sheet_owner_id="${config_entry#*=}"; candidate_sheet_owner_seen='true' ;;
      INVESTMENT_THESIS_AI_POLICY_MAX_EVIDENCE_AGE=*) candidate_max_evidence_age="${config_entry#*=}" ;;
      INVESTMENT_THESIS_AI_POLICY_MINIMUM_SOURCE_COUNT=*) candidate_minimum_source_count="${config_entry#*=}" ;;
      INVESTMENT_THESIS_AI_POLICY_MAX_TRIGGER_BELOW_PRICE_PCT=*) candidate_max_trigger_pct="${config_entry#*=}" ;;
    esac
  done <<<"$inspect_env"
  if [[ "$candidate_runtime_line" == runtime$'\t'* ]]; then
    IFS=$'\t' read -r _ candidate_oom_killed candidate_restart_count candidate_started_at candidate_pids_limit <<<"$candidate_runtime_line"
    if [[ "$candidate_oom_killed" == true || "$candidate_oom_killed" == false ]]; then runtime_oom_killed="$candidate_oom_killed"; fi
    if [[ "$candidate_restart_count" =~ ^[0-9]{1,9}$ ]]; then runtime_restart_count="$candidate_restart_count"; fi
    if [[ "$candidate_started_at" =~ ^([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})([.]([0-9]{1,9}))?Z$ ]]; then
      started_fraction="${BASH_REMATCH[3]:-000}"
      while (( ${#started_fraction} < 3 )); do started_fraction+="0"; done
      runtime_started_at="\"${BASH_REMATCH[1]}.${started_fraction:0:3}Z\""
    fi
    if [[ "$candidate_pids_limit" =~ ^[0-9]{1,9}$ ]]; then runtime_pids_limit="$candidate_pids_limit"; fi
    runtime_available='true'
  fi
  if [[ "${candidate_tag:-}" =~ ^trade-backend:[0-9a-f]{40}$ ]]; then backend_tag="$candidate_tag"; fi
  if [[ "${candidate_image_id:-}" =~ ^sha256:[0-9a-f]{64}$ ]]; then backend_image_id="$candidate_image_id"; fi
  case "${candidate_state:-}" in
    created|running|paused|restarting|exited|dead) backend_state="$candidate_state" ;;
  esac
  case "${candidate_health:-}" in
    healthy|unhealthy|starting|none) backend_health="$candidate_health" ;;
  esac
  if normalized="$(safe_bool "${candidate_ai_policy_enabled:-}")"; then
    ai_policy_deployment_enabled="$normalized"
    ai_policy_deployment_source='CONTAINER_ENV'
  fi
  if normalized="$(safe_bool "${candidate_sheet_enabled:-}")"; then
    sheet_enabled="$normalized"
    sheet_enabled_source='CONTAINER_ENV'
  fi
  if [[ -n "${candidate_sheet_owner_id:-}" && "$candidate_sheet_owner_id" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$ ]]; then
    if [[ "$(printf '%s' "$candidate_sheet_owner_id" | tr '[:upper:]' '[:lower:]')" == "$owner_id" ]]; then
      sheet_owner_matches='true'
    else
      sheet_owner_matches='false'
    fi
    sheet_owner_source='CONTAINER_ENV'
  elif [[ "$candidate_sheet_owner_seen" == true && -z "${candidate_sheet_owner_id:-}" ]]; then
    sheet_owner_matches='false'
    sheet_owner_source='CONTAINER_ENV'
  fi
  if normalized="$(safe_duration_seconds "${candidate_max_evidence_age:-}")"; then deployment_max_evidence_age_seconds="$normalized"; fi
  if normalized="$(safe_source_count "${candidate_minimum_source_count:-}")"; then deployment_minimum_source_count="$normalized"; fi
  if normalized="$(safe_trigger_pct "${candidate_max_trigger_pct:-}")"; then deployment_max_trigger_below_price_pct="$normalized"; fi
  if [[ "$deployment_max_evidence_age_seconds" != null && "$deployment_minimum_source_count" != null &&
        "$deployment_max_trigger_below_price_pct" != null ]]; then
    deployment_threshold_source='CONTAINER_ENV'
  fi
fi

if [[ -n "$backend_id" ]]; then
  runtime_values="$(timeout 3s docker exec "$backend_id" sh -c '
    for key in memory.current memory.max pids.current pids.max; do
      file="/sys/fs/cgroup/$key"
      if [ -r "$file" ]; then printf "%s\t" "$key"; cat "$file"; fi
    done

    if [ -d /proc/1/task ]; then
      acceptor=0; poller=0; exec_count=0; total=0
      for comm_file in /proc/1/task/[0-9]*/comm; do
        [ -r "$comm_file" ] || continue
        IFS= read -r comm <"$comm_file" || continue
        total=$((total + 1))
        case "$comm" in
          http-nio-8080-A*) acceptor=$((acceptor + 1)) ;;
          http-nio-8080-P*) poller=$((poller + 1)) ;;
          http-nio-8080-e*) exec_count=$((exec_count + 1)) ;;
        esac
      done
      printf "threads\ttrue\t%d\t%d\t%d\t%d\n" "$acceptor" "$poller" "$exec_count" "$total"
      futex=0; epoll=0; accept_wait=0; pipe_write=0; other=0
      for wchan_file in /proc/1/task/[0-9]*/wchan; do
        [ -r "$wchan_file" ] || continue
        wchan=""
        IFS= read -r wchan <"$wchan_file" || true
        [ -n "$wchan" ] || wchan="?"
        case "$wchan" in
          *futex*) futex=$((futex + 1)) ;;
          *ep_poll*|*epoll_wait*|*do_epoll_wait*) epoll=$((epoll + 1)) ;;
          inet_csk_accept*) accept_wait=$((accept_wait + 1)) ;;
          *pipe_write*|*do_pipe_write*) pipe_write=$((pipe_write + 1)) ;;
          *) other=$((other + 1)) ;;
        esac
      done
      printf "wchan\ttrue\t%d\t%d\t%d\t%d\t%d\n" "$futex" "$epoll" "$accept_wait" "$pipe_write" "$other"
    else
      printf "threads\tfalse\t0\t0\t0\t0\n"
      printf "wchan\tfalse\t0\t0\t0\t0\t0\n"
    fi

    if [ -r /proc/net/tcp ]; then
      tcp_files=/proc/net/tcp
      if [ -r /proc/net/tcp6 ]; then tcp_files="$tcp_files /proc/net/tcp6"; fi
      awk '\''
        function hexval(value, i, digit, number) {
          value = toupper(value); number = 0
          for (i = 1; i <= length(value); i++) {
            digit = index("0123456789ABCDEF", substr(value, i, 1)) - 1
            if (digit < 0) return 0
            number = number * 16 + digit
          }
          return number
        }
        $4 == "0A" {
          local_fields = split($2, local, ":")
          if (toupper(local[local_fields]) == "1F90") {
            split($5, queue, ":")
            listeners++
            rx_queue += hexval(queue[2])
          }
        }
        END { printf "listener\ttrue\t%d\t%.0f\n", listeners, rx_queue }
      '\'' $tcp_files 2>/dev/null
    else
      printf "listener\tfalse\t0\t0\n"
    fi

    if [ -d /proc/1/fd ] && [ -r /proc/1/limits ]; then
      fd_count=0
      for fd_file in /proc/1/fd/[0-9]*; do
        [ -L "$fd_file" ] || continue
        fd_count=$((fd_count + 1))
      done
      limits="$(awk '\''$1 == "Max" && $2 == "open" && $3 == "files" {
        soft = ($4 ~ /^[0-9]+$/) ? $4 : "null"
        hard = ($5 ~ /^[0-9]+$/) ? $5 : "null"
        print soft "\t" hard
        found = 1
      } END { if (!found) print "null\tnull" }'\'' /proc/1/limits 2>/dev/null)"
      IFS="$(printf "\\t")" read -r soft_limit hard_limit <<EOF
$limits
EOF
      case "$soft_limit" in ''|*[!0-9]*) soft_limit="null" ;; esac
      case "$hard_limit" in ''|*[!0-9]*) hard_limit="null" ;; esac
      printf "fd\ttrue\t%d\t%s\t%s\n" "$fd_count" "$soft_limit" "$hard_limit"
    else
      printf "fd\tfalse\t0\tnull\tnull\n"
    fi

    if command -v jcmd >/dev/null 2>&1; then jcmd=true; else jcmd=false; fi
    if command -v jstack >/dev/null 2>&1; then jstack=true; else jstack=false; fi
    printf "javaTools\ttrue\t%s\t%s\n" "$jcmd" "$jstack"
  ' 2>/dev/null || true)"
  while IFS=$'\t' read -r key value first second third fourth fifth; do
    case "$key" in
      memory.current) if [[ "$value" =~ ^[0-9]{1,15}$ ]]; then runtime_memory_current="$value"; fi ;;
      memory.max) if [[ "$value" =~ ^[0-9]{1,15}$ ]]; then runtime_memory_limit="$value"; fi ;;
      pids.current) if [[ "$value" =~ ^[0-9]{1,9}$ ]]; then runtime_pids_current="$value"; fi ;;
      pids.max) if [[ "$value" =~ ^[0-9]{1,9}$ ]]; then runtime_pids_limit="$value"; fi ;;
      threads)
        if [[ "$value" == true && "$first" =~ ^[0-9]{1,6}$ && "$second" =~ ^[0-9]{1,6}$ &&
              "$third" =~ ^[0-9]{1,6}$ && "$fourth" =~ ^[0-9]{1,6}$ ]]; then
          thread_counts_available='true'
          thread_acceptor_count="$first"; thread_poller_count="$second"
          thread_exec_count="$third"; thread_total_count="$fourth"
        fi
        ;;
      wchan)
        if [[ "$value" == true && "$first" =~ ^[0-9]{1,6}$ && "$second" =~ ^[0-9]{1,6}$ &&
              "$third" =~ ^[0-9]{1,6}$ && "$fourth" =~ ^[0-9]{1,6}$ && "$fifth" =~ ^[0-9]{1,6}$ ]]; then
          wchan_counts_available='true'
          wchan_futex_wait_count="$first"; wchan_ep_poll_count="$second"
          wchan_inet_csk_accept_count="$third"; wchan_pipe_write_count="$fourth"
          wchan_other_count="$fifth"
        fi
        ;;
      listener)
        if [[ "$value" == true && "$first" =~ ^[0-9]{1,4}$ && "$second" =~ ^[0-9]{1,9}$ ]]; then
          port_8080_available='true'; port_8080_listener_count="$first"; port_8080_rx_queue="$second"
        fi
        ;;
      fd)
        if [[ "$value" == true && "$first" =~ ^[0-9]{1,7}$ ]]; then
          fd_diagnostics_available='true'; fd_count="$first"
          if [[ "$second" =~ ^[0-9]{1,15}$ ]]; then max_open_files_soft="$second"; fi
          if [[ "$third" =~ ^[0-9]{1,15}$ ]]; then max_open_files_hard="$third"; fi
        fi
        ;;
      javaTools)
        if [[ "$value" == true && ( "$first" == true || "$first" == false ) &&
              ( "$second" == true || "$second" == false ) ]]; then
          java_tools_available='true'
          jcmd_available="$first"; jstack_available="$second"
        fi
        ;;
    esac
  done <<<"$runtime_values"
  if logs_summary="$(timeout 5s docker logs --since 30m --tail 2000 "$backend_id" 2>&1 | awk '
    /NullPointerException/ { null_pointer++ }
    /OutOfMemoryError/ { out_of_memory++ }
    /SQLTransientConnectionException|HikariPool.*Connection is not available|Connection is not available, request timed out/ { pool_timeout++ }
    /SocketTimeoutException|ConnectTimeoutException/ { socket_timeout++ }
    /RejectedExecutionException/ { rejected_execution++ }
    END { printf "%d %d %d %d %d\n", null_pointer, out_of_memory, pool_timeout, socket_timeout, rejected_execution }
  ' 2>/dev/null)"; then
    if [[ "$logs_summary" =~ ^[0-9]+[[:space:]][0-9]+[[:space:]][0-9]+[[:space:]][0-9]+[[:space:]][0-9]+$ ]]; then
      exception_counts="$logs_summary"
      exceptions_available='true'
    fi
  fi
fi

host_liveness="$(probe_json host liveness 200)"
host_readiness="$(probe_json host readiness 200)"
host_mcp="$(probe_json host mcpUnauthenticated 401)"
container_liveness="$(probe_json container liveness 200)"
container_readiness="$(probe_json container readiness 200)"
container_mcp="$(probe_json container mcpUnauthenticated 401)"
readiness_http="$(printf '%s' "$host_readiness" | sed -nE 's/.*"httpStatus":"([0-9]{3})".*/\1/p')"
[[ "$readiness_http" =~ ^[0-9]{3}$ ]] || readiness_http='000'
readiness_status='UNKNOWN'
if readiness_body_status="$(curl --silent --show-error --connect-timeout 1 --max-time 3 \
    --output - http://127.0.0.1:8080/actuator/health/readiness 2>/dev/null | \
    grep -oE '"status"[[:space:]]*:[[:space:]]*"[A-Z_]+"' | sed -n '1p' | sed -E 's/.*"([A-Z_]+)"/\1/')"; then
  case "$readiness_body_status" in
    UP|DOWN|OUT_OF_SERVICE|UNKNOWN) readiness_status="$readiness_body_status" ;;
  esac
fi

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
  CASE WHEN to_regclass('public.investment_thesis_ai_policy_revisions') IS NULL THEN 'false' ELSE 'true' END,
  CASE WHEN to_regclass('public.investment_thesis_revisions') IS NULL THEN 'false' ELSE 'true' END;
ROLLBACK;
SQL
)
    ai_schema_metadata="$(printf '%s\n' "$ai_schema_sql" | docker exec -i "$db_id" psql -X -q -t -A -w -F $'\t' \
      -v ON_ERROR_STOP=1 -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null || true)"
    IFS=$'\t' read -r candidate_ai_events_schema candidate_ai_policy_schema candidate_thesis_revisions_schema <<<"$ai_schema_metadata"

    if [[ "${candidate_thesis_revisions_schema:-}" == true ]]; then
      thesis_revision_sql=$(cat <<'SQL'
BEGIN TRANSACTION READ ONLY;
WITH latest_revision AS (
  SELECT DISTINCT ON (user_id, ticker)
         user_id, ticker, id, actor_type, asserted_run_id, thesis_snapshot
    FROM investment_thesis_revisions
   WHERE user_id = :'owner_id'::uuid
   ORDER BY user_id, ticker, revision DESC, id DESC
), compared AS (
  SELECT state.ticker, latest.id AS revision_id,
         (latest.asserted_run_id IS NOT NULL AND length(btrim(latest.asserted_run_id)) > 0) AS proposal_run_id_present,
         CASE WHEN latest.id IS NULL THEN NULL ELSE
           latest.thesis_snapshot - 'revisionId' - 'revision' - 'approvalActorType' -
             'policyVersion' - 'verificationEventId' - 'assertedRunId' - 'updatedAt' =
           jsonb_build_object(
             'ticker', state.ticker,
             'coreThesis', state.core_thesis,
             'upsideDriver', state.upside_driver,
             'expectationsGap', state.expectations_gap,
             'fundamentalInvalidation', state.fundamental_invalidation,
             'revisionInvalidation', state.revision_invalidation,
             'priceRiskTrigger', state.price_risk_trigger,
             'priceRiskTriggerPrice', state.price_risk_trigger_price,
             'invalidationStatus', state.invalidation_status,
             'expandTrigger', state.expand_trigger,
             'exitOrDiscardTrigger', state.exit_or_discard_trigger,
             'classification', state.classification
           )
         END AS snapshot_matches
    FROM investment_thesis_states state
    LEFT JOIN latest_revision latest
      ON latest.user_id = state.user_id AND latest.ticker = state.ticker
   WHERE state.user_id = :'owner_id'::uuid
)
SELECT
  count(*) FILTER (WHERE revision_id IS NOT NULL),
  count(*) FILTER (WHERE revision_id IS NULL),
  (SELECT count(*) FROM investment_thesis_revisions WHERE user_id = :'owner_id'::uuid),
  (SELECT count(*) FROM investment_thesis_revisions
    WHERE user_id = :'owner_id'::uuid AND actor_type = 'LEGACY_BASELINE'),
  count(*) FILTER (WHERE snapshot_matches IS TRUE),
  count(*) FILTER (WHERE snapshot_matches IS FALSE),
  count(*) FILTER (WHERE proposal_run_id_present IS TRUE),
  count(*) FILTER (WHERE revision_id IS NULL OR proposal_run_id_present IS FALSE)
  FROM compared;
ROLLBACK;
SQL
)
      thesis_revision_row="$(printf '%s\n' "$thesis_revision_sql" | docker exec -i "$db_id" psql -X -q -t -A -w -F $'\t' \
        -v ON_ERROR_STOP=1 -v "owner_id=$owner_id" -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null || true)"
      IFS=$'\t' read -r candidate_with_revision candidate_without_revision candidate_revision_total \
        candidate_legacy_baseline candidate_snapshot_match candidate_snapshot_mismatch \
        candidate_with_run_id candidate_without_run_id <<<"$thesis_revision_row"
      if [[ "${candidate_with_revision:-}" =~ ^[0-9]+$ &&
            "${candidate_without_revision:-}" =~ ^[0-9]+$ &&
            "${candidate_revision_total:-}" =~ ^[0-9]+$ &&
            "${candidate_legacy_baseline:-}" =~ ^[0-9]+$ &&
            "${candidate_snapshot_match:-}" =~ ^[0-9]+$ &&
            "${candidate_snapshot_mismatch:-}" =~ ^[0-9]+$ &&
            "${candidate_with_run_id:-}" =~ ^[0-9]+$ &&
            "${candidate_without_run_id:-}" =~ ^[0-9]+$ ]]; then
        thesis_revision_diagnostics_available='true'
        thesis_with_revision_count="$candidate_with_revision"
        thesis_without_revision_count="$candidate_without_revision"
        thesis_revision_count="$candidate_revision_total"
        legacy_baseline_revision_count="$candidate_legacy_baseline"
        latest_thesis_snapshot_match_count="$candidate_snapshot_match"
        latest_thesis_snapshot_mismatch_count="$candidate_snapshot_mismatch"
        current_theses_with_proposal_run_id_count="$candidate_with_run_id"
        current_theses_without_proposal_run_id_count="$candidate_without_run_id"
      fi
    fi

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
SELECT count(*),
       coalesce((SELECT enabled::text FROM investment_thesis_ai_policy_revisions
                  WHERE user_id = :'owner_id'::uuid ORDER BY revision DESC, id DESC LIMIT 1), '#NULL#'),
       coalesce((SELECT max_evidence_age_seconds::text FROM investment_thesis_ai_policy_revisions
                  WHERE user_id = :'owner_id'::uuid ORDER BY revision DESC, id DESC LIMIT 1), '#NULL#'),
       coalesce((SELECT minimum_source_count::text FROM investment_thesis_ai_policy_revisions
                  WHERE user_id = :'owner_id'::uuid ORDER BY revision DESC, id DESC LIMIT 1), '#NULL#'),
       coalesce((SELECT max_trigger_below_price_pct::text FROM investment_thesis_ai_policy_revisions
                  WHERE user_id = :'owner_id'::uuid ORDER BY revision DESC, id DESC LIMIT 1), '#NULL#')
  FROM investment_thesis_ai_policy_revisions
 WHERE user_id = :'owner_id'::uuid;
ROLLBACK;
SQL
)
      ai_policy_revision_row="$(printf '%s\n' "$ai_policy_revision_count_sql" | docker exec -i "$db_id" psql -X -q -t -A -w -F $'\t' \
        -v ON_ERROR_STOP=1 -v "owner_id=$owner_id" -U trade -d trade -h /var/run/postgresql -f - 2>/dev/null || true)"
      IFS=$'\t' read -r candidate_policy_count candidate_policy_enabled candidate_policy_age \
        candidate_policy_source_count candidate_policy_trigger_pct <<<"$ai_policy_revision_row"
      if [[ "${candidate_policy_count:-}" =~ ^[0-9]+$ &&
            "${candidate_policy_enabled:-}" =~ ^(true|false|#NULL#)$ &&
            "${candidate_policy_age:-}" =~ ^([0-9]+|#NULL#)$ &&
            "${candidate_policy_source_count:-}" =~ ^([0-9]+|#NULL#)$ &&
            "${candidate_policy_trigger_pct:-}" =~ ^([0-9]+([.][0-9]+)?|#NULL#)$ ]]; then
        ai_policy_revisions_available='true'
        ai_policy_revision_count="$candidate_policy_count"
        if (( candidate_policy_count > 0 )) &&
           [[ "$candidate_policy_enabled" =~ ^(true|false)$ && "$candidate_policy_age" =~ ^[0-9]+$ ]] &&
           candidate_policy_age_normalized="$(safe_duration_seconds "PT${candidate_policy_age}S")" &&
           candidate_policy_source_count_normalized="$(safe_source_count "$candidate_policy_source_count")" &&
           candidate_policy_trigger_pct_normalized="$(safe_trigger_pct "$candidate_policy_trigger_pct")"; then
          owner_policy_stored_override_present='true'
          owner_policy_stored_override_enabled="$candidate_policy_enabled"
          owner_policy_effective_enabled="$candidate_policy_enabled"
          owner_policy_source='USER'
          owner_policy_max_evidence_age_seconds="$candidate_policy_age_normalized"
          owner_policy_minimum_source_count="$candidate_policy_source_count_normalized"
          owner_policy_max_trigger_below_price_pct="$candidate_policy_trigger_pct_normalized"
          deployment_threshold_source='DATABASE_USER_POLICY'
        elif (( candidate_policy_count == 0 )) &&
             [[ "$candidate_policy_enabled" == '#NULL#' && "$candidate_policy_age" == '#NULL#' &&
                "$candidate_policy_source_count" == '#NULL#' && "$candidate_policy_trigger_pct" == '#NULL#' ]]; then
          owner_policy_stored_override_present='false'
        else
          ai_policy_revisions_available='false'
          ai_policy_revision_count='0'
        fi
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

if [[ "$ai_policy_revisions_available" == true && "$ai_policy_revision_count" == 0 &&
      "$owner_policy_stored_override_present" == null ]]; then
  owner_policy_stored_override_present='false'
fi
if [[ "$ai_policy_revisions_available" == true && "$owner_policy_stored_override_present" == false &&
      "$owner_policy_source" == UNKNOWN ]]; then
  if [[ "$ai_policy_deployment_enabled" != null && "$sheet_enabled" != null && "$sheet_owner_matches" != null ]]; then
    if [[ "$ai_policy_deployment_enabled" == true && "$sheet_enabled" == true && "$sheet_owner_matches" == true ]]; then
      owner_policy_effective_enabled='true'
      owner_policy_source='DEPLOYMENT'
    else
      owner_policy_effective_enabled='false'
      owner_policy_source='DEFAULT_DISABLED'
    fi
  fi
  owner_policy_max_evidence_age_seconds="$deployment_max_evidence_age_seconds"
  owner_policy_minimum_source_count="$deployment_minimum_source_count"
  owner_policy_max_trigger_below_price_pct="$deployment_max_trigger_below_price_pct"
  if [[ "$deployment_threshold_source" == CONTAINER_ENV ]]; then
    owner_policy_source_thresholds='CONTAINER_ENV'
  else
    owner_policy_source_thresholds='UNKNOWN'
  fi
fi
if [[ "$owner_policy_stored_override_present" == true ]]; then
  owner_policy_source_thresholds='DATABASE_USER_POLICY'
fi
if [[ "$thesis_revision_diagnostics_available" == true ]]; then
  if (( thesis_count == 0 )); then
    current_thesis_snapshot_match_status='NO_THESIS'
  elif (( thesis_with_revision_count == 0 )); then
    current_thesis_snapshot_match_status='NO_REVISION'
  elif (( latest_thesis_snapshot_match_count > 0 && latest_thesis_snapshot_mismatch_count > 0 )); then
    current_thesis_snapshot_match_status='MIXED'
  elif (( latest_thesis_snapshot_mismatch_count > 0 )); then
    current_thesis_snapshot_match_status='MISMATCH'
  elif (( latest_thesis_snapshot_match_count == thesis_with_revision_count )); then
    current_thesis_snapshot_match_status='MATCH'
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
printf '"readiness":{"httpStatus":"%s","status":"%s"},' "$readiness_http" "$readiness_status"
read -r null_pointer_count oom_count pool_timeout_count socket_timeout_count rejected_execution_count <<<"$exception_counts"
printf '"httpDiagnostics":{"backendRuntime":{"available":%s,"oomKilled":%s,"restartCount":%s,"startedAt":%s,"memoryUsageBytes":%s,"memoryLimitBytes":%s,"pidsCurrent":%s,"pidsLimit":%s,"healthcheckExitCodes":[%s],' \
  "$runtime_available" "$runtime_oom_killed" "$runtime_restart_count" "$runtime_started_at" \
  "$runtime_memory_current" "$runtime_memory_limit" "$runtime_pids_current" "$runtime_pids_limit" "$healthcheck_exit_codes"
printf '"tomcatThreads":{"available":%s,"acceptor":%s,"poller":%s,"exec":%s,"total":%s},' \
  "$thread_counts_available" "$thread_acceptor_count" "$thread_poller_count" "$thread_exec_count" "$thread_total_count"
printf '"threadWaitChannels":{"available":%s,"futexWait":%s,"epPoll":%s,"inetCskAccept":%s,"pipeWrite":%s,"other":%s},' \
  "$wchan_counts_available" "$wchan_futex_wait_count" "$wchan_ep_poll_count" \
  "$wchan_inet_csk_accept_count" "$wchan_pipe_write_count" "$wchan_other_count"
printf '"port8080":{"available":%s,"listenerCount":%s,"rxQueue":%s},' \
  "$port_8080_available" "$port_8080_listener_count" "$port_8080_rx_queue"
printf '"fileDescriptors":{"available":%s,"count":%s,"maxOpenFilesSoft":%s,"maxOpenFilesHard":%s},' \
  "$fd_diagnostics_available" "$fd_count" "$max_open_files_soft" "$max_open_files_hard"
printf '"javaTools":{"available":%s,"jcmdAvailable":%s,"jstackAvailable":%s}},' \
  "$java_tools_available" "$jcmd_available" "$jstack_available"
printf '"probes":{"host":{"liveness":%s,"readiness":%s,"mcpUnauthenticated":%s},"container":{"liveness":%s,"readiness":%s,"mcpUnauthenticated":%s}},' \
  "$host_liveness" "$host_readiness" "$host_mcp" "$container_liveness" "$container_readiness" "$container_mcp"
printf '"recentExceptions":{"available":%s,"counts":{"nullPointerException":%s,"outOfMemoryError":%s,"poolTimeout":%s,"socketTimeout":%s,"rejectedExecution":%s}}},' \
  "$exceptions_available" "$null_pointer_count" "$oom_count" "$pool_timeout_count" "$socket_timeout_count" "$rejected_execution_count"
printf '"database":{"status":"%s","flywayVersion":%s,"flywaySuccess":%s,' \
  "$db_status" "$flyway_version_json" "$flyway_success"
printf '"analysisInputSnapshotCount":%s,"securitySnapshotCount":%s,"priceSnapshotCount":%s,"thesisCount":%s,"decisionCount":%s,' \
  "$analysis_input_snapshot_count" "$security_snapshot_count" "$price_snapshot_count" "$thesis_count" "$decision_count"
printf '"latestSecurityProjectionCount":%s,"latestSecurityProjectionSha256":"%s","aiVerificationPolicy":{"verificationEventsAvailable":%s,"verificationCount":%s,"approvedCount":%s,"blockedCount":%s,"rejectedCount":%s,"policyRevisionsAvailable":%s,"aiPolicyRevisionCount":%s,"deploymentEnabled":%s,"ownerScopeConfigured":%s,' \
  "$latest_security_projection_count" "${latest_security_projection_sha256:-}" \
  "$ai_verification_events_available" "$ai_verification_count" "$ai_approved_count" \
  "$ai_blocked_count" "$ai_rejected_count" "$ai_policy_revisions_available" \
  "$ai_policy_revision_count" "$ai_policy_deployment_enabled" "$ai_policy_owner_scope_configured"
printf '"effectiveOwnerPolicy":{"storedOverridePresent":%s,"storedOverrideEnabled":%s,"enabled":%s,"source":"%s",' \
  "$owner_policy_stored_override_present" "$owner_policy_stored_override_enabled" \
  "$owner_policy_effective_enabled" "$owner_policy_source"
printf '"sheetEnabled":%s,"ownerScopeMatches":%s,"configSources":{"deploymentPolicyEnabled":"%s","sheetEnabled":"%s","ownerScope":"%s","thresholds":"%s"},' \
  "$sheet_enabled" "$sheet_owner_matches" "$ai_policy_deployment_source" "$sheet_enabled_source" \
  "$sheet_owner_source" "$owner_policy_source_thresholds"
printf '"maxEvidenceAgeSeconds":%s,"minimumSourceCount":%s,"maxTriggerBelowPricePct":%s}},' \
  "$owner_policy_max_evidence_age_seconds" "$owner_policy_minimum_source_count" \
  "$owner_policy_max_trigger_below_price_pct"
printf '"thesisRevisionDiagnostics":{"available":%s,"thesesWithRevisionCount":%s,"thesesWithoutRevisionCount":%s,"revisionCount":%s,"legacyBaselineRevisionCount":%s,' \
  "$thesis_revision_diagnostics_available" "$thesis_with_revision_count" "$thesis_without_revision_count" \
  "$thesis_revision_count" "$legacy_baseline_revision_count"
printf '"currentThesesWithProposalRunIdCount":%s,"currentThesesWithoutProposalRunIdCount":%s,"latestSnapshotMatchCount":%s,"latestSnapshotMismatchCount":%s,"snapshotMatchStatus":"%s"}},' \
  "$current_theses_with_proposal_run_id_count" "$current_theses_without_proposal_run_id_count" \
  "$latest_thesis_snapshot_match_count" "$latest_thesis_snapshot_mismatch_count" \
  "$current_thesis_snapshot_match_status"

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
