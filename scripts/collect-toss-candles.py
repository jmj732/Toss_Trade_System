#!/usr/bin/env python3
"""Collect Toss daily candles through the existing read-only broker surface for offline research.

The script only issues GET requests to
``/api/v1/broker-connections/{connectionId}/candles`` on the Spring backend, which proxies the
Toss Open API read-only candle endpoint. It never calls order, approval or account-mutating routes.

Output (one dataset directory, consumed by ``python -m app.research``):

    <out>/manifest.json          provider, endpoint, adjustment flags, per-file sha256, page provenance
    <out>/<SYMBOL>.csv            selected series (adjusted=true by default) date,open,high,low,close,volume
    <out>/unadjusted/<SYMBOL>.csv the adjusted=false series, kept to audit what "adjusted" changes
    <out>/raw/<SYMBOL>-<adj>-<n>.json raw backend envelopes for every page

Values are written exactly as the provider returned them. Missing or contradictory pages are
reported and stop the symbol; nothing is interpolated or synthesized.

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
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

DEFAULT_SYMBOLS = ["SPY", "QQQ", "IWM", "EFA", "EEM", "TLT", "IEF", "GLD"]
ENDPOINT = "/api/v1/candles"
PAGE_SIZE = 200  # backend and Toss adapter reject count > 200
NEW_YORK = ZoneInfo("America/New_York")
CSV_HEADER = ["date", "open", "high", "low", "close", "volume"]


class CollectionError(RuntimeError):
    pass


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def fetch_page(base_url: str, token: str, connection_id: str, symbol: str, adjusted: bool,
               before: str | None, timeout: float) -> dict:
    query = {"symbol": symbol, "interval": "1d", "count": str(PAGE_SIZE),
             "adjusted": "true" if adjusted else "false"}
    if before:
        query["before"] = before
    url = (f"{base_url.rstrip('/')}/api/v1/broker-connections/"
           f"{urllib.parse.quote(connection_id)}/candles?{urllib.parse.urlencode(query)}")
    request = urllib.request.Request(url, method="GET", headers={
        "Authorization": f"Bearer {token}", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        hint = " (access token expired or invalid; default TTL is 20 minutes)" if error.code == 401 else ""
        raise CollectionError(f"{symbol} adjusted={adjusted}: HTTP {error.code}{hint}") from None
    except urllib.error.URLError as error:
        raise CollectionError(f"{symbol} adjusted={adjusted}: {error.reason}") from None


def trading_date(timestamp: str) -> str:
    instant = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    return instant.astimezone(NEW_YORK).date().isoformat()


STOP_NO_CURSOR = "NO_CURSOR"        # provider says there is no older page: genuine history end
STOP_EMPTY_PAGE = "EMPTY_PAGE"      # provider returned no candles
STOP_NO_PROGRESS = "NO_PROGRESS"    # cursor present but page added no older dates: paging may be broken
STOP_MAX_PAGES = "MAX_PAGES"        # local --max-pages cap reached: history may be truncated
SUSPECT_STOPS = {STOP_NO_PROGRESS, STOP_MAX_PAGES}


def collect_series(args, token: str, symbol: str, adjusted: bool,
                   raw_dir: Path) -> tuple[list[list[str]], list[dict], str]:
    rows: dict[str, list[str]] = {}
    pages: list[dict] = []
    before: str | None = None
    stop_reason = STOP_MAX_PAGES
    for page_index in range(args.max_pages):
        envelope = fetch_page(args.backend_url, token, args.connection_id, symbol, adjusted, before, args.timeout)
        observed_at = utc_now()
        tag = "adj" if adjusted else "raw"
        (raw_dir / f"{symbol}-{tag}-{page_index:03d}.json").write_text(
            json.dumps(envelope, sort_keys=True, indent=1), encoding="utf-8")
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
        time.sleep(args.pause)
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
    reader; adjustment_basis stays UNVERIFIED until a human confirms it."""
    raw_by_date = {row[0]: row for row in raw_rows}
    previous = None
    changes = []
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
            changes.append({"date": row[0], "ratio_step": round(ratio / previous - 1, 8)})
        previous = ratio
    return {"ratio_change_count": len(changes), "largest_steps": sorted(
        changes, key=lambda item: -abs(item["ratio_step"]))[:10],
        "ohlc_ratio_mismatch_count": len(ohlc_mismatch_dates),
        "ohlc_ratio_mismatch_sample": ohlc_mismatch_dates[:10],
        "unmatched_dates": len(set(r[0] for r in adjusted_rows) ^ set(raw_by_date))}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True, type=Path, help="dataset directory (keep outside git)")
    parser.add_argument("--symbols", default=",".join(DEFAULT_SYMBOLS))
    parser.add_argument("--max-pages", type=int, default=40, help="200 bars per page")
    parser.add_argument("--pause", type=float, default=0.5, help="seconds between page requests")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--backend-url", default=os.environ.get("TRADE_BACKEND_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--connection-id", default=os.environ.get("TRADE_BROKER_CONNECTION_ID"))
    args = parser.parse_args()

    token = os.environ.get("TRADE_ACCESS_TOKEN")
    if not token or not args.connection_id:
        parser.error("TRADE_ACCESS_TOKEN and TRADE_BROKER_CONNECTION_ID (or --connection-id) are required")

    out: Path = args.out
    raw_dir = out / "raw"
    raw_dir.mkdir(parents=True, exist_ok=True)
    manifest = {"provider": "TOSS", "endpoint": ENDPOINT, "interval": "1d", "adjusted": True,
                "adjustment_basis": "UNVERIFIED", "date_basis": "America/New_York date of candle timestamp",
                "collected_at": utc_now(), "symbols": {}, "unadjusted": {}, "errors": {}, "warnings": []}
    for symbol in [s.strip().upper() for s in args.symbols.split(",") if s.strip()]:
        try:
            series = {}
            for adjusted in (True, False):
                rows, pages, stop_reason = collect_series(args, token, symbol, adjusted, raw_dir)
                if not rows:
                    raise CollectionError(f"{symbol} adjusted={adjusted}: no candles")
                relative = f"{symbol}.csv" if adjusted else f"unadjusted/{symbol}.csv"
                entry = {"file": relative, "sha256": write_csv(out / relative, rows), "rows": len(rows),
                         "first_date": rows[0][0], "last_date": rows[-1][0], "pages": pages,
                         "stop_reason": stop_reason}
                if stop_reason in SUSPECT_STOPS:
                    manifest["warnings"].append(f"{symbol} adjusted={adjusted}: paging stopped with "
                                                f"{stop_reason}; history depth is not verified")
                manifest["symbols" if adjusted else "unadjusted"][symbol] = entry
                series[adjusted] = rows
                print(f"{symbol} adjusted={adjusted}: {len(rows)} bars {rows[0][0]}..{rows[-1][0]}")
            manifest["symbols"][symbol]["adjustment_audit"] = adjustment_audit(series[True], series[False])
        except CollectionError as error:
            manifest["symbols"].pop(symbol, None)
            manifest["unadjusted"].pop(symbol, None)
            manifest["errors"][symbol] = str(error)
            print(f"error: {error}", file=sys.stderr)
    (out / "manifest.json").write_text(json.dumps(manifest, sort_keys=True, indent=2), encoding="utf-8")
    print(f"manifest: {out / 'manifest.json'}")
    return 1 if manifest["errors"] else 0


if __name__ == "__main__":
    sys.exit(main())
