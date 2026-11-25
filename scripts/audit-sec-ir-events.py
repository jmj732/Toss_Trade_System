#!/usr/bin/env python3
"""Read-only SEC/IR event-data audit helper.

No returns, signals, backtests, or order routes. Reuses the existing SEC manifest and price CSVs,
fetches only SEC Archives filing documents and optional issuer IR URLs supplied in a mapping JSON.
The SEC contact is read from SEC_USER_AGENT and never printed or persisted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, time, timedelta
from html.parser import HTMLParser
from pathlib import Path
from zoneinfo import ZoneInfo

NEW_YORK = ZoneInfo("America/New_York")
DEFAULT_SYMBOLS = [
    "AAPL", "MSFT", "NVDA", "AMZN", "CSCO", "ORCL", "JPM", "BAC", "SLB", "CVX",
    "JNJ", "PFE", "AMGN", "PG", "WMT", "MCD", "CAT", "BA", "HD", "NKE",
]
EASTERN_OPEN = time(9, 30)
EASTERN_CLOSE = time(16, 0)
ARCHIVE_PREFIX = "https://www.sec.gov/Archives/edgar/data/"
IR_HOSTS = frozenset({
    "www.apple.com", "www.microsoft.com", "investor.nvidia.com", "ir.aboutamazon.com",
    "investor.cisco.com", "investor.oracle.com", "jpmorganchaseco.gcs-web.com",
    "investor.bankofamerica.com", "investorcenter.slb.com", "chevroncorp.gcs-web.com",
    "www.investor.jnj.com", "investors.pfizer.com", "investors.amgen.com", "www.pginvestor.com",
    "corporate.walmart.com", "corporate.mcdonalds.com", "www.caterpillar.com",
    "investors.boeing.com", "ir.homedepot.com", "investors.nike.com",
})


class FetchError(RuntimeError):
    pass


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links: list[tuple[str, str]] = []
        self._href: str | None = None
        self._text: list[str] = []

    def handle_starttag(self, tag, attrs):
        if tag.lower() == "a":
            self._href = dict(attrs).get("href")
            self._text = []

    def handle_data(self, data):
        if self._href is not None:
            self._text.append(" ".join(data.split()))

    def handle_endtag(self, tag):
        if tag.lower() == "a" and self._href is not None:
            self.links.append((self._href, " ".join(self._text).strip()))
            self._href = None
            self._text = []


class Text(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts: list[str] = []

    def handle_data(self, data):
        clean = " ".join(data.split())
        if clean:
            self.parts.append(clean)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fetch(url: str, user_agent: str, timeout: float = 30.0) -> bytes:
    parts = urllib.parse.urlsplit(url)
    if parts.scheme != "https" or parts.hostname != "www.sec.gov":
        raise FetchError(f"refused URL host/scheme: {parts.hostname}")
    if not parts.path.startswith("/Archives/edgar/data/") or parts.query or parts.fragment:
        raise FetchError("refused SEC Archive route")
    req = urllib.request.Request(url, method="GET", headers={"User-Agent": user_agent, "Accept": "text/html"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as response:
            return response.read()
    except (urllib.error.HTTPError, urllib.error.URLError, OSError) as exc:
        raise FetchError(f"fetch failed: {url} ({type(exc).__name__})") from None


def fetch_ir(url: str, timeout: float = 30.0) -> tuple[bytes, str, str]:
    """Fetch one preselected official IR page with a generic User-Agent, never SEC_USER_AGENT."""
    parts = urllib.parse.urlsplit(url)
    host = parts.hostname or ""
    if parts.scheme != "https" or (host not in IR_HOSTS and not host.endswith(".q4cdn.com")):
        raise FetchError(f"refused IR URL host/scheme: {host}")
    if parts.fragment:
        raise FetchError("refused IR URL fragment")
    req = urllib.request.Request(url, method="GET", headers={
        "User-Agent": "research-audit/1.0", "Accept": "text/html,application/pdf,*/*"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=timeout) as response:
            return response.read(), response.geturl(), response.headers.get("Content-Type", "")
    except (urllib.error.HTTPError, urllib.error.URLError, OSError) as exc:
        raise FetchError(f"IR fetch failed: {url} ({type(exc).__name__})") from None


def html_text(raw: bytes) -> str:
    parser = Text()
    parser.feed(raw.decode("utf-8", errors="replace"))
    return " ".join(parser.parts)


def archive_url(cik: str, accession: str, filename: str) -> str:
    return f"{ARCHIVE_PREFIX}{int(cik)}/{accession.replace('-', '')}/{filename}"


def parse_eps(text: str) -> list[dict]:
    # Context is retained so a human can reject a number from another period before any return use.
    # Issuers use earnings/profit/loss per share, GAAP/non-GAAP EPS, and table labels with
    # punctuation. First value in a paired GAAP/non-GAAP sentence is retained; no value is
    # inferred from a different period or from a forecast range.
    patterns = [
        r"(?:diluted|basic and diluted)\s+(?:earnings\s+)?per\s+(?:diluted\s+)?share(?:\s+was|\s+were|\s+is|\s+are|\s+of|\s*[:=])?\s*\$?\s*([0-9]+(?:\.[0-9]+)?)",
        r"(?:earnings|profit|loss)\s+per\s+(?:diluted\s+)?share(?:\s+was|\s+were|\s+is|\s+are|\s+of|\s*[:=]|\s*\([^)]*\))?\s*\(?\$?\s*(-?[0-9]+(?:\.[0-9]+)?)",
        r"(?:diluted|basic and diluted)\s+EPS(?:\s+was|\s+were|\s+is|\s+are|\s+of|\s*[:=])?\s*\$?\s*([0-9]+(?:\.[0-9]+)?)",
        r"(?:GAAP|non-GAAP)\s+EPS\s*(?:was|were|is|are|of|to|[:=])?\s*\$?\s*([0-9]+(?:\.[0-9]+)?)",
        r"earnings\s+per\s+share[^$]{0,100}?(?:to|of|was|were|is|are|[:=])\s*\$?\s*([0-9]+(?:\.[0-9]+)?)",
        r"(?:earnings|profit|loss)\s+per\s+(?:diluted\s+)?share[^$]{0,100}\$\s*(-?[0-9]+(?:\.[0-9]+)?)",
        r"(?:earnings|profit|loss)\s+per\s+share[^0-9]{0,100}\$?\s*(-?[0-9]+\.[0-9]+)",
        r"\(\s*\$?(-?[0-9]+(?:\.[0-9]+)?)\s+per\s+(?:diluted\s+)?share",
        r"earnings\s+per\s+share\s*\(?(?:diluted)\)?\s*[:=]?\s*\$?\s*([0-9]+(?:\.[0-9]+)?)",
    ]
    found = []
    for pattern in patterns:
        for match in re.finditer(pattern, text, flags=re.I):
            start, end = max(0, match.start() - 160), min(len(text), match.end() + 160)
            value = match.group(1)
            item = {"value": value, "pattern": pattern, "context": text[start:end]}
            if item not in found:
                found.append(item)
    return found[:20]


def parse_acceptance(value: str) -> datetime | None:
    if not value or not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(NEW_YORK)


def next_trading_open(price_file: Path, after: datetime) -> dict:
    dates = []
    with price_file.open(newline="", encoding="utf-8") as handle:
        rows = list(__import__("csv").DictReader(handle))
    # An open is executable only before that session starts. A filing accepted during/after
    # the session therefore maps to the next trading date, not the already-passed open.
    earliest_date = after.date() if after.time() < EASTERN_OPEN else after.date() + timedelta(days=1)
    for row in rows:
        try:
            d = date.fromisoformat(row["date"])
        except (KeyError, ValueError):
            continue
        if d >= earliest_date and row.get("open"):
            dates.append((d, row["open"]))
    if not dates:
        return {"status": "MISSING", "reason": "no executable open after SEC acceptance", "date": None, "open": None}
    d, open_ = dates[0]
    same_day = d == after.date() and after.time() < EASTERN_OPEN
    return {
        "status": "AVAILABLE",
        "date": d.isoformat(),
        "open": open_,
        "same_day_open_possible_from_sec_time": same_day,
        "basis": "SEC acceptance lower bound; IR time may change same-day eligibility",
    }


def load_events(sec_dir: Path, symbols: list[str], as_of: date) -> list[dict]:
    manifest = json.loads((sec_dir / "manifest.json").read_text(encoding="utf-8"))
    events = []
    for symbol in symbols:
        entry = manifest.get("symbols", {}).get(symbol)
        if not entry:
            events.append({"symbol": symbol, "status": "SEC_INPUT_MISSING"})
            continue
        rows = []
        for relative in entry.get("files", {}):
            if "/submissions/" not in relative:
                continue
            payload = json.loads((sec_dir / relative).read_text(encoding="utf-8"))
            block = payload.get("filings", {}).get("recent", payload)
            forms = block.get("form", [])
            for i, form in enumerate(forms):
                items = block.get("items", [])
                if form != "8-K" or i >= len(items) or "2.02" not in str(items[i]):
                    continue
                def at(name):
                    values = block.get(name, [])
                    return values[i] if i < len(values) else None
                filing_date = at("filingDate")
                if not filing_date or date.fromisoformat(filing_date) > as_of:
                    continue
                rows.append({
                    "symbol": symbol, "cik": entry.get("cik"), "accession": at("accessionNumber"),
                    "filing_date": filing_date, "report_date": at("reportDate"),
                    "acceptance_utc": at("acceptanceDateTime"), "acceptance_et": None,
                    "items": at("items"), "primary_document": at("primaryDocument"),
                })
        # One recent candidate per issuer for the feasibility audit; duplicates retained in a separate count.
        rows = sorted({r["accession"]: r for r in rows if r.get("accession")}.values(),
                      key=lambda r: (r["filing_date"], r["accession"]))
        if not rows:
            events.append({"symbol": symbol, "status": "SEC_EVENT_MISSING", "candidate_count": 0})
            continue
        candidate = rows[-1]
        candidate["candidate_count"] = len(rows)
        candidate["acceptance_et"] = (parse_acceptance(candidate["acceptance_utc"]).isoformat()
                                      if parse_acceptance(candidate["acceptance_utc"]) else None)
        candidate["status"] = "SEC_EVENT_FOUND"
        events.append(candidate)
    return events


def audit(args) -> dict:
    user_agent = (args.user_agent or "").strip()
    if not user_agent:
        raise SystemExit("SEC_USER_AGENT must be supplied; value is never printed")
    as_of = date.fromisoformat(args.as_of)
    sec_dir = Path(args.sec_dir)
    price_dir = Path(args.price_dir)
    raw_dir = Path(args.out_dir) / "raw" / "sec"
    ir_dir = Path(args.out_dir) / "raw" / "ir"
    raw_dir.mkdir(parents=True, exist_ok=True)
    ir_dir.mkdir(parents=True, exist_ok=True)
    mapping = json.loads(Path(args.ir_map).read_text(encoding="utf-8")) if args.ir_map else {}
    results = []
    for event in load_events(sec_dir, args.symbols, as_of):
        symbol = event["symbol"]
        if event.get("status") != "SEC_EVENT_FOUND":
            results.append(event)
            continue
        cik = event["cik"]
        accession = event["accession"]
        base = archive_url(cik, accession, event["primary_document"])
        try:
            primary = fetch(base, user_agent)
            (raw_dir / f"{symbol}-{accession.replace('-', '')}-primary.htm").write_bytes(primary)
            parser = Links(); parser.feed(primary.decode("utf-8", errors="replace"))
            candidates = [(href, label) for href, label in parser.links
                          if href and re.search(r"(?:99(?:[.-]?1)|exhibit|earnings|press)", href + " " + label, re.I)
                          and not href.startswith("#")]
            seen = set(); exhibits = []
            for href, label in candidates:
                url = urllib.parse.urljoin(base, href)
                if url in seen or not url.startswith(ARCHIVE_PREFIX):
                    continue
                seen.add(url)
                try:
                    raw = fetch(url, user_agent)
                except FetchError:
                    continue
                filename = Path(urllib.parse.urlsplit(url).path).name or f"exhibit-{len(exhibits)}.htm"
                (raw_dir / f"{symbol}-{accession.replace('-', '')}-{filename}").write_bytes(raw)
                exhibits.append({"url": url, "label": label, "filename": filename, "sha256": sha256(raw),
                                 "bytes": len(raw), "eps": parse_eps(html_text(raw))})
            primary_text = html_text(primary)
            all_eps = parse_eps(primary_text)
            for exhibit in exhibits:
                all_eps.extend(exhibit["eps"])
            event.update({
                "sec_primary_url": base,
                "sec_primary_sha256": sha256(primary),
                "sec_primary_bytes": len(primary),
                "sec_exhibits": exhibits,
                "eps_candidates": all_eps[:30],
                "eps_status": "FOUND" if all_eps else "UNAVAILABLE",
            })
        except FetchError as error:
            event.update({"sec_archive_status": "FETCH_ERROR", "sec_archive_error": str(error),
                          "eps_status": "UNAVAILABLE"})
        ir = mapping.get(symbol, {})
        event["ir"] = {"url": ir.get("url"), "status": "NOT_ATTEMPTED" if ir.get("url") else "PAGE_UNAVAILABLE",
                       "posted_at": ir.get("posted_at"), "timezone": ir.get("timezone"),
                       "source_sha256": None}
        if ir.get("url"):
            try:
                raw, final_url, content_type = fetch_ir(ir["url"])
                ext = ".pdf" if ir.get("format") == "pdf" or "pdf" in content_type.lower() else ".html"
                ir_path = ir_dir / f"{symbol}{ext}"
                ir_path.write_bytes(raw)
                event["ir"].update({"status": "FETCHED", "final_url": final_url,
                                    "content_type": content_type.split(";", 1)[0],
                                    "bytes": len(raw), "source_sha256": sha256(raw),
                                    "file": str(ir_path)})
            except FetchError as error:
                event["ir"]["status"] = "FETCH_ERROR"
                event["ir"]["error"] = str(error)
        price_file = price_dir / f"{symbol}.csv"
        event["first_executable_open"] = (next_trading_open(price_file, parse_acceptance(event["acceptance_utc"]))
                                            if price_file.is_file() and parse_acceptance(event["acceptance_utc"])
                                            else {"status": "PRICE_INPUT_MISSING"})
        results.append(event)
    payload = {"protocol": "claudedocs/event-audit-protocol.md", "as_of": args.as_of,
               "symbols": args.symbols, "results": results,
               "note": "No returns or strategy results computed; SEC_USER_AGENT source is env only."}
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True), encoding="utf-8")
    return payload


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--sec-dir", default="research-data/sec")
    parser.add_argument("--price-dir", default="research-data/leadlag-tr")
    parser.add_argument("--out-dir", default="research-data/event-audit")
    parser.add_argument("--out", default="research-data/event-audit/sec-ir-audit.json")
    parser.add_argument("--ir-map")
    parser.add_argument("--as-of", default="2026-11-24")
    parser.add_argument("--symbols", nargs="+", default=DEFAULT_SYMBOLS)
    parser.add_argument("--user-agent", default=None, help=argparse.SUPPRESS)
    args = parser.parse_args()
    args.user_agent = args.user_agent or __import__("os").environ.get("SEC_USER_AGENT")
    payload = audit(args)
    counts = {}
    for row in payload["results"]:
        counts[row.get("status", "UNKNOWN")] = counts.get(row.get("status", "UNKNOWN"), 0) + 1
    print(json.dumps({"rows": len(payload["results"]), "status": counts}, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
