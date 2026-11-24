#!/usr/bin/env python3
"""Collect free Yahoo daily OHLCV for offline exploratory research, read-only.

Only GET requests to query1.finance.yahoo.com/v8/finance/chart/{SYMBOL} are allowed.
No key, account, broker, order, or mutable endpoint is used. Redirects and environment
proxies are refused. Raw responses and SHA-256 hashes are retained.

CSV columns include provider raw OHLC/adjclose and ``adjusted_open = open * adjclose/close``.
This adjustment is suitable for exploratory open-to-open total-return approximation; it is
not broker execution evidence.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

HOST = "query1.finance.yahoo.com"
PATH = re.compile(r"/v8/finance/chart/([A-Z0-9^-]{1,15})")
SYMBOL = re.compile(r"[A-Z0-9^-]{1,15}")
INTERVAL = "1d"
EVENTS = "div,splits"
NEW_YORK = ZoneInfo("America/New_York")
USER_AGENT = "Mozilla/5.0 research-client"
RETRYABLE = frozenset({429, 500, 502, 503, 504})
CSV_FIELDS = ("date", "open", "high", "low", "close", "adjclose", "volume",
              "adjustment_factor", "adjusted_open")


class CollectionError(RuntimeError):
    pass


class _RefuseRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CollectionError(f"HTTP {code} redirect refused")


_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), _RefuseRedirects)


def validate_symbol(value: str) -> str:
    value = value.strip().upper().replace(".", "-")
    if not SYMBOL.fullmatch(value):
        raise CollectionError(f"symbol {value!r} is outside the allowlist")
    return value


def build_url(symbol: str, period1: int, period2: int) -> str:
    symbol = validate_symbol(symbol)
    if period1 < 0 or period2 <= period1:
        raise CollectionError("invalid epoch range")
    query = urllib.parse.urlencode({"period1": str(period1), "period2": str(period2),
                                    "interval": INTERVAL, "events": EVENTS})
    return f"https://{HOST}/v8/finance/chart/{symbol}?{query}"


def check_request(url: str) -> None:
    try:
        parts = urllib.parse.urlsplit(url)
    except ValueError:
        raise CollectionError("malformed URL") from None
    if parts.scheme != "https" or parts.hostname != HOST or parts.fragment:
        raise CollectionError("request must use the fixed Yahoo https host")
    match = PATH.fullmatch(parts.path)
    if match is None:
        raise CollectionError("request path is outside the chart allowlist")
    validate_symbol(match.group(1))
    query = dict(urllib.parse.parse_qsl(parts.query, keep_blank_values=True))
    if set(query) != {"period1", "period2", "interval", "events"}:
        raise CollectionError("unexpected query parameter")
    if not query["period1"].isdigit() or not query["period2"].isdigit():
        raise CollectionError("period bounds must be integer epochs")
    if query["interval"] != INTERVAL or query["events"] != EVENTS:
        raise CollectionError("interval/events outside the allowlist")


def fetch(url: str, timeout: float, retries: int, backoff: float, sleep=time.sleep) -> bytes:
    check_request(url)
    for attempt in range(retries + 1):
        request = urllib.request.Request(url, method="GET", headers={
            "User-Agent": USER_AGENT, "Accept": "application/json"})
        try:
            with _OPENER.open(request, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code in RETRYABLE and attempt < retries:
                sleep(backoff * 2 ** attempt)
                continue
            raise CollectionError(f"HTTP {error.code}") from None
        except (urllib.error.URLError, OSError) as error:
            if attempt < retries:
                sleep(backoff * 2 ** attempt)
                continue
            raise CollectionError(f"network error {type(error).__name__}") from None
    raise AssertionError("unreachable")


def _finite(value, label: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise CollectionError(f"{label} is not numeric") from None
    if not math.isfinite(number):
        raise CollectionError(f"{label} is not finite")
    return number


def parse(body: bytes, expected_symbol: str) -> tuple[list[dict], dict]:
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CollectionError(f"response is not JSON ({error})") from None
    chart = payload.get("chart") if isinstance(payload, dict) else None
    if not isinstance(chart, dict) or chart.get("error"):
        raise CollectionError("chart response carries an error")
    results = chart.get("result")
    if not isinstance(results, list) or len(results) != 1 or not isinstance(results[0], dict):
        raise CollectionError("chart response has no single result")
    result = results[0]
    meta = result.get("meta") or {}
    provider_symbol = str(meta.get("symbol") or "").upper()
    if provider_symbol != expected_symbol.upper():
        raise CollectionError(f"provider symbol {provider_symbol!r} != {expected_symbol!r}")
    timestamps = result.get("timestamp")
    quote_sets = (result.get("indicators") or {}).get("quote")
    adj_sets = (result.get("indicators") or {}).get("adjclose")
    if not isinstance(timestamps, list) or not isinstance(quote_sets, list) or len(quote_sets) != 1:
        raise CollectionError("missing timestamp/quote arrays")
    if not isinstance(adj_sets, list) or len(adj_sets) != 1:
        raise CollectionError("missing adjclose array")
    quote, adj = quote_sets[0], adj_sets[0].get("adjclose")
    arrays = {key: quote.get(key) for key in ("open", "high", "low", "close", "volume")}
    arrays["adjclose"] = adj
    if any(not isinstance(values, list) or len(values) != len(timestamps) for values in arrays.values()):
        raise CollectionError("indicator arrays do not match timestamps")
    rows = []
    for index, stamp in enumerate(timestamps):
        values = {key: arrays[key][index] for key in arrays}
        if any(value is None for value in values.values()):
            continue
        date = datetime.fromtimestamp(int(stamp), tz=timezone.utc).astimezone(NEW_YORK).date().isoformat()
        parsed = {key: _finite(value, f"{date}.{key}") for key, value in values.items()}
        if min(parsed[k] for k in ("open", "high", "low", "close", "adjclose")) <= 0:
            raise CollectionError(f"{date}: prices must be positive")
        if parsed["volume"] < 0 or parsed["high"] < max(parsed["open"], parsed["close"], parsed["low"]):
            raise CollectionError(f"{date}: invalid OHLCV")
        if parsed["low"] > min(parsed["open"], parsed["close"], parsed["high"]):
            raise CollectionError(f"{date}: invalid low")
        factor = parsed["adjclose"] / parsed["close"]
        rows.append({"date": date, **parsed, "adjustment_factor": factor,
                     "adjusted_open": parsed["open"] * factor})
    if not rows or any(a["date"] >= b["date"] for a, b in zip(rows, rows[1:])):
        raise CollectionError("rows are empty, duplicate, or unordered")
    return rows, {"exchange_timezone": meta.get("exchangeTimezoneName"),
                  "currency": meta.get("currency"), "instrument_type": meta.get("instrumentType")}


def atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as handle:
        handle.write(data); handle.flush(); os.fsync(handle.fileno())
    os.replace(temporary, path)


def csv_bytes(rows: list[dict]) -> bytes:
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=CSV_FIELDS, lineterminator="\n")
    writer.writeheader()
    for row in rows:
        writer.writerow({key: row[key] for key in CSV_FIELDS})
    return stream.getvalue().encode("utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--symbols", required=True, help="comma-separated Yahoo symbols")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--start", default="2018-01-01")
    parser.add_argument("--end-exclusive", default="2026-11-21")
    parser.add_argument("--pause", type=float, default=0.15)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--max-retries", type=int, default=4)
    parser.add_argument("--backoff", type=float, default=2.0)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args(argv)
    if args.pause < 0 or args.max_retries < 0:
        parser.error("pause/retries must be nonnegative")
    try:
        symbols = [validate_symbol(item) for item in args.symbols.split(",") if item.strip()]
        period1 = int(datetime.fromisoformat(args.start).replace(tzinfo=timezone.utc).timestamp())
        period2 = int(datetime.fromisoformat(args.end_exclusive).replace(tzinfo=timezone.utc).timestamp())
    except (CollectionError, ValueError) as error:
        parser.error(str(error))
    if not symbols or len(set(symbols)) != len(symbols):
        parser.error("symbols must be nonempty and unique after normalization")
    manifest_path = args.out / "manifest.json"
    manifest = json.loads(manifest_path.read_text()) if args.resume and manifest_path.exists() else {
        "provider": "Yahoo Finance chart", "interval": INTERVAL, "events": EVENTS,
        "start": args.start, "end_exclusive": args.end_exclusive, "symbols": {}, "errors": {}}
    failures = 0
    for position, symbol in enumerate(symbols):
        existing = manifest["symbols"].get(symbol)
        if args.resume and existing:
            path = args.out / existing["file"]
            if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == existing["sha256"]:
                print(f"[yahoo] {symbol} resumed rows={existing['rows']}")
                continue
        try:
            url = build_url(symbol, period1, period2)
            body = fetch(url, args.timeout, args.max_retries, args.backoff)
            rows, metadata = parse(body, symbol)
            raw_path = args.out / "raw" / f"{symbol}.json"
            file_path = args.out / f"{symbol}.csv"
            encoded = csv_bytes(rows)
            atomic(raw_path, body); atomic(file_path, encoded)
            manifest["symbols"][symbol] = {
                "file": file_path.name, "raw_file": f"raw/{symbol}.json",
                "sha256": hashlib.sha256(encoded).hexdigest(), "raw_sha256": hashlib.sha256(body).hexdigest(),
                "rows": len(rows), "first_date": rows[0]["date"], "last_date": rows[-1]["date"], **metadata}
            manifest["errors"].pop(symbol, None)
            print(f"[yahoo] {symbol} rows={len(rows)} {rows[0]['date']}..{rows[-1]['date']}")
        except CollectionError as error:
            failures += 1; manifest["errors"][symbol] = str(error)
            print(f"[yahoo] {symbol} error={error}", file=sys.stderr)
        atomic(manifest_path, (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode())
        if position + 1 < len(symbols):
            time.sleep(args.pause)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
