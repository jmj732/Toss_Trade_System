#!/usr/bin/env python3
"""Fetch historical cash dividends from Yahoo Finance's public chart endpoint, read-only.

Used only to build dividend inputs for the offline total-return check
(``python -m app.research.total_return``). The script issues GET requests to exactly one
URL shape::

    https://query1.finance.yahoo.com/v8/finance/chart/{SYMBOL}?period1=0&period2=<now>&interval=1mo&events=div

Every request is checked against a local allowlist before any network I/O (GET only, https only,
host and path fixed, symbol limited to [A-Z.-]{1,10}). Redirects are refused and environment
proxies are ignored. No API key is used and no account, order or broker endpoint is touched.

Output (one directory, consumed by ``app.research.total_return --dividends``)::

    <out>/<SYMBOL>.json    symbol, source_url, fetched_at, raw_file, raw_sha256,
                           timezone, events: [{ex_date (America/New_York, ISO), amount}]
    <out>/raw/<SYMBOL>.json  the exact response body, so the parsed events can be re-derived

Writes are atomic (temporary file, fsync, os.replace). A symbol whose request or parsing fails is
reported and writes nothing for that symbol; its previous files, if any, are left untouched.
Dividend amounts are written exactly as provided. Nothing is interpolated or synthesized.
"""

from __future__ import annotations

import argparse
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
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

HOST = "query1.finance.yahoo.com"
PATH_PATTERN = re.compile(r"/v8/finance/chart/([A-Z.\-]{1,10})")
SYMBOL_PATTERN = re.compile(r"[A-Z.\-]{1,10}")
REQUIRED_QUERY = {"period1": "0", "interval": "1mo", "events": "div"}
USER_AGENT = "Mozilla/5.0"
NEW_YORK = ZoneInfo("America/New_York")
RETRY_HTTP_CODES = frozenset({429, 500, 502, 503, 504})
RAW_DIRECTORY = "raw"
DEFAULT_SYMBOLS = ("KO", "JNJ", "XOM", "AMZN")


class FetchError(RuntimeError):
    """Request refused by the allowlist, failed upstream or returned an unusable payload."""


class _RefuseRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise FetchError(f"HTTP {code} redirect refused; the fetcher does not follow redirects")


# An explicit empty ProxyHandler keeps requests away from any proxy set in the environment.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}), _RefuseRedirects)


def validate_symbol(symbol: str) -> str:
    if not SYMBOL_PATTERN.fullmatch(symbol):
        raise FetchError(f"symbol {symbol!r} is outside [A-Z.-]{{1,10}}")
    return symbol


def build_url(symbol: str, now_epoch: int) -> str:
    validate_symbol(symbol)
    query = {"period1": "0", "period2": str(now_epoch), "interval": "1mo", "events": "div"}
    return f"https://{HOST}/v8/finance/chart/{symbol}?{urllib.parse.urlencode(query)}"


def check_read_only_request(method: str, url: str) -> None:
    """Allowlist guard. Runs before any network I/O; refuses anything outside the chart endpoint."""
    if method != "GET":
        raise FetchError(f"request method {method!r} refused; only GET is allowed")
    try:
        parts = urllib.parse.urlsplit(url)
        hostname = parts.hostname
    except ValueError:
        raise FetchError("request URL is malformed") from None
    if parts.scheme != "https" or hostname != HOST:
        raise FetchError("request must be an https URL to the Yahoo chart host")
    match = PATH_PATTERN.fullmatch(parts.path)
    if match is None:
        raise FetchError("request path is outside the chart allowlist")
    validate_symbol(match.group(1))
    query = dict(urllib.parse.parse_qsl(parts.query, keep_blank_values=True))
    period2 = query.pop("period2", "")
    if not period2.isdigit():
        raise FetchError("period2 must be an integer epoch")
    if query != REQUIRED_QUERY:
        raise FetchError("query parameters are outside the dividend allowlist")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def epoch_to_new_york_date(epoch: int) -> str:
    return datetime.fromtimestamp(epoch, tz=timezone.utc).astimezone(NEW_YORK).date().isoformat()


def parse_dividends(body: bytes) -> list[dict]:
    """Dividend events from a chart response. Empty list when the symbol has none."""
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise FetchError(f"response is not JSON ({error})") from error
    chart = payload.get("chart") if isinstance(payload, dict) else None
    if not isinstance(chart, dict):
        raise FetchError("response has no chart object")
    if chart.get("error"):
        raise FetchError(f"provider error: {chart['error']}")
    results = chart.get("result")
    if not isinstance(results, list) or not results or not isinstance(results[0], dict):
        raise FetchError("response has no chart result")
    events = results[0].get("events") or {}
    dividends = events.get("dividends") or {}
    if not isinstance(dividends, dict):
        raise FetchError("dividends must be an object keyed by epoch")
    parsed: list[dict] = []
    for key, value in dividends.items():
        if not isinstance(value, dict):
            raise FetchError(f"dividend entry {key} is not an object")
        try:
            epoch = int(value.get("date", key))
            amount = float(value["amount"])
        except (KeyError, TypeError, ValueError) as error:
            raise FetchError(f"dividend entry {key} is malformed ({error})") from error
        if not math.isfinite(amount):
            raise FetchError(f"dividend entry {key} has a non-finite amount")
        parsed.append({"ex_date": epoch_to_new_york_date(epoch), "amount": amount})
    parsed.sort(key=lambda item: item["ex_date"])
    dates = [item["ex_date"] for item in parsed]
    if len(dates) != len(set(dates)):
        raise FetchError("duplicate ex-dates in the dividend list")
    return parsed


def fetch_body(url: str, *, timeout: float, max_retries: int, backoff: float, sleep) -> bytes:
    """GET with retry on 429 and 5xx. Waits backoff * 2**k seconds before retry k."""
    check_read_only_request("GET", url)
    attempt = 0
    while True:
        request = urllib.request.Request(url, method="GET", headers={"User-Agent": USER_AGENT,
                                                                       "Accept": "application/json"})
        try:
            with _OPENER.open(request, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code in RETRY_HTTP_CODES and attempt < max_retries:
                sleep(backoff * (2**attempt))
                attempt += 1
                continue
            raise FetchError(f"HTTP {error.code} from the chart endpoint") from None
        except urllib.error.URLError as error:
            raise FetchError(f"network error ({error.reason})") from None


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def fetch_symbol(symbol: str, out: Path, *, now_epoch: int, fetched_at: str, timeout: float,
                 max_retries: int, backoff: float, sleep) -> dict:
    url = build_url(symbol, now_epoch)
    body = fetch_body(url, timeout=timeout, max_retries=max_retries, backoff=backoff, sleep=sleep)
    events = parse_dividends(body)
    raw_name = f"{RAW_DIRECTORY}/{symbol}.json"
    raw_sha = hashlib.sha256(body).hexdigest()
    document = {
        "symbol": symbol,
        "source_url": url,
        "fetched_at": fetched_at,
        "timezone": "America/New_York",
        "raw_file": raw_name,
        "raw_sha256": raw_sha,
        "events": events,
    }
    encoded = (json.dumps(document, sort_keys=True, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    write_atomic(out / RAW_DIRECTORY / f"{symbol}.json", body)
    write_atomic(out / f"{symbol}.json", encoded)
    return document


def main(argv: list[str] | None = None, sleep=time.sleep, clock=time.time) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--symbols", default=",".join(DEFAULT_SYMBOLS),
                        help="comma-separated symbols, e.g. KO,JNJ")
    parser.add_argument("--out", required=True, type=Path, help="dividends directory (keep outside git)")
    parser.add_argument("--pause", type=float, default=1.0, help="seconds between symbol requests")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--max-retries", type=int, default=3, help="retries on 429 and 5xx")
    parser.add_argument("--backoff", type=float, default=2.0, help="base seconds for retry backoff")
    args = parser.parse_args(argv)

    symbols = [item.strip() for item in args.symbols.split(",") if item.strip()]
    if not symbols:
        parser.error("--symbols is empty")
    try:
        for symbol in symbols:
            validate_symbol(symbol)
    except FetchError as error:
        parser.error(str(error))
    if args.max_retries < 0 or args.pause < 0:
        parser.error("--max-retries and --pause must be >= 0")

    failures = 0
    for index, symbol in enumerate(symbols):
        if index:
            sleep(args.pause)
        now_epoch = int(clock())
        try:
            document = fetch_symbol(symbol, args.out, now_epoch=now_epoch, fetched_at=utc_now(),
                                    timeout=args.timeout, max_retries=args.max_retries,
                                    backoff=args.backoff, sleep=sleep)
        except FetchError as error:
            failures += 1
            print(f"{symbol}: FAILED ({error})", file=sys.stderr)
            continue
        print(f"{symbol}: {len(document['events'])} dividend event(s) written")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
