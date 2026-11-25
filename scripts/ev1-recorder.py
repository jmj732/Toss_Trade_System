#!/usr/bin/env python3
"""Record the EV-1 forward validation window without order or broker access.

The recorder polls SEC submissions for Item 2.02 8-K filings after the frozen
cutoff, keeps the SEC acceptance timestamp separate from the local observation
timestamp, and evaluates only the pre-registered EPS_STRONG25 rule.  It uses
frozen-universe metadata and Yahoo daily CSVs produced by the existing
read-only collector.  No XBRL/companyfacts data, broker endpoint, order route,
or approval endpoint is used.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import html
import json
import math
import os
import random
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from datetime import date, datetime, time as dt_time, timedelta, timezone
from html.parser import HTMLParser
from pathlib import Path
from typing import Callable, Iterable
from zoneinfo import ZoneInfo

CUTOFF_DATE = date(2026, 11, 25)
NEW_YORK = ZoneInfo("America/New_York")
READY_CUTOFF = dt_time(9, 15)
SEC_DATA_HOST = "data.sec.gov"
SEC_WWW_HOST = "www.sec.gov"
SEC_USER_AGENT_ENV = "SEC_USER_AGENT"
SEC_MIN_INTERVAL = 0.2
SEC_RETRY_STATUS = frozenset({429, *range(500, 600)})
SEC_ARCHIVE_PREFIX = "https://www.sec.gov/Archives/edgar/data/"
SEC_TICKER_MAP_PATH = "/files/company_tickers.json"
ITEM_202_RE = re.compile(r"(?:^|[,;\s])2\.02(?:$|[,;\s])")
ITEM_RE = re.compile(r"\d+\.\d{2}")
MATERIAL_ITEMS = frozenset({"1.01", "2.01", "2.03", "2.04", "2.05", "2.06", "5.02"})
HORIZONS = (3, 5, 10, 20)
COST_BPS = (30, 40, 70)
PRIMARY_HORIZON = 5
SECONDARY_HORIZONS = (3, 10, 20)
MANIFEST_NAME = "manifest.json"
METRICS_CI_METHOD = "entry-date block bootstrap"
METRICS_CI_SEED = 1729
METRICS_CI_REPETITIONS = 2000

# SEC routes are checked before any request is handed to urllib.
SEC_SUBMISSIONS_RE = re.compile(r"/submissions/CIK\d{10}\.json")
SEC_ARCHIVE_RE = re.compile(r"/Archives/edgar/data/\d+/\d{18}/[A-Za-z0-9][A-Za-z0-9._-]*")

# Visible text parsing intentionally retains table/block boundaries.  A table
# row is stronger evidence than a broad bag-of-numbers extracted from HTML.
_BLOCK_TAGS = frozenset({
    "address", "article", "br", "div", "dl", "dt", "dd", "h1", "h2", "h3",
    "h4", "h5", "h6", "li", "ol", "p", "section", "table", "tbody", "td",
    "tfoot", "th", "thead", "tr", "ul",
})
_NUMBER_RE = re.compile(r"(?<![\w.])\(?\s*[-+]?\$?\s*\d[\d,]*(?:\.\d+)?\s*\)?(?![\w.])")
_EPS_LABEL_RE = re.compile(
    r"(?i)(?:diluted\s+)?(?:earnings\s+per\s+share|eps)\b"
)
_GAAP_RE = re.compile(r"(?i)\bGAAP\b")
_NON_GAAP_RE = re.compile(r"(?i)\bnon[\s-]*GAAP\b|\badjusted\b|\bunderlying\b")
_COMPARE_RE = re.compile(
    r"(?i)\b(?:compared\s+(?:with|to)|versus|vs\.?|prior\s+(?:year|period)|last\s+year|previous\s+year)\b"
)
_YEAR_CONTEXT_RE = re.compile(
    r"(?i)\b(?:year|period|quarter|months?|ended|current|prior|previous|202[0-9]|203[0-9])\b"
)


class RecorderError(RuntimeError):
    """A safe, user-facing recorder failure."""


class SetupError(RecorderError):
    pass


class FetchError(RecorderError):
    pass


class FetchResult:
    __slots__ = ("body", "observed_at", "url")

    def __init__(self, body: bytes, observed_at: str, url: str):
        self.body = body
        self.observed_at = observed_at
        self.url = url


class _RefuseRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise FetchError(f"HTTP {code} redirect refused")


def default_opener():
    # An empty ProxyHandler prevents environment proxy variables from changing
    # the destination or TLS path of a read-only request.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), _RefuseRedirects)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def utc_now(clock: Callable[[], datetime] | None = None) -> str:
    value = clock() if clock else datetime.now(timezone.utc)
    if value.tzinfo is None:
        raise ValueError("clock must return an aware datetime")
    return value.astimezone(timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")


def parse_timestamp(value: str) -> datetime:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("timestamp is empty")
    parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp must include a timezone")
    return parsed.astimezone(timezone.utc)


def check_sec_url(url: str) -> str:
    """Return the allowlisted route or raise before any network I/O."""
    try:
        parts = urllib.parse.urlsplit(url)
        port = parts.port
    except ValueError:
        raise FetchError("malformed SEC URL") from None
    if parts.scheme != "https" or parts.username or parts.password or port:
        raise FetchError("SEC URL must use https without credentials or a port")
    if parts.query or parts.fragment or not parts.hostname:
        raise FetchError("SEC URL must not contain query or fragment")
    host = parts.hostname.lower()
    if host == SEC_DATA_HOST and SEC_SUBMISSIONS_RE.fullmatch(parts.path):
        return f"{host}{parts.path}"
    if host == SEC_WWW_HOST and parts.path == SEC_TICKER_MAP_PATH:
        return f"{host}{parts.path}"
    if host == SEC_WWW_HOST and SEC_ARCHIVE_RE.fullmatch(parts.path):
        return f"{host}{parts.path}"
    raise FetchError(f"SEC route {host}{parts.path} is outside the allowlist")


def submissions_url(cik: str) -> str:
    return f"https://{SEC_DATA_HOST}/submissions/CIK{int(cik):010d}.json"


def company_tickers_url() -> str:
    return f"https://{SEC_WWW_HOST}{SEC_TICKER_MAP_PATH}"


def archive_url(cik: str, accession: str, filename: str) -> str:
    if not re.fullmatch(r"\d{10}", str(cik)):
        cik = f"{int(cik):010d}"
    accession_digits = str(accession).replace("-", "")
    if not re.fullmatch(r"\d{18}", accession_digits):
        raise FetchError("invalid SEC accession")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", filename):
        raise FetchError("invalid SEC archive filename")
    return f"{SEC_ARCHIVE_PREFIX}{int(cik)}/{accession_digits}/{filename}"


class Throttle:
    """Global spacing for every SEC attempt, including retries."""

    def __init__(self, min_interval: float = SEC_MIN_INTERVAL,
                 sleep: Callable[[float], None] = time.sleep,
                 clock: Callable[[], float] = time.monotonic):
        if min_interval < SEC_MIN_INTERVAL:
            raise SetupError("SEC min interval must be at least 0.2 seconds (maximum 5 requests/s)")
        self.min_interval = min_interval
        self.sleep = sleep
        self.clock = clock
        self.last: float | None = None
        self.requests = 0

    def wait(self) -> None:
        now = self.clock()
        if self.last is not None:
            remaining = self.last + self.min_interval - now
            if remaining > 0:
                self.sleep(remaining)
                now = self.clock()
        self.last = now
        self.requests += 1


class SecClient:
    def __init__(self, user_agent: str, opener=None, throttle: Throttle | None = None,
                 timeout: float = 30.0, max_retries: int = 4,
                 backoff: float = 2.0, sleep: Callable[[float], None] = time.sleep,
                 clock: Callable[[], datetime] | None = None):
        if not user_agent or any(ord(c) < 32 or ord(c) > 126 for c in user_agent):
            raise SetupError("SEC_USER_AGENT must be printable ASCII and is read only from the environment")
        if max_retries < 0 or backoff < 0 or timeout <= 0:
            raise SetupError("invalid SEC retry, backoff, or timeout setting")
        self.user_agent = user_agent
        self.opener = opener or default_opener()
        self.throttle = throttle or Throttle()
        self.timeout = timeout
        self.max_retries = max_retries
        self.backoff = backoff
        self.sleep = sleep
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self.retries = 0

    def get(self, url: str) -> FetchResult:
        check_sec_url(url)
        for attempt in range(self.max_retries + 1):
            self.throttle.wait()
            request = urllib.request.Request(url, method="GET", headers={
                "User-Agent": self.user_agent,
                "Accept": "application/json" if "/submissions/" in url or SEC_TICKER_MAP_PATH in url
                else "text/html,*/*",
            })
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    body = response.read()
                # Capture the observation immediately after the response body is
                # available; this is never substituted for SEC acceptance time.
                observed = utc_now(self.clock)
                return FetchResult(body=body, observed_at=observed, url=url)
            except urllib.error.HTTPError as error:
                retryable = error.code in SEC_RETRY_STATUS or 500 <= error.code <= 599
                retry_after = error.headers.get("Retry-After") if error.headers else None
                error.close()
                if retryable and attempt < self.max_retries:
                    self.retries += 1
                    delay = _retry_after_seconds(retry_after)
                    self.sleep(delay if delay is not None else self.backoff * (2 ** attempt))
                    continue
                if retryable:
                    raise FetchError(f"HTTP {error.code} persisted after {attempt} retries") from None
                raise FetchError(f"HTTP {error.code}") from None
            except (urllib.error.URLError, OSError) as error:
                if attempt < self.max_retries:
                    self.retries += 1
                    self.sleep(self.backoff * (2 ** attempt))
                    continue
                raise FetchError(f"network error {type(error).__name__} persisted after {attempt} retries") from None
        raise AssertionError("unreachable")


def _retry_after_seconds(value: str | None) -> float | None:
    if value is None:
        return None
    try:
        seconds = float(value.strip())
    except (AttributeError, ValueError):
        return None
    return seconds if seconds >= 0 else None


def write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("wb") as handle:
        handle.write(data)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def write_manifest_atomic(path: Path, manifest: dict) -> None:
    # The manifest contains no user-agent value.  Only known safe values are
    # supplied by this module; serialisation is kept deterministic for audits.
    write_atomic(path, (json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8"))


def load_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise SetupError(f"cannot read JSON input {path}: {type(error).__name__}") from None
    if not isinstance(value, dict):
        raise SetupError(f"JSON input {path} must be an object")
    return value


def _norm_ticker(value: str) -> str:
    return value.strip().upper().replace(".", "-")


def _universe_rows(universe_csv: Path, by_ticker: dict[str, dict]) -> list[dict]:
    rows: list[dict] = []
    try:
        handle = universe_csv.open(newline="", encoding="utf-8")
    except OSError as error:
        raise SetupError(f"cannot read frozen universe: {type(error).__name__}") from None
    with handle:
        for number, row in enumerate(csv.DictReader(handle), start=2):
            ticker = _norm_ticker(row.get("ticker", ""))
            if not ticker:
                continue
            entry = by_ticker.get(ticker) or {}
            cik = str(entry.get("cik", ""))
            sic = str(entry.get("sic", ""))
            valid_cik = bool(re.fullmatch(r"\d{1,10}", cik))
            valid_sic = bool(re.fullmatch(r"\d{4}", sic))
            rows.append({
                "ticker": ticker,
                "display_ticker": row.get("ticker", ticker).strip(),
                "name": row.get("name", "").strip(),
                "weight": row.get("weight"),
                "cik": f"{int(cik):010d}" if valid_cik else None,
                "sic": sic if valid_sic else None,
                "sic2": sic[:2] if valid_sic else None,
                "mapping_status": "MAPPED" if valid_cik and valid_sic else "MAPPING_INVALID",
                "source_row": number,
            })
    if not rows:
        raise SetupError("frozen universe CSV contains no ticker rows")
    return rows


def load_universe(universe_csv: Path, sec_manifest_path: Path | None = None,
                  metadata_path: Path | None = None) -> list[dict]:
    """Map the frozen CSV using persisted SEC metadata or the legacy full manifest."""
    if metadata_path and metadata_path.is_file():
        metadata = load_json(metadata_path)
        mapping = metadata.get("symbols")
        if not isinstance(mapping, dict):
            raise SetupError("EV-1 universe metadata has no symbols map")
        return _universe_rows(universe_csv, mapping)
    if sec_manifest_path and sec_manifest_path.is_file():
        sec_manifest = load_json(sec_manifest_path)
        symbols = sec_manifest.get("symbols")
        if not isinstance(symbols, dict):
            raise SetupError("SEC full-universe manifest has no symbols map")
        return _universe_rows(universe_csv, symbols)
    raise SetupError("no persisted SEC universe metadata; bootstrap is required")


def _submission_sic(payload: dict) -> str | None:
    value = payload.get("sic") if isinstance(payload, dict) else None
    text = str(value or "")
    return text if re.fullmatch(r"\d{4}", text) else None


def bootstrap_universe(client: SecClient, universe_csv: Path, metadata_path: Path) -> list[dict]:
    """Bootstrap CIK/SIC from public SEC mapping and submissions only.

    This metadata is persisted separately from the forward manifest so a restart
    does not repeat the bootstrap. It is never used as an event or XBRL signal.
    """
    ticker_response = client.get(company_tickers_url())
    try:
        ticker_payload = json.loads(ticker_response.body.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise FetchError("SEC ticker map is not JSON") from None
    if not isinstance(ticker_payload, dict):
        raise FetchError("SEC ticker map is not an object")
    by_ticker: dict[str, dict] = {}
    for entry in ticker_payload.values():
        if not isinstance(entry, dict):
            continue
        ticker = _norm_ticker(str(entry.get("ticker", "")))
        cik = entry.get("cik_str")
        if ticker and isinstance(cik, int) and 0 < cik <= 9999999999:
            by_ticker[ticker] = {"cik": f"{cik:010d}", "name": entry.get("title")}
    frozen = _universe_rows(universe_csv, by_ticker)
    for issuer in frozen:
        if not issuer.get("cik"):
            continue
        response = client.get(submissions_url(issuer["cik"]))
        payload = _read_raw_json(response.body)
        sic = _submission_sic(payload)
        issuer["sic"] = sic
        issuer["sic2"] = sic[:2] if sic else None
        issuer["mapping_status"] = "MAPPED" if sic else "MAPPING_INVALID"
        issuer["metadata_observed_at"] = response.observed_at
    metadata = {
        "schema_version": 1,
        "source": "SEC company_tickers.json and submissions",
        "ticker_map_url": company_tickers_url(),
        "ticker_map_sha256": sha256(ticker_response.body),
        "frozen_universe_csv": str(universe_csv),
        "frozen_universe_sha256": sha256(universe_csv.read_bytes()),
        "symbols": {row["ticker"]: row for row in frozen},
    }
    write_manifest_atomic(metadata_path, metadata)
    return frozen


class _VisibleText(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        if tag.lower() in _BLOCK_TAGS:
            self.parts.append("\n")

    def handle_data(self, data):
        if data:
            self.parts.append(data)


def visible_text(raw: bytes) -> str:
    parser = _VisibleText()
    parser.feed(raw.decode("utf-8", errors="replace"))
    text = html.unescape("".join(parser.parts).replace("\xa0", " "))
    lines = [re.sub(r"\s+", " ", line).strip() for line in text.splitlines()]
    return "\n".join(line for line in lines if line)


class _Links(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links: list[tuple[str, str]] = []
        self.href: str | None = None
        self.text: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "a":
            self.href = dict(attrs).get("href")
            self.text = []

    def handle_data(self, data):
        if self.href is not None:
            self.text.append(data)

    def handle_endtag(self, tag):
        if tag.lower() == "a" and self.href is not None:
            self.links.append((self.href, " ".join("".join(self.text).split())))
            self.href = None
            self.text = []


def _candidate_exhibit_links(raw: bytes, primary_url: str) -> list[str]:
    parser = _Links()
    parser.feed(raw.decode("utf-8", errors="replace"))
    primary_path = urllib.parse.urlsplit(primary_url).path
    candidates: list[str] = []
    seen: set[str] = set()
    for href, label in parser.links:
        if not href or href.startswith("#"):
            continue
        url = urllib.parse.urljoin(primary_url, html.unescape(href))
        parts = urllib.parse.urlsplit(url)
        filename = Path(parts.path).name
        marker = f"{filename} {label}"
        if not re.search(r"(?i)(?:99[._-]?\d|exhibit|earnings|press|release|results)", marker):
            continue
        if parts.path == primary_path or url in seen:
            continue
        try:
            check_sec_url(url)
        except FetchError:
            continue
        seen.add(url)
        candidates.append(url)
    return candidates


def _number_value(token: str) -> float | None:
    cleaned = token.replace("$", "").replace(",", "").replace(" ", "")
    negative = cleaned.startswith("(") and cleaned.endswith(")")
    cleaned = cleaned.strip("()")
    try:
        value = float(cleaned)
    except ValueError:
        return None
    if negative:
        value = -value
    return value if math.isfinite(value) else None


def _numbers_after(text: str, start: int) -> list[tuple[int, float, str]]:
    found: list[tuple[int, float, str]] = []
    for match in _NUMBER_RE.finditer(text, start):
        value = _number_value(match.group(0))
        if value is not None:
            found.append((match.start(), value, match.group(0)))
    return found


def _eps_candidates(text: str) -> list[dict]:
    """Find only pairs with explicit GAAP/diluted release context."""
    lines = text.splitlines() or [text]
    candidates: list[dict] = []
    offset = 0
    for index, line in enumerate(lines):
        labels = list(_EPS_LABEL_RE.finditer(line))
        if not labels or len(labels) > 1:
            # Multiple EPS rows in one line cannot identify one current/prior
            # pair without table-cell boundaries; conservatively reject it.
            offset += len(line) + 1
            continue
        label = labels[0]
        context_before = " ".join(lines[max(0, index - 3):index])
        context_after = " ".join(lines[index:min(len(lines), index + 4)])
        window = f"{context_before} {line} {context_after}"
        label_start = label.start()
        prefix = line[max(0, label_start - 90):label_start]
        label_context = line[max(0, label_start - 90):]
        # A non-GAAP row must never inherit GAAP from a nearby header or row.
        if _NON_GAAP_RE.search(prefix):
            offset += len(line) + 1
            continue
        has_gaap = bool(_GAAP_RE.search(label_context) or _GAAP_RE.search(context_before))
        if not has_gaap:
            offset += len(line) + 1
            continue
        numbers = _numbers_after(line, label.end())
        comparison = bool(_COMPARE_RE.search(line) or _COMPARE_RE.search(context_after))
        table_context = bool(_YEAR_CONTEXT_RE.search(context_before) or _YEAR_CONTEXT_RE.search(context_after))
        # A row can be split into cells by HTML extraction.  Look only a few
        # lines forward, and require period/comparison context before accepting.
        if len(numbers) < 2:
            tail = " ".join(lines[index:min(len(lines), index + 4)])
            numbers = _numbers_after(tail, len(line[:label.end()]))
        if len(numbers) >= 2 and (comparison or table_context):
            if len(numbers) > 2 and not comparison:
                # Several unlabelled figures in a table context are ambiguous.
                offset += len(line) + 1
                continue
            if any(1900 <= abs(value) <= 2100 for _, value, _ in numbers[:2]):
                # A filing year/date before the EPS values must not become a
                # fabricated current/prior pair.
                offset += len(line) + 1
                continue
            current = numbers[0][1]
            prior = numbers[1][1]
            candidates.append({
                "current_eps": current,
                "prior_eps": prior,
                "evidence": window[:700].strip(),
                "line": line,
                "source": "gaap_diluted_release_context",
                "position": offset + label.start(),
            })
        else:
            # Handle a common prose form where the second value follows an
            # explicit comparison phrase on the same line.
            prose = line[label.start():]
            match = re.search(
                r"(?i)(?:was|were|is|are|of|:)?\s*\$?\s*(\(?[-+]?\d[\d,]*(?:\.\d+)?\)?)"
                r".{0,100}?\b(?:compared\s+(?:with|to)|versus|vs\.?|prior\s+(?:year|period)|last\s+year)\b"
                r".{0,60}?\$?\s*(\(?[-+]?\d[\d,]*(?:\.\d+)?\)?)",
                prose,
            )
            if match:
                current = _number_value(match.group(1))
                prior = _number_value(match.group(2))
                if current is not None and prior is not None:
                    candidates.append({
                        "current_eps": current,
                        "prior_eps": prior,
                        "evidence": window[:700].strip(),
                        "line": line,
                        "source": "gaap_diluted_release_prose",
                        "position": offset + label.start(),
                    })
        offset += len(line) + 1
    # Deduplicate a pair repeated in a primary document and its exhibit while
    # retaining the first evidence snippet for auditability.
    unique: dict[tuple[float, float], dict] = {}
    for item in candidates:
        key = (round(item["current_eps"], 12), round(item["prior_eps"], 12))
        unique.setdefault(key, item)
    return sorted(unique.values(), key=lambda item: item["position"])


def parse_eps_pair(text: str) -> dict:
    """Conservatively classify a release-only GAAP diluted EPS pair.

    No XBRL/companyfacts fallback exists here by design.  More than one
    distinct pair, a non-GAAP-only row, or an unclear table is unusable.
    """
    candidates = _eps_candidates(text)
    if len(candidates) != 1:
        return {
            "status": "EPS_UNUSABLE",
            "reason": "no unambiguous GAAP diluted current/prior pair" if not candidates else "multiple EPS pairs",
            "candidate_count": len(candidates),
        }
    candidate = candidates[0]
    current = candidate["current_eps"]
    prior = candidate["prior_eps"]
    result = {
        "current_eps": current,
        "prior_eps": prior,
        "change_ratio": (current - prior) / abs(prior) if prior else None,
        "evidence": candidate["evidence"],
        "candidate_count": 1,
    }
    if prior <= 0:
        result.update(status="EPS_PAIR_VISIBLE", reason="prior EPS is not positive")
    elif result["change_ratio"] is not None and result["change_ratio"] >= 0.25:
        result.update(status="EPS_STRONG25", reason="GAAP diluted EPS improved at least 25 percent")
    else:
        result.update(status="EPS_PAIR_VISIBLE", reason="GAAP diluted EPS pair is below 25 percent")
    return result


# Alias kept explicit for callers/tests that use the requirement's wording.
parse_release_eps = parse_eps_pair


def split_items(value: str | Iterable[str] | None) -> set[str]:
    if isinstance(value, str):
        return {match.group(0) for match in ITEM_RE.finditer(value)}
    if value is None:
        return set()
    return {str(item).strip() for item in value if ITEM_RE.fullmatch(str(item).strip())}


def classify_package(items: str | Iterable[str] | None, release_text: str = "") -> str:
    item_set = split_items(items)
    if not ("2.02" in item_set):
        return "NOT_EARNINGS"
    if MATERIAL_ITEMS.intersection(item_set):
        return "MATERIAL_CONCURRENT"
    # 3.xx and 4.xx are material corporate-event families; 5.02 is explicit.
    if any(item.startswith("3.") or item.startswith("4.") for item in item_set):
        return "MATERIAL_CONCURRENT"
    material_phrase = re.search(
        r"(?i)\b(?:merger|acquisition|definitive agreement|credit facility|material contract|restructur)\b",
        release_text,
    )
    if material_phrase and any(item.startswith(("1.", "2.", "3.", "4.", "5.")) and item != "2.02"
                               for item in item_set):
        return "MATERIAL_CONCURRENT"
    if "7.01" in item_set or "8.01" in item_set:
        return "EARNINGS_PACKAGE"
    return "CORE_EARNINGS"


def extract_submission_events(payload: dict, issuer: dict, observed_at: str) -> list[dict]:
    filings = payload.get("filings") if isinstance(payload, dict) else None
    recent = filings.get("recent") if isinstance(filings, dict) else None
    if not isinstance(recent, dict):
        recent = payload if isinstance(payload, dict) else {}
    forms = recent.get("form") or []
    items = recent.get("items") or []
    events: list[dict] = []
    for index, form in enumerate(forms):
        item_value = items[index] if index < len(items) else ""
        if str(form).strip().upper() != "8-K" or not ITEM_202_RE.search(str(item_value)):
            continue
        def at(name: str):
            values = recent.get(name) or []
            return values[index] if index < len(values) else None
        filing_date = at("filingDate")
        try:
            filing_day = date.fromisoformat(str(filing_date))
        except (TypeError, ValueError):
            continue
        if filing_day <= CUTOFF_DATE:
            continue
        accession = at("accessionNumber")
        if not accession:
            continue
        events.append({
            "accession": str(accession),
            "ticker": issuer["ticker"],
            "cik": issuer.get("cik"),
            "sic": issuer.get("sic"),
            "sic2": issuer.get("sic2"),
            "filing_date": filing_day.isoformat(),
            "report_date": at("reportDate"),
            "acceptanceDateTime": at("acceptanceDateTime"),
            "items": str(item_value),
            "form": "8-K",
            "primary_document": at("primaryDocument"),
            "observed_at": observed_at,
            "submission_observed_at": observed_at,
        })
    return events


def _parse_ready(acceptance: str | None, observed_at: str | None) -> tuple[datetime | None, str | None]:
    try:
        accepted = parse_timestamp(acceptance or "")
        observed = parse_timestamp(observed_at or "")
    except ValueError:
        return None, "missing or invalid acceptanceDateTime/observed_at"
    ready = max(accepted, observed)
    return ready, None


def _finite_positive(value) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number > 0 else None


def _parse_row_number(value) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def load_price_rows(path: Path) -> list[dict]:
    """Read Yahoo collector CSV without repairing, filling, or synthesising bars."""
    if not path.is_file():
        return []
    rows: list[dict] = []
    with path.open(newline="", encoding="utf-8") as handle:
        for raw in csv.DictReader(handle):
            try:
                day = date.fromisoformat(str(raw.get("date", "")))
            except ValueError:
                continue
            parsed = {"date": day, "raw": {key: raw.get(key) for key in raw}}
            for key in ("open", "close", "adjclose", "adjusted_open", "volume"):
                parsed[key] = _parse_row_number(raw.get(key))
            parsed["valid_open"] = bool(parsed["open"] and parsed["open"] > 0)
            if parsed["adjusted_open"] is not None and parsed["adjusted_open"] > 0:
                parsed["entry_value"] = parsed["adjusted_open"]
                parsed["entry_value_field"] = "adjusted_open"
            elif parsed["valid_open"]:
                parsed["entry_value"] = parsed["open"]
                parsed["entry_value_field"] = "open"
            else:
                parsed["entry_value"] = None
                parsed["entry_value_field"] = None
            parsed["valid_liquidity"] = bool(
                parsed["volume"] is not None and parsed["volume"] >= 0
                and (parsed["adjclose"] or parsed["close"]) is not None
                and (parsed["adjclose"] or parsed["close"]) > 0
            )
            rows.append(parsed)
    rows.sort(key=lambda row: row["date"])
    return rows


def choose_entry_bar(rows: list[dict], ready: datetime | str) -> dict:
    """Choose same-day open through 09:15 ET, otherwise the next trading open."""
    if isinstance(ready, str):
        ready = parse_timestamp(ready)
    eastern = ready.astimezone(NEW_YORK)
    same_day_allowed = eastern.time() <= READY_CUTOFF
    for row in rows:
        if row["date"] < eastern.date():
            continue
        if row["date"] == eastern.date():
            if same_day_allowed and row.get("entry_value") is not None:
                return {
                    "status": "AVAILABLE", "entry_date": row["date"].isoformat(),
                    "entry_open": row["entry_value"], "entry_open_field": row["entry_value_field"],
                    "row_index": rows.index(row), "same_day": True,
                    "ready_at_utc": ready.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
                }
            continue
        if row.get("entry_value") is not None:
            return {
                "status": "AVAILABLE", "entry_date": row["date"].isoformat(),
                "entry_open": row["entry_value"], "entry_open_field": row["entry_value_field"],
                "row_index": rows.index(row), "same_day": False,
                "ready_at_utc": ready.astimezone(timezone.utc).isoformat().replace("+00:00", "Z"),
            }
    return {"status": "MISSING", "reason": "no valid trading open after ready time",
            "ready_at_utc": ready.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")}


# Alias for a concise public test surface.
entry_bar_for_ready = choose_entry_bar


def choose_entry_on_date(rows: list[dict], entry_date: date) -> dict:
    for index, row in enumerate(rows):
        if row["date"] == entry_date:
            if row.get("entry_value") is None:
                return {"status": "MISSING", "reason": "entry date bar has no valid open"}
            return {"status": "AVAILABLE", "entry_date": entry_date.isoformat(),
                    "entry_open": row["entry_value"], "entry_open_field": row["entry_value_field"],
                    "row_index": index}
    return {"status": "MISSING", "reason": "entry date bar is absent"}


def exit_evidence(rows: list[dict], entry_index: int, entry_open: float) -> dict:
    result: dict = {}
    for horizon in HORIZONS:
        target = entry_index + horizon
        key = str(horizon)
        if target >= len(rows):
            result[key] = {"status": "MISSING", "reason": f"missing {horizon} trading-day exit bar"}
            continue
        row = rows[target]
        if row.get("entry_value") is None:
            result[key] = {"status": "MISSING", "reason": f"invalid {horizon} trading-day exit bar"}
            continue
        gross = row["entry_value"] / entry_open - 1.0
        result[key] = {
            "status": "AVAILABLE", "exit_date": row["date"].isoformat(),
            "exit_open": row["entry_value"], "exit_open_field": row["entry_value_field"],
            "gross_return": gross,
            "net_returns": {f"{bps}bp": net_return(gross, bps) for bps in COST_BPS},
        }
    return result


def net_return(gross_return: float, cost_bps: float) -> float:
    """Apply round-trip cost multiplicatively, matching the research engine."""
    if not math.isfinite(float(gross_return)) or cost_bps < 0:
        raise ValueError("gross return must be finite and cost must be non-negative")
    return (1.0 + float(gross_return)) * (1.0 - float(cost_bps) / 10_000.0) - 1.0


# Explicit alias used by some callers.
costed_return = net_return


def median_dollar_volume(rows: list[dict], before: date, lookback: int = 20) -> float | None:
    prior = [row for row in rows if row["date"] < before]
    if len(prior) < lookback:
        return None
    window = prior[-lookback:]
    values: list[float] = []
    for row in window:
        if not row.get("valid_liquidity"):
            return None
        close = row.get("adjclose") or row.get("close")
        volume = row.get("volume")
        if close is None or volume is None or close <= 0 or volume < 0:
            return None
        values.append(close * volume)
    values.sort()
    middle = len(values) // 2
    return values[middle] if len(values) % 2 else (values[middle - 1] + values[middle]) / 2.0


def assign_terciles(values: dict[str, float]) -> dict[str, int]:
    ordered = sorted(values.items(), key=lambda item: (item[1], item[0]))
    count = len(ordered)
    return {ticker: min(3, 1 + (rank * 3) // count) for rank, (ticker, _) in enumerate(ordered)} if count else {}


def _median(values: list[float]) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2.0


def _read_raw_json(data: bytes) -> dict:
    try:
        payload = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, ValueError):
        raise FetchError("SEC response is not JSON") from None
    if not isinstance(payload, dict):
        raise FetchError("SEC response is not a JSON object")
    return payload


def _record_document(out: Path, raw: bytes, url: str, kind: str, accession: str) -> dict:
    parts = urllib.parse.urlsplit(url)
    filename = Path(parts.path).name
    safe_accession = accession.replace("-", "")
    relative = Path("raw") / "sec" / safe_accession / f"{kind}-{filename}"
    write_atomic(out / relative, raw)
    return {"kind": kind, "url": url, "file": str(relative), "bytes": len(raw), "sha256": sha256(raw)}


def _load_event_documents(client: SecClient, out: Path, event: dict) -> tuple[list[dict], str]:
    primary_name = event.get("primary_document")
    if not event.get("cik") or not event.get("accession") or not primary_name:
        return [], "missing primary document metadata"
    primary = archive_url(event["cik"], event["accession"], str(primary_name))
    documents: list[dict] = []
    texts: list[str] = []
    try:
        response = client.get(primary)
        documents.append(_record_document(out, response.body, primary, "primary", event["accession"]))
        texts.append(visible_text(response.body))
        exhibit_urls = _candidate_exhibit_links(response.body, primary)
        for position, url in enumerate(exhibit_urls, start=1):
            try:
                exhibit = client.get(url)
            except FetchError:
                # The primary release remains usable only if its own evidence
                # is unambiguous; the failed exhibit is explicit in the event.
                continue
            documents.append(_record_document(out, exhibit.body, url, f"exhibit-{position}", event["accession"]))
            texts.append(visible_text(exhibit.body))
    except FetchError as error:
        return documents, str(error)
    return documents, "\n".join(texts)


def _safe_error(error: Exception) -> str:
    # Never include request headers or environment values in persisted errors.
    message = str(error)
    if SEC_USER_AGENT_ENV in message:
        return "SEC request failed"
    return message[:500]


def evaluate_event_document(event: dict, documents: list[dict], release_text: str) -> None:
    package = classify_package(event.get("items"), release_text)
    event["package_class"] = package
    event["documents"] = documents
    if not release_text or not documents:
        event["parser_status"] = "EPS_UNUSABLE"
        event["parser_reason"] = "release document unavailable"
        return
    parsed = parse_eps_pair(release_text)
    event["parser_status"] = parsed["status"]
    event["parser_reason"] = parsed.get("reason")
    event["eps_candidate_count"] = parsed.get("candidate_count", 0)
    if "current_eps" in parsed:
        event["current_eps"] = parsed["current_eps"]
        event["prior_eps"] = parsed["prior_eps"]
        event["eps_change_ratio"] = parsed.get("change_ratio")
        event["eps_evidence"] = parsed.get("evidence")
    if package == "MATERIAL_CONCURRENT":
        event["exclusion_reasons"] = ["MATERIAL_CONCURRENT"]
    elif package not in {"CORE_EARNINGS", "EARNINGS_PACKAGE"}:
        event["exclusion_reasons"] = ["NOT_EARNINGS"]
    elif parsed["status"] != "EPS_STRONG25":
        event["exclusion_reasons"] = [parsed["status"]]
    else:
        event["rule_match"] = True


def _new_manifest(universe_csv: Path, sec_manifest: Path | None,
                  metadata_path: Path | None = None) -> dict:
    inputs = {
        "frozen_universe_csv": str(universe_csv),
        "frozen_universe_sha256": sha256(universe_csv.read_bytes()),
    }
    if sec_manifest and sec_manifest.is_file():
        inputs.update({
            "sec_full_universe_manifest": str(sec_manifest),
            "sec_full_universe_manifest_sha256": sha256(sec_manifest.read_bytes()),
        })
    elif metadata_path:
        inputs.update({
            "sec_universe_metadata": str(metadata_path),
            "sec_universe_metadata_sha256": None,
        })
    return {
        "schema_version": 1,
        "protocol": "EV-1",
        "cutoff_date_exclusive": CUTOFF_DATE.isoformat(),
        "provider": "SEC EDGAR submissions and Archives",
        "config": {
            "primary_rule": "EPS_STRONG25",
            "primary_package": "CORE_EARNINGS",
            "secondary_package": "EARNINGS_PACKAGE",
            "excluded_package": "MATERIAL_CONCURRENT",
            "primary_horizon": PRIMARY_HORIZON,
            "secondary_horizons": list(SECONDARY_HORIZONS),
            "all_horizons": list(HORIZONS),
            "round_trip_cost_bps": list(COST_BPS),
            "ready_rule": "max(acceptanceDateTime, observed_at); same-day open through 09:15 America/New_York",
            "control_rule": "same entry date + SIC2 + pre-entry 20-day median dollar-volume tercile; minimum 3",
            "order_route": "none",
            "xbrl_signal_fallback": "none",
        },
        "inputs": inputs,
        "progress": {
            "polls": 0, "responses": 0, "events_seen": 0, "events_admitted": 0,
            "events_excluded": 0, "events_secondary_diagnostic": 0,
            "unmapped_issuers": 0, "error_count": 0, "requests": 0, "retries": 0,
            "last_poll_started_at": None, "last_poll": None,
        },
        "last_poll": None,
        "errors": [],
        "responses": {},
        "events": {},
    }


def _ensure_manifest(out: Path, universe_csv: Path, sec_manifest: Path | None,
                     metadata_path: Path | None = None) -> dict:
    path = out / MANIFEST_NAME
    if path.is_file():
        manifest = load_json(path)
        manifest.setdefault("progress", {})
        manifest.setdefault("errors", [])
        manifest.setdefault("responses", {})
        manifest.setdefault("events", {})
        return manifest
    manifest = _new_manifest(universe_csv, sec_manifest, metadata_path)
    write_manifest_atomic(path, manifest)
    return manifest


def _append_error(manifest: dict, message: str, ticker: str | None = None) -> None:
    record = {"at": utc_now(), "message": _safe_error(RecorderError(message))}
    if ticker:
        record["ticker"] = ticker
    errors = manifest.setdefault("errors", [])
    errors.append(record)
    del errors[:-100]
    manifest.setdefault("progress", {})["error_count"] = len(errors)


def _submission_raw_path(out: Path, cik: str, body: bytes) -> Path:
    digest = sha256(body)[:16]
    return out / "raw" / "submissions" / f"CIK{int(cik):010d}-{digest}.json"


def _percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * weight


def _entry_date_for_metrics(event: dict) -> str | None:
    entry = event.get("entry")
    value = entry.get("entry_date") if isinstance(entry, dict) else event.get("entry_date")
    try:
        return date.fromisoformat(str(value)).isoformat()
    except (TypeError, ValueError):
        return None


def _net_metric_value(event: dict, horizon: int, kind: str) -> float | None:
    container = event.get("active_returns" if kind == "active" else "absolute_net_returns")
    if not isinstance(container, dict):
        if kind == "absolute":
            returns = event.get("returns")
            container = returns if isinstance(returns, dict) else {}
        else:
            container = {}
    row = container.get(str(horizon))
    if not isinstance(row, dict):
        return None
    net_returns = row.get("net_returns")
    value = net_returns.get("40bp") if isinstance(net_returns, dict) else None
    try:
        value = float(value)
    except (TypeError, ValueError):
        return None
    return value if math.isfinite(value) else None


def _bootstrap_mean_ci(blocks: dict[str, list[float]], seed: int,
                       repetitions: int) -> list[float] | None:
    if not blocks:
        return None
    dates = sorted(blocks)
    randomizer = random.Random(seed)
    means: list[float] = []
    for _ in range(repetitions):
        sample = [value for _ in dates for value in blocks[randomizer.choice(dates)]]
        means.append(sum(sample) / len(sample))
    return [_percentile(means, 0.025), _percentile(means, 0.975)]


def _metric_summary(observations: list[tuple[str, float]], seed: int,
                    repetitions: int) -> dict:
    blocks: dict[str, list[float]] = defaultdict(list)
    for entry_date, value in observations:
        blocks[entry_date].append(value)
    values = [value for _, value in observations]
    if not values:
        return {
            "status": "NO_DATA", "n": 0, "mean": None, "median": None,
            "p10": None, "p90": None, "positive_fraction": None,
            "mean_ci95": None,
        }
    ci = _bootstrap_mean_ci(blocks, seed, repetitions)
    return {
        "status": "OK", "n": len(values), "mean": sum(values) / len(values),
        "median": _median(values), "p10": _percentile(values, 0.10),
        "p90": _percentile(values, 0.90),
        "positive_fraction": sum(value > 0 for value in values) / len(values),
        "mean_ci95": {"lower": ci[0], "upper": ci[1]} if ci else None,
    }


def _metric_observations(events: list[dict], statuses: set[str], package: str,
                         horizon: int, kind: str) -> list[tuple[str, float]]:
    observations: list[tuple[str, float]] = []
    for event in events:
        if event.get("status") not in statuses or event.get("package_class") != package:
            continue
        entry_date = _entry_date_for_metrics(event)
        value = _net_metric_value(event, horizon, kind)
        if entry_date is not None and value is not None:
            observations.append((entry_date, value))
    return observations


def _data_quality(events: list[dict]) -> dict:
    observed_at_present = 0
    missing_controls = 0
    missing_exits = 0
    for event in events:
        try:
            parse_timestamp(event.get("observed_at", ""))
        except (TypeError, ValueError):
            pass
        else:
            observed_at_present += 1
        reasons = set(event.get("exclusion_reasons") or [])
        control_count = event.get("control_count")
        if "INSUFFICIENT_CONTROLS" in reasons or (
                isinstance(control_count, (int, float)) and control_count < 3):
            missing_controls += 1
        package = event.get("package_class")
        required_horizon = PRIMARY_HORIZON if package == "CORE_EARNINGS" else SECONDARY_HORIZONS[0]
        required = event.get("returns", {}).get(str(required_horizon)) \
            if isinstance(event.get("returns"), dict) else None
        if ("MISSING_PRIMARY_EXIT_BAR" in reasons or "MISSING_SECONDARY_EXIT_BAR" in reasons
                or (event.get("status") in {"ADMITTED", "SECONDARY_DIAGNOSTIC"}
                    and (not isinstance(required, dict) or required.get("status") != "AVAILABLE"))):
            missing_exits += 1
    admitted = sum(event.get("status") == "ADMITTED" for event in events)
    excluded = sum(event.get("status") == "EXCLUDED" for event in events)
    secondary = sum(event.get("status") == "SECONDARY_DIAGNOSTIC" for event in events)
    return {
        "events_total": len(events), "events_admitted": admitted,
        "events_excluded": excluded, "events_secondary_diagnostic": secondary,
        "observed_at_present": observed_at_present,
        "observed_at_missing": len(events) - observed_at_present,
        "observed_at_coverage": observed_at_present / len(events) if events else None,
        "missing_controls": missing_controls, "missing_exits": missing_exits,
    }


def recompute_progress_metrics(manifest: dict, clock: Callable[[], datetime] | None = None) -> dict:
    """Recompute all progress metrics solely from persisted event records."""
    raw_events = manifest.get("events", {})
    events = ([event for event in raw_events.values() if isinstance(event, dict)]
              if isinstance(raw_events, dict) else [])
    primary = {
        "horizon_days": PRIMARY_HORIZON, "cost_bps": 40,
        "absolute": _metric_summary(
            _metric_observations(events, {"ADMITTED"}, "CORE_EARNINGS", PRIMARY_HORIZON, "absolute"),
            METRICS_CI_SEED, METRICS_CI_REPETITIONS),
        "active": _metric_summary(
            _metric_observations(events, {"ADMITTED"}, "CORE_EARNINGS", PRIMARY_HORIZON, "active"),
            METRICS_CI_SEED, METRICS_CI_REPETITIONS),
    }
    secondary: dict[str, dict] = {}
    for horizon in SECONDARY_HORIZONS:
        secondary[str(horizon)] = {
            "horizon_days": horizon, "cost_bps": 40,
            "absolute": _metric_summary(
                _metric_observations(events, {"SECONDARY_DIAGNOSTIC"}, "EARNINGS_PACKAGE", horizon, "absolute"),
                METRICS_CI_SEED, METRICS_CI_REPETITIONS),
            "active": _metric_summary(
                _metric_observations(events, {"SECONDARY_DIAGNOSTIC"}, "EARNINGS_PACKAGE", horizon, "active"),
                METRICS_CI_SEED, METRICS_CI_REPETITIONS),
        }
    metrics = {
        "status": "OK" if primary["absolute"]["n"] or primary["active"]["n"] else "NO_DATA",
        "ci": {"method": METRICS_CI_METHOD, "seed": METRICS_CI_SEED,
               "repetitions": METRICS_CI_REPETITIONS, "confidence": 0.95},
        "primary": primary, "secondary": secondary,
        "metrics_updated_at_utc": utc_now(clock),
        "data_quality": _data_quality(events),
    }
    manifest.setdefault("progress", {})["metrics"] = metrics
    return metrics


def _refresh_progress(manifest: dict, client: SecClient) -> None:
    progress = manifest.setdefault("progress", {})
    events = manifest.get("events", {})
    progress["events_seen"] = len(events)
    progress["events_admitted"] = sum(1 for event in events.values() if event.get("status") == "ADMITTED")
    progress["events_excluded"] = sum(1 for event in events.values() if event.get("status") == "EXCLUDED")
    progress["events_secondary_diagnostic"] = sum(
        1 for event in events.values() if event.get("status") == "SECONDARY_DIAGNOSTIC"
    )
    progress["error_count"] = len(manifest.get("errors", []))
    progress["requests"] = client.throttle.requests
    progress["retries"] = client.retries


def poll_once(manifest: dict, universe: list[dict], client: SecClient, out: Path,
              price_dir: Path, persist: Callable[[dict], None] | None = None) -> dict:
    """Poll all mapped issuers once and atomically persist progress as it advances."""
    progress = manifest.setdefault("progress", {})
    progress["polls"] = int(progress.get("polls", 0)) + 1
    progress["last_poll_started_at"] = utc_now()
    if persist:
        persist(manifest)
    for issuer in universe:
        if issuer.get("mapping_status") != "MAPPED":
            progress["unmapped_issuers"] = int(progress.get("unmapped_issuers", 0)) + 1
            continue
        try:
            response = client.get(submissions_url(issuer["cik"]))
            raw_path = _submission_raw_path(out, issuer["cik"], response.body)
            if not raw_path.is_file():
                write_atomic(raw_path, response.body)
            manifest.setdefault("responses", {})[issuer["cik"]] = {
                "kind": "submissions",
                "ticker": issuer["ticker"],
                "url": response.url,
                "file": str(raw_path.relative_to(out)),
                "bytes": len(response.body),
                "sha256": sha256(response.body),
                "observed_at": response.observed_at,
            }
            progress["responses"] = int(progress.get("responses", 0)) + 1
            payload = _read_raw_json(response.body)
            for candidate in extract_submission_events(payload, issuer, response.observed_at):
                accession = candidate["accession"]
                if accession in manifest["events"]:
                    # Preserve first observed_at and discovery hash; a later
                    # poll is persistence evidence, not a new observation.
                    existing = manifest["events"][accession]
                    existing["last_seen_at"] = response.observed_at
                    existing["last_submission_sha256"] = sha256(response.body)
                    if existing.get("document_error"):
                        documents, release_text_or_error = _load_event_documents(client, out, existing)
                        if documents and release_text_or_error and not release_text_or_error.startswith(("HTTP ", "network ", "missing ")):
                            evaluate_event_document(existing, documents, release_text_or_error)
                            existing.pop("document_error", None)
                        else:
                            existing["document_error"] = release_text_or_error
                    continue
                event = dict(candidate)
                event["submission_sha256"] = sha256(response.body)
                event["submission_file"] = str(raw_path.relative_to(out))
                event["status"] = "FETCHING_DOCUMENTS"
                documents, release_text_or_error = _load_event_documents(client, out, event)
                if documents and release_text_or_error and not release_text_or_error.startswith(("HTTP ", "network ", "missing ")):
                    evaluate_event_document(event, documents, release_text_or_error)
                else:
                    evaluate_event_document(event, documents, "")
                    event["document_error"] = release_text_or_error
                manifest["events"][accession] = event
        except (FetchError, ValueError, OSError) as error:
            _append_error(manifest, _safe_error(error), issuer["ticker"])
        _refresh_progress(manifest, client)
        if persist:
            persist(manifest)
    progress["last_poll"] = utc_now()
    manifest["last_poll"] = progress["last_poll"]
    evaluate_prices(manifest, universe, price_dir)
    recompute_progress_metrics(manifest)
    _refresh_progress(manifest, client)
    if persist:
        persist(manifest)
    return manifest


def _event_ready(event: dict) -> datetime | None:
    ready, error = _parse_ready(event.get("acceptanceDateTime"), event.get("observed_at"))
    if error:
        event.setdefault("exclusion_reasons", []).append("INVALID_READY_TIMESTAMP")
    return ready


def _price_cache(price_dir: Path, universe: list[dict]) -> dict[str, list[dict]]:
    return {issuer["ticker"]: load_price_rows(price_dir / f"{_norm_ticker(issuer['ticker'])}.csv") for issuer in universe}


def _set_excluded(event: dict, reason: str) -> None:
    reasons = event.setdefault("exclusion_reasons", [])
    if reason not in reasons:
        reasons.append(reason)
    event["status"] = "EXCLUDED"


def _return_summary(evidence: dict | None, horizon: int) -> dict | None:
    if not evidence:
        return None
    return evidence.get(str(horizon))


def evaluate_prices(manifest: dict, universe: list[dict], price_dir: Path) -> None:
    """Evaluate fixed EV-1 rule and same-date matched controls only."""
    prices = _price_cache(price_dir, universe)
    prepared: dict[str, dict] = {}
    event_dates: dict[str, set[str]] = defaultdict(set)
    # First establish entry dates for every detected Item 2.02 issuer, including
    # excluded package/parser events, so controls cannot leak same-date event issuers.
    for event in manifest.get("events", {}).values():
        ticker = _norm_ticker(str(event.get("ticker", "")))
        rows = prices.get(ticker, [])
        ready = _event_ready(event)
        if ready is None:
            _set_excluded(event, "INVALID_READY_TIMESTAMP")
            continue
        entry = choose_entry_bar(rows, ready)
        event["ready_at_utc"] = ready.isoformat().replace("+00:00", "Z")
        event["entry"] = entry
        if entry.get("status") != "AVAILABLE":
            _set_excluded(event, "MISSING_ENTRY_BAR")
            continue
        event_date = entry["entry_date"]
        event_dates[event_date].add(ticker)
        prepared[event.get("accession", "")] = {
            "ticker": ticker, "entry_date": date.fromisoformat(event_date),
            "entry": entry, "rows": rows,
        }
    # Build price/liquidity evidence once per entry date.
    date_cache: dict[str, dict] = {}
    for accession, prep in prepared.items():
        entry_date = prep["entry_date"]
        date_key = entry_date.isoformat()
        if date_key in date_cache:
            continue
        liquidity: dict[str, float] = {}
        entry_bars: dict[str, dict] = {}
        exits: dict[str, dict] = {}
        for ticker, rows in prices.items():
            entry = choose_entry_on_date(rows, entry_date)
            entry_bars[ticker] = entry
            median = median_dollar_volume(rows, entry_date)
            if median is not None:
                liquidity[ticker] = median
            if entry.get("status") == "AVAILABLE":
                exits[ticker] = exit_evidence(rows, entry["row_index"], entry["entry_open"])
        date_cache[date_key] = {
            "liquidity": liquidity,
            "terciles": assign_terciles(liquidity),
            "entry_bars": entry_bars,
            "exits": exits,
        }
    for accession, event in manifest.get("events", {}).items():
        prep = prepared.get(accession)
        if not prep:
            continue
        entry_date = prep["entry_date"]
        date_key = entry_date.isoformat()
        cache = date_cache[date_key]
        ticker = prep["ticker"]
        event["liquidity_median_dollar_volume"] = cache["liquidity"].get(ticker)
        event["liquidity_tercile"] = cache["terciles"].get(ticker)
        event["returns"] = cache["exits"].get(ticker, {})
        event["absolute_net_returns"] = {
            horizon: row.get("net_returns")
            for horizon, row in event["returns"].items()
            if row.get("status") == "AVAILABLE"
        }
        event["primary_rule"] = "EPS_STRONG25 + CORE_EARNINGS"
        event["secondary_rule"] = "EARNINGS_PACKAGE diagnostic at 3/10/20d"
        if event.get("package_class") == "MATERIAL_CONCURRENT":
            _set_excluded(event, "MATERIAL_CONCURRENT")
            continue
        if event.get("parser_status") != "EPS_STRONG25":
            _set_excluded(event, event.get("parser_status", "EPS_UNUSABLE"))
            continue
        if event.get("package_class") not in {"CORE_EARNINGS", "EARNINGS_PACKAGE"}:
            _set_excluded(event, "NOT_EARNINGS")
            continue
        if ticker not in cache["liquidity"] or ticker not in cache["terciles"]:
            _set_excluded(event, "MISSING_EVENT_LIQUIDITY")
            continue
        is_primary = event.get("package_class") == "CORE_EARNINGS"
        required_horizon = PRIMARY_HORIZON if is_primary else SECONDARY_HORIZONS[0]
        required_return = _return_summary(event["returns"], required_horizon)
        if not required_return or required_return.get("status") != "AVAILABLE":
            reason = "MISSING_PRIMARY_EXIT_BAR" if is_primary else "MISSING_SECONDARY_EXIT_BAR"
            _set_excluded(event, reason)
            continue
        controls: list[dict] = []
        control_exclusions: list[dict] = []
        target_sic2 = event.get("sic2")
        target_tercile = cache["terciles"].get(ticker)
        same_date_issuers = event_dates[date_key]
        for issuer in universe:
            control_ticker = issuer["ticker"]
            if control_ticker == ticker:
                continue
            if issuer.get("mapping_status") != "MAPPED":
                continue
            if issuer.get("sic2") != target_sic2 or cache["terciles"].get(control_ticker) != target_tercile:
                continue
            reasons: list[str] = []
            if control_ticker in same_date_issuers:
                reasons.append("same_date_event_issuer")
            entry = cache["entry_bars"].get(control_ticker, {})
            if entry.get("status") != "AVAILABLE":
                reasons.append("missing_or_invalid_entry_bar")
            if control_ticker not in cache["liquidity"]:
                reasons.append("missing_or_invalid_preentry_liquidity")
            control_returns = cache["exits"].get(control_ticker, {})
            control_required = _return_summary(control_returns, required_horizon)
            if not control_required or control_required.get("status") != "AVAILABLE":
                reasons.append("missing_or_invalid_primary_exit_bar" if is_primary
                               else "missing_or_invalid_secondary_exit_bar")
            if reasons:
                control_exclusions.append({"ticker": control_ticker, "reasons": reasons})
                continue
            controls.append({
                "ticker": control_ticker, "sic2": issuer.get("sic2"),
                "liquidity_tercile": target_tercile,
                "entry_date": date_key, "entry_open": entry["entry_open"],
                "returns": control_returns,
            })
        event["control_exclusions"] = control_exclusions
        event["controls"] = controls
        event["control_count"] = len(controls)
        event["control_median_returns"] = _median_control_returns(controls)
        if len(controls) < 3:
            _set_excluded(event, "INSUFFICIENT_CONTROLS")
            continue
        event["active_returns"] = _active_returns(event["returns"], event["control_median_returns"])
        if is_primary:
            event["status"] = "ADMITTED"
            event["exclusion_reasons"] = []
        else:
            event["status"] = "SECONDARY_DIAGNOSTIC"
            event["exclusion_reasons"] = ["SECONDARY_EARNINGS_PACKAGE"]


def _median_control_returns(controls: list[dict]) -> dict:
    result: dict = {}
    for horizon in HORIZONS:
        available = [control["returns"].get(str(horizon)) for control in controls
                     if control["returns"].get(str(horizon), {}).get("status") == "AVAILABLE"]
        if not available:
            result[str(horizon)] = None
            continue
        gross = _median([row["gross_return"] for row in available])
        nets = {f"{bps}bp": _median([row["net_returns"][f"{bps}bp"] for row in available])
                for bps in COST_BPS}
        result[str(horizon)] = {"gross_return": gross, "net_returns": nets,
                                "sample_count": len(available)}
    return result


def _active_returns(event_returns: dict, control_medians: dict) -> dict:
    result: dict = {}
    for horizon in HORIZONS:
        event_row = event_returns.get(str(horizon))
        control_row = control_medians.get(str(horizon))
        if not event_row or event_row.get("status") != "AVAILABLE" or not control_row:
            result[str(horizon)] = None
            continue
        result[str(horizon)] = {
            "gross_return": event_row["gross_return"] - control_row["gross_return"],
            "net_returns": {
                f"{bps}bp": event_row["net_returns"][f"{bps}bp"] - control_row["net_returns"][f"{bps}bp"]
                for bps in COST_BPS
            },
        }
    return result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--universe", type=Path,
                        default=Path("claudedocs/q1-universe-snapshot-spy-2026-11-20.csv"))
    parser.add_argument("--sec-manifest", type=Path,
                        default=Path("research-data/sec-event-full/manifest.json"))
    parser.add_argument("--price-dir", type=Path, default=Path("research-data/event-prices-yahoo"))
    parser.add_argument("--out", type=Path, default=Path("research-data/ev1-forward"))
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--once", action="store_true", help="poll every frozen issuer once")
    mode.add_argument("--loop-seconds", type=float, help="poll repeatedly at this interval")
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--max-retries", type=int, default=4)
    parser.add_argument("--backoff", type=float, default=2.0)
    parser.add_argument("--min-interval", type=float, default=SEC_MIN_INTERVAL)
    return parser


def run(args, environ=None, opener=None, sleep: Callable[[float], None] = time.sleep,
        wall_clock: Callable[[], datetime] | None = None,
        mono_clock: Callable[[], float] = time.monotonic) -> int:
    environ = os.environ if environ is None else environ
    user_agent = (environ.get(SEC_USER_AGENT_ENV) or "").strip()
    if not user_agent:
        raise SetupError("SEC_USER_AGENT is not set; no SEC request was made")
    if args.loop_seconds is not None and args.loop_seconds <= 0:
        raise SetupError("--loop-seconds must be positive")
    if args.min_interval < SEC_MIN_INTERVAL:
        raise SetupError("--min-interval must be at least 0.2 seconds (maximum 5 requests/s)")
    universe_path = args.universe.resolve()
    sec_manifest_path = args.sec_manifest.resolve()
    out = args.out.resolve()
    price_dir = args.price_dir.resolve()
    metadata_path = out / "universe-metadata.json"
    manifest = _ensure_manifest(out, universe_path, sec_manifest_path, metadata_path)
    persist = lambda value: write_manifest_atomic(out / MANIFEST_NAME, value)
    client = SecClient(user_agent, opener=opener,
                       throttle=Throttle(args.min_interval, sleep=sleep, clock=mono_clock),
                       timeout=args.timeout, max_retries=args.max_retries,
                       backoff=args.backoff, sleep=sleep, clock=wall_clock)
    try:
        universe = load_universe(universe_path, sec_manifest_path, metadata_path)
    except SetupError as error:
        if "bootstrap is required" not in str(error):
            raise
        universe = bootstrap_universe(client, universe_path, metadata_path)
    manifest.setdefault("inputs", {})["sec_universe_metadata"] = str(metadata_path)
    manifest["inputs"]["sec_universe_metadata_sha256"] = sha256(metadata_path.read_bytes())
    persist(manifest)
    while True:
        poll_once(manifest, universe, client, out, price_dir, persist=persist)
        if args.once:
            break
        sleep(args.loop_seconds)
    return 0 if not manifest.get("errors") else 1


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return run(args)
    except (SetupError, RecorderError, OSError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
