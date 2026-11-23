#!/usr/bin/env python3
"""Collect Toss daily candles through the existing read-only broker surface for offline research.

The script only issues GET requests to ``/api/v1/broker-connections/{connectionId}/candles`` on the
Spring backend, which proxies the Toss Open API read-only candle endpoint. Every request is checked
against a local allowlist before any network I/O (GET only, http/https only, candles path only),
redirects are refused and environment proxies are ignored, so the bearer token only goes to the
configured backend. It never calls order, approval or account-mutating routes.

Output (one dataset directory, consumed by ``python -m app.research``):

    <out>/manifest.json          provider, endpoint, adjustment flags, per-file sha256, page provenance,
                                 retry counts, adjustment_classification and adjustment_basis
    <out>/<SYMBOL>.csv            selected series (adjusted=true by default) date,open,high,low,close,volume
    <out>/unadjusted/<SYMBOL>.csv the adjusted=false series, kept to audit what "adjusted" changes
    <out>/raw/<SYMBOL>-<adj>-<n>.json raw backend envelope of every page (last attempt kept)

Values are written exactly as the provider returned them. Missing or contradictory pages are
reported and stop the symbol; nothing is interpolated or synthesized.

Durability and resume: manifest.json is replaced atomically after every symbol. An existing manifest
is never overwritten silently; pass --resume to continue it. On resume a symbol is skipped only when
both of its series exist, their CSV sha256 values match the manifest, neither stop_reason is suspect
and it has no recorded error. Anything else is recollected.

Retries: provider rate-limit/timeout envelopes and HTTP 429/502/503/504 are retried up to
--max-retries times with exponential backoff (retry k waits --backoff * 2**k seconds). A Retry-After
seconds header replaces the computed wait. Waits are capped at 120 seconds. HTTP 401 raises
AuthExpired: the run stops before any remaining symbol and the manifest is still written.

Adjustment classification, rule adj-rule-v1 (pre-registered; recomputed at the end of every run and by
--classify-only, which needs neither network nor token). Every check records its observed value:
    symbol_integrity       every collected symbol: ohlc_ratio_mismatch_count == 0, unmatched_dates == 0,
                           and no suspect stop_reason in either series
    collection_errors      no requested symbol failed to collect
    gld_control            GLD present and ratio_change_count == 0 (GLD makes no distributions)
    spy_present            SPY present
    spy_change_frequency   SPY ratio_change_count >= 3 * years, years = SPY rows / 252
    spy_no_negative_steps  SPY negative_step_count == 0
    spy_small_steps        every SPY largest_steps |ratio_step| < 0.05
Result:
    SPLIT_AND_DIVIDEND  every check passes
    NONE_DETECTED       SPY present, no collection error, integrity holds, and every symbol has
                        ratio_change_count == 0 (adjusted == raw)
    UNVERIFIED          anything else
The result is stored in manifest["adjustment_classification"]. manifest["adjustment_basis"] (the field the
engine reads) is SPLIT_AND_DIVIDEND only when the result is SPLIT_AND_DIVIDEND, otherwise UNVERIFIED.

Environment:
    TRADE_BACKEND_URL           e.g. http://127.0.0.1:8080
    TRADE_ACCESS_TOKEN          Bearer access token of the signed-in user (never written to disk)
    TRADE_BROKER_CONNECTION_ID  owned Toss broker connection UUID
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

DEFAULT_SYMBOLS = ["SPY", "QQQ", "IWM", "EFA", "EEM", "TLT", "IEF", "GLD"]
ENDPOINT = "/api/v1/broker-connections/{connectionId}/candles"
PAGE_SIZE = 200  # backend and Toss adapter reject count > 200
NEW_YORK = ZoneInfo("America/New_York")
CSV_HEADER = ["date", "open", "high", "low", "close", "volume"]
MANIFEST_NAME = "manifest.json"

RULE_VERSION = "adj-rule-v1"
TRADING_DAYS_PER_YEAR = 252
SPY_MIN_CHANGES_PER_YEAR = 3
SPY_MAX_STEP = 0.05

ALLOWED_METHOD = "GET"
ALLOWED_SCHEMES = frozenset({"http", "https"})
CANDLES_PATH = re.compile(r"/api/v1/broker-connections/[0-9a-fA-F-]{36}/candles")
RETRY_ENVELOPE_REASONS = frozenset({"PROVIDER_RATE_LIMITED", "PROVIDER_TIMEOUT"})
RETRY_HTTP_CODES = frozenset({429, 502, 503, 504})
RETRY_AFTER_CAP = 120.0


class CollectionError(RuntimeError):
    pass


class AuthExpired(CollectionError):
    """HTTP 401: the access token is expired or invalid. Remaining symbols must not be requested."""


class TransientHTTP(CollectionError):
    def __init__(self, reason: str, retry_after: float | None):
        super().__init__(reason)
        self.reason = reason
        self.retry_after = retry_after


class _RefuseRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CollectionError(f"HTTP {code} redirect refused; the collector does not follow redirects")


# An explicit empty ProxyHandler keeps the bearer token away from any proxy set in the environment.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), _RefuseRedirects)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def check_read_only_request(method: str, url: str) -> None:
    """Order-authority guard. Runs before any network I/O; the token never appears in its messages."""
    if method != ALLOWED_METHOD:
        raise CollectionError(f"request method {method!r} refused; only {ALLOWED_METHOD} is allowed")
    try:
        parts = urllib.parse.urlsplit(url)
        hostname = parts.hostname
    except ValueError:
        raise CollectionError("backend URL is malformed") from None
    if parts.scheme not in ALLOWED_SCHEMES or not hostname:
        raise CollectionError("backend URL must be an http(s) URL with a host")
    if not CANDLES_PATH.fullmatch(parts.path):
        raise CollectionError("request path is outside the read-only candles allowlist")


def parse_retry_after(value: str | None) -> float | None:
    """Only the delta-seconds form is honored; HTTP-date values fall back to backoff."""
    if value is None:
        return None
    try:
        seconds = float(value.strip())
    except ValueError:
        return None
    if not math.isfinite(seconds) or seconds < 0:
        return None
    return seconds


def fetch_page(base_url: str, token: str, connection_id: str, symbol: str, adjusted: bool,
               before: str | None, timeout: float) -> dict:
    """One attempt. Raises AuthExpired on 401, TransientHTTP on retryable HTTP status, else CollectionError."""
    query = {"symbol": symbol, "interval": "1d", "count": str(PAGE_SIZE),
             "adjusted": "true" if adjusted else "false"}
    if before:
        query["before"] = before
    url = (f"{base_url.rstrip('/')}/api/v1/broker-connections/"
           f"{urllib.parse.quote(connection_id)}/candles?{urllib.parse.urlencode(query)}")
    request = urllib.request.Request(url, method=ALLOWED_METHOD, headers={
        "Authorization": f"Bearer {token}", "Accept": "application/json"})
    check_read_only_request(request.get_method(), url)
    label = f"{symbol} adjusted={adjusted}"
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            body = response.read()
    except urllib.error.HTTPError as error:
        if error.code == 401:
            raise AuthExpired(f"{label}: HTTP 401 (access token expired or invalid; "
                              "default TTL is 20 minutes)") from None
        if error.code in RETRY_HTTP_CODES:
            raise TransientHTTP(f"HTTP {error.code}",
                                parse_retry_after(error.headers.get("Retry-After"))) from None
        raise CollectionError(f"{label}: HTTP {error.code}") from None
    except (urllib.error.URLError, OSError) as error:
        reason = error.reason if isinstance(error, urllib.error.URLError) else type(error).__name__
        raise CollectionError(f"{label}: {reason}") from None
    try:
        envelope = json.loads(body.decode("utf-8"))
    except ValueError:
        raise CollectionError(f"{label}: response is not JSON") from None
    if not isinstance(envelope, dict):
        raise CollectionError(f"{label}: response is not a JSON object")
    return envelope


def fetch_with_retry(args, token: str, symbol: str, adjusted: bool, before: str | None,
                     retries: Counter, sleep, raw_path: Path) -> dict:
    """Retries rate-limit/timeout envelopes and 429/502/503/504 up to args.max_retries times."""
    attempt = 0
    while True:
        retry_after = None
        try:
            envelope = fetch_page(args.backend_url, token, args.connection_id, symbol, adjusted,
                                  before, args.timeout)
        except TransientHTTP as error:
            reason, retry_after = error.reason, error.retry_after
        else:
            raw_path.write_text(json.dumps(envelope, sort_keys=True, indent=1), encoding="utf-8")
            if not (envelope.get("unavailable")
                    and envelope.get("unavailableReason") in RETRY_ENVELOPE_REASONS):
                return envelope
            reason = envelope["unavailableReason"]
        if attempt >= args.max_retries:
            raise CollectionError(f"{symbol} adjusted={adjusted}: {reason} persisted after "
                                  f"{attempt} retries")
        delay = retry_after if retry_after is not None else args.backoff * 2 ** attempt
        retries[reason] += 1
        sleep(min(delay, RETRY_AFTER_CAP))
        attempt += 1


def trading_date(timestamp: str) -> str:
    instant = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    return instant.astimezone(NEW_YORK).date().isoformat()


STOP_NO_CURSOR = "NO_CURSOR"        # provider says there is no older page: genuine history end
STOP_EMPTY_PAGE = "EMPTY_PAGE"      # provider returned no candles
STOP_NO_PROGRESS = "NO_PROGRESS"    # cursor present but page added no older dates: paging may be broken
STOP_MAX_PAGES = "MAX_PAGES"        # local --max-pages cap reached: history may be truncated
SUSPECT_STOPS = {STOP_NO_PROGRESS, STOP_MAX_PAGES}


def collect_series(args, token: str, symbol: str, adjusted: bool, raw_dir: Path,
                   retries: Counter, sleep) -> tuple[list[list[str]], list[dict], str]:
    tag = "adj" if adjusted else "raw"
    for stale in list(raw_dir.glob(f"{symbol}-{tag}-*.json")):
        stale.unlink()
    rows: dict[str, list[str]] = {}
    pages: list[dict] = []
    before: str | None = None
    stop_reason = STOP_MAX_PAGES
    for page_index in range(args.max_pages):
        raw_path = raw_dir / f"{symbol}-{tag}-{page_index:03d}.json"
        envelope = fetch_with_retry(args, token, symbol, adjusted, before, retries, sleep, raw_path)
        observed_at = utc_now()
        status = envelope.get("status")
        data = envelope.get("data")
        if envelope.get("unavailable") or data is None:
            raise CollectionError(f"{symbol} adjusted={adjusted}: unavailable "
                                  f"({envelope.get('unavailableReason')})")
        if status != "AVAILABLE":
            raise CollectionError(f"{symbol} adjusted={adjusted}: status={status} "
                                  f"unknownFields={envelope.get('unknownFields')}")
        if data.get("symbol", "").upper() != symbol or data.get("interval") != "1d" \
                or bool(data.get("adjusted")) != adjusted:
            raise CollectionError(f"{symbol} adjusted={adjusted}: series contract mismatch")
        candles = data.get("candles") or []
        new_dates = 0
        for candle in candles:
            date = trading_date(candle["timestamp"])
            row = [date, str(candle["openPrice"]), str(candle["highPrice"]), str(candle["lowPrice"]),
                   str(candle["closePrice"]), str(candle["volume"])]
            if date in rows and rows[date] != row:
                raise CollectionError(f"{symbol} adjusted={adjusted}: conflicting bars for {date}")
            if date not in rows:
                new_dates += 1
            rows[date] = row
        pages.append({"before": before, "observed_at": observed_at, "count": len(candles),
                      "provenance": envelope.get("provenance")})
        next_before = data.get("nextBefore")
        if not candles:
            stop_reason = STOP_EMPTY_PAGE
            break
        if not next_before:
            stop_reason = STOP_NO_CURSOR
            break
        if new_dates == 0 or next_before == before:
            stop_reason = STOP_NO_PROGRESS
            break
        before = next_before
        if args.pause > 0:
            sleep(args.pause)
    if stop_reason in SUSPECT_STOPS:
        print(f"warning: {symbol} adjusted={adjusted} stopped with {stop_reason}; "
              "history may be truncated, not genuinely short", file=sys.stderr)
    return [rows[date] for date in sorted(rows)], pages, stop_reason


def write_csv(path: Path, rows: list[list[str]]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(CSV_HEADER)
        writer.writerows(rows)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def adjustment_audit(adjusted_rows: list[list[str]], raw_rows: list[list[str]]) -> dict:
    """Dates where the adjusted/unadjusted close ratio changes. Small, frequent steps point to
    dividend adjustment; rare large steps point to split-only adjustment. Inference is left to the
    classification in classify_adjustment."""
    raw_by_date = {row[0]: row for row in raw_rows}
    previous = None
    changes = []
    positive = negative = 0
    ohlc_mismatch_dates = []
    for row in adjusted_rows:
        raw = raw_by_date.get(row[0])
        if raw is None or any(float(raw[i]) <= 0 for i in range(1, 5)):
            continue
        # The engine fills at the open, so open/high/low must carry the same factor as close.
        ratios = [float(row[i]) / float(raw[i]) for i in range(1, 5)]
        if max(ratios) / min(ratios) - 1 > 1e-4:
            ohlc_mismatch_dates.append(row[0])
        ratio = ratios[3]
        if previous is not None and abs(ratio / previous - 1) > 1e-6:
            step = ratio / previous - 1
            if step > 0:
                positive += 1
            else:
                negative += 1
            changes.append({"date": row[0], "ratio_step": round(step, 8)})
        previous = ratio
    return {"ratio_change_count": len(changes), "positive_step_count": positive,
            "negative_step_count": negative, "largest_steps": sorted(
        changes, key=lambda item: -abs(item["ratio_step"]))[:10],
        "ohlc_ratio_mismatch_count": len(ohlc_mismatch_dates),
        "ohlc_ratio_mismatch_sample": ohlc_mismatch_dates[:10],
        "unmatched_dates": len(set(r[0] for r in adjusted_rows) ^ set(raw_by_date))}


def classify_adjustment(manifest: dict) -> dict:
    """Pure function of the manifest (rule adj-rule-v1). See the module docstring for the checks."""
    symbols = manifest.get("symbols") or {}
    raw_entries = manifest.get("unadjusted") or {}
    errors = manifest.get("errors") or {}

    integrity: dict[str, dict] = {}
    for symbol in sorted(symbols):
        entry = symbols[symbol] or {}
        audit = entry.get("adjustment_audit") or {}
        raw_entry = raw_entries.get(symbol) or {}
        stops = [entry.get("stop_reason"), raw_entry.get("stop_reason")]
        passed = (bool(audit) and bool(raw_entry)
                  and audit.get("ohlc_ratio_mismatch_count") == 0
                  and audit.get("unmatched_dates") == 0
                  and not any(stop in SUSPECT_STOPS for stop in stops))
        integrity[symbol] = {"ohlc_ratio_mismatch_count": audit.get("ohlc_ratio_mismatch_count"),
                             "unmatched_dates": audit.get("unmatched_dates"),
                             "stop_reasons": stops, "passed": passed}
    integrity_ok = all(item["passed"] for item in integrity.values())

    checks: dict[str, dict] = {}
    checks["symbol_integrity"] = {"observed": integrity, "passed": integrity_ok}
    checks["collection_errors"] = {"observed": sorted(errors), "passed": not errors}

    gld = symbols.get("GLD")
    gld_changes = ((gld or {}).get("adjustment_audit") or {}).get("ratio_change_count")
    checks["gld_control"] = {"observed": gld_changes,
                             "passed": gld is not None and gld_changes == 0}

    spy = symbols.get("SPY")
    spy_audit = (spy or {}).get("adjustment_audit") or {}
    checks["spy_present"] = {"observed": spy is not None, "passed": spy is not None}

    rows = (spy or {}).get("rows")
    years = rows / TRADING_DAYS_PER_YEAR if isinstance(rows, int) and rows > 0 else None
    changes = spy_audit.get("ratio_change_count")
    required = SPY_MIN_CHANGES_PER_YEAR * years if years is not None else None
    checks["spy_change_frequency"] = {
        "observed": {"rows": rows, "years": years, "ratio_change_count": changes,
                     "required_min": required},
        "passed": required is not None and isinstance(changes, int) and changes >= required}

    negative = spy_audit.get("negative_step_count")
    checks["spy_no_negative_steps"] = {"observed": negative, "passed": negative == 0}

    largest = max((abs(item["ratio_step"]) for item in spy_audit.get("largest_steps", [])),
                  default=None)
    checks["spy_small_steps"] = {"observed": largest,
                                 "passed": bool(spy_audit) and (largest is None or largest < SPY_MAX_STEP)}

    if all(check["passed"] for check in checks.values()):
        result = "SPLIT_AND_DIVIDEND"
    elif (spy is not None and not errors and integrity_ok and bool(symbols)
          and all((((entry or {}).get("adjustment_audit") or {}).get("ratio_change_count") == 0)
                  for entry in symbols.values())):
        result = "NONE_DETECTED"
    else:
        result = "UNVERIFIED"
    return {"rule_version": RULE_VERSION, "checks": checks, "result": result}


def apply_classification(manifest: dict) -> dict:
    classification = classify_adjustment(manifest)
    manifest["adjustment_classification"] = classification
    # The engine loader accepts only its own basis vocabulary; anything short of a confirmed
    # SPLIT_AND_DIVIDEND is UNVERIFIED there (and verdicts are held). The finer result stays in
    # adjustment_classification for the reader.
    manifest["adjustment_basis"] = ("SPLIT_AND_DIVIDEND" if classification["result"] == "SPLIT_AND_DIVIDEND"
                                    else "UNVERIFIED")
    return classification


def write_manifest(out: Path, manifest: dict) -> None:
    out.mkdir(parents=True, exist_ok=True)
    target = out / MANIFEST_NAME
    temporary = out / (MANIFEST_NAME + ".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        handle.write(json.dumps(manifest, sort_keys=True, indent=2))
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, target)


def read_manifest(out: Path) -> dict:
    manifest = json.loads((out / MANIFEST_NAME).read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise CollectionError("manifest is not a JSON object")
    for key, default in (("symbols", {}), ("unadjusted", {}), ("errors", {}), ("warnings", []),
                         ("resumed_at", []), ("retry_total", 0)):
        manifest.setdefault(key, default)
    return manifest


def series_verified(out: Path, entry: dict) -> bool:
    expected = entry.get("sha256")
    try:
        return bool(expected) and hashlib.sha256(
            (out / entry["file"]).read_bytes()).hexdigest() == expected
    except (OSError, KeyError, TypeError):
        return False


def symbol_verified(out: Path, manifest: dict, symbol: str, max_pages: int) -> bool:
    adjusted = manifest["symbols"].get(symbol) or {}
    raw = manifest["unadjusted"].get(symbol) or {}
    if not adjusted or not raw or symbol in manifest["errors"] or "adjustment_audit" not in adjusted:
        return False
    for entry in (adjusted, raw):
        reason = entry.get("stop_reason")
        if reason == STOP_NO_PROGRESS:
            return False
        # A MAX_PAGES series is final for this cap: re-collecting with the same (or a lower)
        # --max-pages returns the same truncated window. Only a higher cap justifies a refetch.
        if reason == STOP_MAX_PAGES and len(entry.get("pages") or []) < max_pages:
            return False
    return series_verified(out, adjusted) and series_verified(out, raw)


def drop_symbol(manifest: dict, symbol: str) -> None:
    manifest["symbols"].pop(symbol, None)
    manifest["unadjusted"].pop(symbol, None)
    manifest["errors"].pop(symbol, None)
    manifest["warnings"] = [w for w in manifest["warnings"] if not w.startswith(f"{symbol} ")]


def collect_symbol(args, token: str, symbol: str, manifest: dict, out: Path, sleep) -> None:
    raw_dir = out / "raw"
    series = {}
    for adjusted in (True, False):
        retries: Counter = Counter()
        try:
            rows, pages, stop_reason = collect_series(args, token, symbol, adjusted, raw_dir,
                                                      retries, sleep)
        finally:
            manifest["retry_total"] += sum(retries.values())
        if not rows:
            raise CollectionError(f"{symbol} adjusted={adjusted}: no candles")
        relative = f"{symbol}.csv" if adjusted else f"unadjusted/{symbol}.csv"
        entry = {"file": relative, "sha256": write_csv(out / relative, rows), "rows": len(rows),
                 "first_date": rows[0][0], "last_date": rows[-1][0], "pages": pages,
                 "stop_reason": stop_reason, "retries": sum(retries.values()),
                 "retry_reasons": dict(sorted(retries.items()))}
        if stop_reason in SUSPECT_STOPS:
            manifest["warnings"].append(f"{symbol} adjusted={adjusted}: paging stopped with "
                                        f"{stop_reason}; history depth is not verified")
        manifest["symbols" if adjusted else "unadjusted"][symbol] = entry
        series[adjusted] = rows
        print(f"{symbol} adjusted={adjusted}: {len(rows)} bars {rows[0][0]}..{rows[-1][0]}")
    manifest["symbols"][symbol]["adjustment_audit"] = adjustment_audit(series[True], series[False])


def classify_only(out: Path) -> int:
    try:
        manifest = read_manifest(out)
    except (OSError, ValueError, CollectionError) as error:
        print(f"error: cannot read manifest in {out}: {error}", file=sys.stderr)
        return 2
    classification = apply_classification(manifest)
    write_manifest(out, manifest)
    print(f"adjustment: {classification['result']} ({RULE_VERSION})")
    print(f"manifest: {out / MANIFEST_NAME}")
    return 0


def main(argv: list[str] | None = None, sleep=time.sleep) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, type=Path, help="dataset directory (keep outside git)")
    parser.add_argument("--symbols", default=",".join(DEFAULT_SYMBOLS))
    parser.add_argument("--max-pages", type=int, default=40, help="200 bars per page")
    parser.add_argument("--pause", type=float, default=1.0,
                        help="seconds between page requests (shared Toss rate budget with live schedulers)")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--max-retries", type=int, default=5,
                        help="retries per request on rate limit, timeout or HTTP 429/502/503/504")
    parser.add_argument("--backoff", type=float, default=2.0,
                        help="base seconds; retry k waits backoff * 2**k (Retry-After wins, capped at 120)")
    parser.add_argument("--resume", action="store_true", help="continue the manifest already in --out")
    parser.add_argument("--classify-only", action="store_true",
                        help="recompute adjustment classification of the existing manifest; offline")
    parser.add_argument("--backend-url", default=os.environ.get("TRADE_BACKEND_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--connection-id", default=os.environ.get("TRADE_BROKER_CONNECTION_ID"))
    args = parser.parse_args(argv)

    if args.classify_only:
        return classify_only(args.out)

    token = os.environ.get("TRADE_ACCESS_TOKEN")
    if not token or not args.connection_id:
        parser.error("TRADE_ACCESS_TOKEN and TRADE_BROKER_CONNECTION_ID (or --connection-id) are required")
    if args.max_retries < 0:
        parser.error("--max-retries must be >= 0")

    out: Path = args.out
    manifest_exists = (out / MANIFEST_NAME).exists()
    if manifest_exists and not args.resume:
        print(f"error: {out / MANIFEST_NAME} already exists; pass --resume to continue it or use a new --out",
              file=sys.stderr)
        return 2
    if manifest_exists:
        try:
            manifest = read_manifest(out)
        except (OSError, ValueError, CollectionError) as error:
            print(f"error: cannot read existing manifest: {error}", file=sys.stderr)
            return 2
        manifest["resumed_at"].append(utc_now())
    else:
        manifest = {"provider": "TOSS", "endpoint": ENDPOINT, "interval": "1d", "adjusted": True,
                    "adjustment_basis": "UNVERIFIED", "date_basis": "America/New_York date of candle timestamp",
                    "collected_at": utc_now(), "resumed_at": [], "retry_total": 0,
                    "symbols": {}, "unadjusted": {}, "errors": {}, "warnings": []}
    (out / "raw").mkdir(parents=True, exist_ok=True)

    halted = False
    for symbol in [s.strip().upper() for s in args.symbols.split(",") if s.strip()]:
        if args.resume and symbol_verified(out, manifest, symbol, args.max_pages):
            print(f"{symbol}: verified in earlier run, skipped")
            continue
        drop_symbol(manifest, symbol)
        try:
            collect_symbol(args, token, symbol, manifest, out, sleep)
        except AuthExpired as error:
            drop_symbol(manifest, symbol)
            manifest["errors"][symbol] = str(error)
            print(f"error: {error}", file=sys.stderr)
            halted = True
        except CollectionError as error:
            drop_symbol(manifest, symbol)
            manifest["errors"][symbol] = str(error)
            print(f"error: {error}", file=sys.stderr)
        write_manifest(out, manifest)
        if halted:
            break

    classification = apply_classification(manifest)
    write_manifest(out, manifest)
    print(f"adjustment: {classification['result']} ({RULE_VERSION})")
    print(f"manifest: {out / MANIFEST_NAME}")
    if halted:
        print("error: access token rejected (HTTP 401); stopped before the remaining symbols. "
              "rerun with a fresh TRADE_ACCESS_TOKEN and --resume", file=sys.stderr)
        return 1
    return 1 if manifest["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
