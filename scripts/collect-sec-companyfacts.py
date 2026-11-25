#!/usr/bin/env python3
"""Collect SEC EDGAR company facts and submissions for offline fundamentals research (GP/A, Q-1).

Only read-only GET requests to these routes are issued (checked before any network I/O):

    data.sec.gov  /api/xbrl/companyfacts/CIK##########.json
    data.sec.gov  /submissions/CIK##########.json
    data.sec.gov  /submissions/CIK##########-submissions-###.json   (older filing pages)
    www.sec.gov   /files/company_tickers.json

Redirects are refused, environment proxies are ignored, only https is accepted.

Contact: the SEC fair-access policy asks for a descriptive User-Agent with a contact. It is read only from
the environment variable SEC_USER_AGENT, sent as the User-Agent header, and never printed, logged or written.
The manifest records only "user_agent_source": "env:SEC_USER_AGENT".

Rate: one global throttle keeps at least --min-interval seconds between requests, retries included
(default 0.2 s, i.e. at most 5 requests/s; the SEC limit is 10 requests/s). Smaller values are refused.

Output (one dataset directory, consumed by ``python -m app.research.fundamentals``):

    <out>/manifest.json                             provider, routes, per-file sha256, per-symbol status
    <out>/raw/company_tickers.json                  ticker -> CIK map (www.sec.gov)
    <out>/raw/submissions/CIK##########.json        filing index with acceptanceDateTime, SIC, name
    <out>/raw/submissions/CIK##########-submissions-###.json  older filing pages (when listed)
    <out>/raw/companyfacts/CIK##########.json       XBRL facts; absent when the issuer has no XBRL (404)

Raw bytes are stored exactly as received. Durability: manifest.json is replaced atomically after every symbol.
An existing manifest is never overwritten silently; pass --resume to continue it. On resume a symbol is skipped
only when its recorded files exist with matching sha256 and its CIK is unchanged; anything else is refetched.

Explicit CIKs (for issuers that are no longer in company_tickers.json, e.g. delisted or merged) are passed as
--cik LABEL=CIK and are never resolved through the ticker file.

Retries: HTTP 429/500/502/503/504 and transport errors are retried up to --max-retries times with exponential
backoff (retry k waits --backoff * 2**k seconds); a Retry-After seconds header replaces the computed wait.
Waits are capped at 120 seconds. HTTP 404 on companyfacts is recorded as NO_COMPANYFACTS, not retried.
HTTP 401/403 stops the run immediately; the manifest is still written.

Exit codes: 0 every symbol collected (NO_COMPANYFACTS counts as collected), 1 at least one symbol ERROR,
2 setup problem (SEC_USER_AGENT unset, manifest exists without --resume, unknown ticker, bad arguments),
3 SEC rejected the request (HTTP 401/403).
"""

from __future__ import annotations

import argparse
import hashlib
import json
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

# LL-1 universe: sector ETF constituents from claudedocs/leadlag-preregistration.md section 2 (35 stocks).
DEFAULT_SYMBOLS = [
    "AAPL", "MSFT", "NVDA", "ORCL", "CSCO",
    "JPM", "BAC", "WFC", "C", "GS",
    "XOM", "CVX", "COP", "SLB", "OXY",
    "JNJ", "PFE", "MRK", "ABT", "AMGN",
    "PG", "KO", "PEP", "WMT", "CL",
    "CAT", "GE", "HON", "BA", "DE",
    "AMZN", "HD", "MCD", "NKE", "LOW",
]

UA_ENV = "SEC_USER_AGENT"
UA_SOURCE = "env:SEC_USER_AGENT"
ALLOWED_METHOD = "GET"
ALLOWED_SCHEME = "https"
DATA_HOST = "data.sec.gov"
WWW_HOST = "www.sec.gov"
TICKER_PATH = "/files/company_tickers.json"
ALLOWED_ROUTES = (
    (WWW_HOST, re.compile(re.escape(TICKER_PATH))),
    (DATA_HOST, re.compile(r"/api/xbrl/companyfacts/CIK(\d{10})\.json")),
    (DATA_HOST, re.compile(r"/submissions/CIK(\d{10})\.json")),
    (DATA_HOST, re.compile(r"/submissions/CIK(\d{10})-submissions-(\d{3})\.json")),
)
MANIFEST_NAME = "manifest.json"
MIN_INTERVAL_FLOOR = 0.2
RETRY_STATUS = frozenset({429, 500, 502, 503, 504})
REJECT_STATUS = frozenset({401, 403})
RETRY_AFTER_CAP = 120.0
PROVIDER = "SEC EDGAR"
STATUS_OK = "OK"
STATUS_NO_FACTS = "NO_COMPANYFACTS"
STATUS_ERROR = "ERROR"


class CollectionError(RuntimeError):
    pass


class SetupError(CollectionError):
    pass


class NotFound(CollectionError):
    pass


class SecRejected(CollectionError):
    def __init__(self, status: int, route: str):
        super().__init__(f"SEC rejected the request: HTTP {status} for {route}")
        self.status = status


class Transient(CollectionError):
    def __init__(self, reason: str, retry_after: float | None):
        super().__init__(reason)
        self.reason = reason
        self.retry_after = retry_after


class _RefuseRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise CollectionError(f"HTTP {code} redirect refused; the collector does not follow redirects")


def default_opener():
    # An explicit empty ProxyHandler keeps the request away from any proxy set in the environment.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), _RefuseRedirects)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def check_request(url: str) -> str:
    """Allowlist guard. Runs before any network I/O. Returns the route (path) for logging; never the User-Agent."""
    try:
        parts = urllib.parse.urlsplit(url)
        host = parts.hostname
    except ValueError:
        raise CollectionError("request URL is malformed") from None
    if parts.scheme != ALLOWED_SCHEME or not host:
        raise CollectionError("request URL must be an https URL with a host")
    if parts.query or parts.fragment:
        raise CollectionError("request URL must not carry a query or fragment")
    for allowed_host, pattern in ALLOWED_ROUTES:
        if host == allowed_host and pattern.fullmatch(parts.path):
            return f"{host}{parts.path}"
    raise CollectionError(f"request route {host}{parts.path} is outside the SEC allowlist")


def read_user_agent(environ) -> str:
    value = (environ.get(UA_ENV) or "").strip()
    if not value:
        raise SetupError(f"{UA_ENV} is not set; export a descriptive User-Agent with a contact "
                         "(for example via doppler) before running this collector")
    if any(ord(char) < 32 or ord(char) > 126 for char in value):
        # The value itself is never echoed.
        raise SetupError(f"{UA_ENV} must be printable ASCII without control characters")
    return value


def parse_retry_after(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        seconds = float(value.strip())
    except ValueError:
        return None
    return seconds if seconds >= 0 else None


class Throttle:
    """Global request spacing. Every attempt, including retries, passes through wait()."""

    def __init__(self, min_interval: float, sleep, clock):
        self.min_interval = min_interval
        self.sleep = sleep
        self.clock = clock
        self._last: float | None = None
        self.requests = 0

    def wait(self) -> None:
        now = self.clock()
        if self._last is not None:
            remaining = self._last + self.min_interval - now
            if remaining > 0:
                self.sleep(remaining)
                now = self.clock()
        self._last = now
        self.requests += 1


class Client:
    def __init__(self, user_agent: str, opener, throttle: Throttle, timeout: float, max_retries: int,
                 backoff: float, sleep):
        self.user_agent = user_agent
        self.opener = opener
        self.throttle = throttle
        self.timeout = timeout
        self.max_retries = max_retries
        self.backoff = backoff
        self.sleep = sleep
        self.retries: Counter = Counter()

    def _fetch_once(self, url: str) -> bytes:
        route = check_request(url)
        request = urllib.request.Request(url, method=ALLOWED_METHOD, headers={
            "User-Agent": self.user_agent, "Accept": "application/json"})
        self.throttle.wait()
        try:
            with self.opener.open(request, timeout=self.timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code in REJECT_STATUS:
                raise SecRejected(error.code, route) from None
            if error.code == 404:
                raise NotFound(f"HTTP 404 for {route}") from None
            if error.code in RETRY_STATUS:
                raise Transient(f"HTTP {error.code} for {route}",
                                parse_retry_after(error.headers.get("Retry-After"))) from None
            raise CollectionError(f"HTTP {error.code} for {route}") from None
        except (urllib.error.URLError, OSError) as error:
            name = type(error).__name__
            raise Transient(f"transport error {name} for {route}", None) from None

    def get(self, url: str) -> bytes:
        attempt = 0
        while True:
            try:
                return self._fetch_once(url)
            except Transient as error:
                if attempt >= self.max_retries:
                    raise CollectionError(f"{error.reason}; persisted after {attempt} retries") from None
                delay = error.retry_after if error.retry_after is not None else self.backoff * 2 ** attempt
                self.retries[error.reason.split(" for ")[0]] += 1
                self.sleep(min(delay, RETRY_AFTER_CAP))
                attempt += 1


def write_bytes_atomic(path: Path, data: bytes) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)
    return hashlib.sha256(data).hexdigest()


def write_manifest(out: Path, manifest: dict) -> None:
    write_bytes_atomic(out / MANIFEST_NAME, json.dumps(manifest, sort_keys=True, indent=2).encode("utf-8"))


def read_manifest(out: Path) -> dict:
    manifest = json.loads((out / MANIFEST_NAME).read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise CollectionError("manifest is not a JSON object")
    manifest.setdefault("symbols", {})
    return manifest


def parse_json_object(data: bytes, route: str) -> dict:
    try:
        value = json.loads(data.decode("utf-8"))
    except ValueError:
        raise CollectionError(f"{route}: response is not JSON") from None
    if not isinstance(value, dict):
        raise CollectionError(f"{route}: response is not a JSON object")
    return value


def submissions_url(cik10: str) -> str:
    return f"https://{DATA_HOST}/submissions/CIK{cik10}.json"


def companyfacts_url(cik10: str) -> str:
    return f"https://{DATA_HOST}/api/xbrl/companyfacts/CIK{cik10}.json"


def submissions_page_url(cik10: str, name: str) -> str:
    return f"https://{DATA_HOST}/submissions/{name}"


def load_ticker_map(client: Client, out: Path, manifest: dict) -> dict[str, dict]:
    data = client.get(f"https://{WWW_HOST}{TICKER_PATH}")
    sha = write_bytes_atomic(out / "raw" / "company_tickers.json", data)
    manifest["ticker_file"] = {"path": "raw/company_tickers.json", "sha256": sha, "bytes": len(data),
                               "source": f"https://{WWW_HOST}{TICKER_PATH}"}
    table = parse_json_object(data, TICKER_PATH)
    by_ticker: dict[str, dict] = {}
    for row in table.values():
        if isinstance(row, dict) and isinstance(row.get("ticker"), str) and isinstance(row.get("cik_str"), int):
            by_ticker[row["ticker"].upper()] = {"cik": f"{row['cik_str']:010d}", "title": row.get("title")}
    return by_ticker


def verify_recorded(out: Path, entry: dict) -> bool:
    files = entry.get("files") or {}
    if not files or entry.get("status") not in (STATUS_OK, STATUS_NO_FACTS):
        return False
    for relative, info in files.items():
        path = out / relative
        if not path.is_file():
            return False
        if hashlib.sha256(path.read_bytes()).hexdigest() != info.get("sha256"):
            return False
    return True


def collect_symbol(client: Client, out: Path, label: str, cik10: str, cik_source: str,
                   manifest: dict) -> dict:
    files: dict[str, dict] = {}
    entry: dict = {"cik": cik10, "cik_source": cik_source, "status": STATUS_ERROR, "files": files,
                   "submission_pages": [], "error": None}

    def record(relative: str, url: str, data: bytes) -> None:
        sha = write_bytes_atomic(out / relative, data)
        files[relative] = {"sha256": sha, "bytes": len(data), "source": url}

    try:
        sub_url = submissions_url(cik10)
        sub_bytes = client.get(sub_url)
        record(f"raw/submissions/CIK{cik10}.json", sub_url, sub_bytes)
        submissions = parse_json_object(sub_bytes, sub_url)
        if str(submissions.get("cik", "")).zfill(10) != cik10:
            raise CollectionError(f"submissions CIK does not match {cik10}")
        entry["name"] = submissions.get("name")
        entry["sic"] = submissions.get("sic")
        entry["sic_description"] = submissions.get("sicDescription")
        entry["tickers"] = submissions.get("tickers") or []
        pages = submissions.get("filings", {}).get("files") or []
        for page in pages:
            name = page.get("name") if isinstance(page, dict) else None
            if not isinstance(name, str):
                continue
            match = re.fullmatch(rf"CIK{cik10}-submissions-\d{{3}}\.json", name)
            if not match:
                raise CollectionError(f"submissions page name {name!r} is outside the allowlist pattern")
            page_url = submissions_page_url(cik10, name)
            record(f"raw/submissions/{name}", page_url, client.get(page_url))
            entry["submission_pages"].append(name)
        facts_url = companyfacts_url(cik10)
        try:
            facts_bytes = client.get(facts_url)
        except NotFound:
            entry["status"] = STATUS_NO_FACTS
            return entry
        facts = parse_json_object(facts_bytes, facts_url)
        if str(facts.get("cik", "")).zfill(10) != cik10:
            raise CollectionError(f"companyfacts CIK does not match {cik10}")
        record(f"raw/companyfacts/CIK{cik10}.json", facts_url, facts_bytes)
        entry["status"] = STATUS_OK
        entry["entity_name"] = facts.get("entityName")
    except SecRejected:
        raise
    except CollectionError as error:
        entry["error"] = str(error)
    return entry


def resolve_symbols(symbols: list[str], explicit: dict[str, str], tickers: dict[str, dict] | None) -> list[tuple]:
    resolved: list[tuple] = []
    for label, cik in explicit.items():
        resolved.append((label, f"{int(cik):010d}", "explicit_cik"))
    for symbol in symbols:
        if tickers is None or symbol.upper() not in tickers:
            raise SetupError(f"ticker {symbol} is not listed in company_tickers.json; "
                             "pass it as --cik LABEL=CIK if it is delisted")
        resolved.append((symbol.upper(), tickers[symbol.upper()]["cik"], "company_tickers"))
    return resolved


def parse_explicit(values: list[str]) -> dict[str, str]:
    explicit: dict[str, str] = {}
    for value in values:
        label, separator, cik = value.partition("=")
        if not separator or not label.strip() or not cik.strip().isdigit() or len(cik.strip()) > 10:
            raise SetupError("--cik expects LABEL=CIK with a numeric CIK of at most 10 digits")
        if label.strip() in explicit:
            raise SetupError(f"--cik label {label.strip()} is given twice")
        explicit[label.strip()] = cik.strip()
    return explicit


def collect(args, environ, opener=None, sleep=time.sleep, clock=time.monotonic) -> int:
    user_agent = read_user_agent(environ)
    if args.min_interval < MIN_INTERVAL_FLOOR:
        raise SetupError(f"--min-interval must be at least {MIN_INTERVAL_FLOOR} (at most 5 requests/s)")
    out = Path(args.out)
    manifest_path = out / MANIFEST_NAME
    if manifest_path.exists() and not args.resume:
        raise SetupError(f"{manifest_path} exists; pass --resume to continue it")
    manifest = read_manifest(out) if (args.resume and manifest_path.exists()) else {"symbols": {}}
    symbols = [item.strip() for item in args.symbols.split(",") if item.strip()] if args.symbols else []
    explicit = parse_explicit(args.cik or [])
    if not symbols and not explicit:
        raise SetupError("no symbols requested")

    throttle = Throttle(args.min_interval, sleep, clock)
    client = Client(user_agent, opener or default_opener(), throttle, args.timeout, args.max_retries,
                    args.backoff, sleep)
    manifest.update({
        "provider": PROVIDER,
        "collector": "scripts/collect-sec-companyfacts.py",
        "user_agent_source": UA_SOURCE,
        "min_request_interval_seconds": args.min_interval,
        "max_requests_per_second": round(1 / args.min_interval, 3),
        "routes": [f"{host}{pattern.pattern}" for host, pattern in ALLOWED_ROUTES],
        "requested_symbols": symbols,
        "explicit_ciks": explicit,
        "collected_at": utc_now(),
    })
    out.mkdir(parents=True, exist_ok=True)
    exit_code = 0
    try:
        tickers = load_ticker_map(client, out, manifest) if symbols else None
        plan = resolve_symbols(symbols, explicit, tickers)
        for label, cik10, source in plan:
            previous = manifest["symbols"].get(label)
            if args.resume and previous and previous.get("cik") == cik10 and verify_recorded(out, previous):
                print(f"[sec] {label} CIK{cik10} resumed status={previous['status']}")
                continue
            entry = collect_symbol(client, out, label, cik10, source, manifest)
            manifest["symbols"][label] = entry
            write_manifest(out, manifest)
            if entry["status"] == STATUS_ERROR:
                exit_code = 1
            print(f"[sec] {label} CIK{cik10} status={entry['status']} files={len(entry['files'])}"
                  + (f" error={entry['error']}" if entry.get("error") else ""))
    except SecRejected as error:
        manifest["stopped"] = {"reason": str(error), "status": error.status}
        exit_code = 3
        print(f"[sec] stopped: {error}", file=sys.stderr)
    finally:
        manifest["requests"] = throttle.requests
        manifest["retries"] = dict(client.retries)
        manifest["finished_at"] = utc_now()
        write_manifest(out, manifest)
    return exit_code


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--symbols", default=",".join(DEFAULT_SYMBOLS),
                        help="comma-separated tickers resolved through company_tickers.json "
                             "(default: the 35 LL-1 stocks)")
    parser.add_argument("--cik", action="append", metavar="LABEL=CIK",
                        help="explicit CIK, for example a delisted issuer; repeatable")
    parser.add_argument("--out", default="research-data/sec", help="dataset directory")
    parser.add_argument("--resume", action="store_true", help="continue an existing manifest")
    parser.add_argument("--min-interval", type=float, default=MIN_INTERVAL_FLOOR,
                        help="minimum seconds between requests (floor 0.2 = 5 requests/s)")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--max-retries", type=int, default=4)
    parser.add_argument("--backoff", type=float, default=2.0)
    return parser


def main(argv: list[str] | None = None, environ=None, opener=None, sleep=time.sleep,
         clock=time.monotonic) -> int:
    args = build_parser().parse_args(argv)
    try:
        return collect(args, os.environ if environ is None else environ, opener=opener, sleep=sleep, clock=clock)
    except SetupError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except CollectionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
