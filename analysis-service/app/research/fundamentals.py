"""Point-in-time quarterly fundamentals panel from SEC EDGAR companyfacts (research input for GP/A, Q-1).

Stdlib only. No network I/O: input is the raw JSON written by ``scripts/collect-sec-companyfacts.py``.

Mirrors ``trading-backend/.../SecCompanyFactsProvider.java`` where it has an equivalent:
    revenue aliases and per-version alias precedence (Java factRows/allRows, lines 1268-1330),
    eligible forms 10-K, 10-K/A, 10-Q, 10-Q/A (line 1022),
    inclusive day count (lines 1407-1409), 70-105 day standalone quarters, 106-285 day YTD differencing,
    Q4 = 10-K annual minus 9M YTD with 200-300 day prior (lines 947-1001),
    annual 330-371 days (line 973), ambiguous duplicates give no value (uniqueFact, lines 1417-1423).

Not in the Java provider (new here, documented in research-data/sec-audit.md):
    COGS, GrossProfit and total Assets tag preferences; first-reported vs latest versions;
    point-in-time availability; the filed-date fallback at end of the New York day (Java uses start of day UTC,
    lines 159 and 167, which is earlier than the filing could be seen).

Point-in-time definitions:
    Component availability = acceptanceDateTime of the filing that first reported the component
        (submissions JSON, matched by accession). Fallback: filed date at 23:59:59 America/New_York, flagged.
    TTM at quarter end t = sum of the four contiguous standalone quarters ending at t (each quarter must start the
        day after the previous quarter ends). First-reported TTM uses each quarter's first print. Latest TTM uses each
        quarter's latest print. Both are kept.
    Availability of a TTM value = max availability of its components. Assets are an instant at t from the filing
        that first reported that balance sheet date.
    GP/A(t) = (Revenue TTM - COGS TTM) / Total assets(t). When COGS TTM is missing and the issuer reports the
        GrossProfit concept, GrossProfit TTM is used and the method is recorded.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from zoneinfo import ZoneInfo

NEW_YORK = ZoneInfo("America/New_York")
REVENUE_TAGS = ("RevenueFromContractWithCustomerExcludingAssessedTax", "Revenues", "SalesRevenueNet")
# Total cost of revenue first. MSFT reports CostOfRevenue as the total; AAPL reports CostOfGoodsAndServicesSold
# as its total cost of sales (evidence: per-issuer tag counts over the LL-1 universe).
COST_TAGS = ("CostOfRevenue", "CostOfGoodsAndServicesSold", "CostOfGoodsSold")
GROSS_PROFIT_TAG = "GrossProfit"
ASSETS_TAG = "Assets"
FLOW_GROUPS = {"revenue": REVENUE_TAGS, "cogs": COST_TAGS, "gross_profit": (GROSS_PROFIT_TAG,)}
ELIGIBLE_FORMS = ("10-K", "10-K/A", "10-Q", "10-Q/A")
# Java uses 70-105. 120 admits a 16-week fiscal Q4 (52/53-week years, e.g. PEP 2025-09-07..2025-12-27).
# Quarters still have to tile exactly for a TTM, so the wider window cannot double count.
QUARTER_DAYS = (70, 120)
YTD_DAYS = (106, 285)
NINE_MONTH_DAYS = (200, 300)
ANNUAL_DAYS = (330, 371)
ACCEPTANCE_SOURCE = "ACCEPTANCE"
FALLBACK_SOURCE = "FILED_EOD_FALLBACK"
PANEL_NAME = "sec-fundamentals-panel.json"


class FundamentalsError(ValueError):
    """Malformed companyfacts or submissions input."""


@dataclass(frozen=True)
class Row:
    """One companyfacts USD value after alias resolution. Duplicate keys keep the first alias (Java factRows)."""

    concept: str
    start: date | None
    end: date
    value: Decimal
    accession: str
    form: str
    filed: date
    fy: int | None
    fp: str | None


@dataclass(frozen=True)
class Filing:
    """One filing from the submissions index."""

    accession: str
    form: str
    filed: date
    report_date: date | None
    acceptance: datetime | None


@dataclass(frozen=True)
class Quarter:
    group: str
    start: date
    end: date
    value: Decimal
    concept: str
    method: str  # DIRECT_3M | YTD_DIFF | FY_MINUS_9M
    accession: str
    form: str
    filed: date
    available_at: datetime
    availability_source: str
    fiscal_year: int | None = None  # provenance only: describes the filing, not the period
    fiscal_period: str | None = None


def parse_date(value) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(str(value))
    except ValueError:
        return None


def parse_acceptance(value) -> datetime | None:
    """acceptanceDateTime is written with a Z suffix and is UTC (verified: AAPL 10-Q at 10:00-11:01Z = 06:0x ET)."""
    if not value or not isinstance(value, str):
        return None
    text = value.replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return parsed.astimezone(timezone.utc) if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def acceptance_value(value) -> datetime | None:
    """Exact 00:00:00 New York time is a date-only placeholder (every pre-2005 midnight acceptance in the collected
    data is exact). It is treated as missing, so the filed-date fallback applies."""
    acceptance = parse_acceptance(value)
    if acceptance is not None and acceptance.astimezone(NEW_YORK).time() == time(0, 0, 0):
        return None
    return acceptance


def end_of_filing_day(filed: date) -> datetime:
    return datetime.combine(filed, time(23, 59, 59), tzinfo=NEW_YORK).astimezone(timezone.utc)


def inclusive_days(start: date, end: date) -> int:
    """Java duration(): ChronoUnit.DAYS.between(start, end) + 1."""
    return (end - start).days + 1


def filing_index(submission_pages: list[dict]) -> dict[str, Filing]:
    """Index 10-K/10-Q filings by accession across the recent block and older pages (same column layout)."""
    index: dict[str, Filing] = {}
    for page in submission_pages:
        block = page.get("filings", {}).get("recent", page) if "filings" in page else page
        accessions = block.get("accessionNumber")
        if not isinstance(accessions, list):
            continue
        columns = [block.get(name, []) for name in ("form", "filingDate", "reportDate", "acceptanceDateTime")]
        for position, accession in enumerate(accessions):
            form = _at(columns[0], position)
            filed = parse_date(_at(columns[1], position))
            if form not in ELIGIBLE_FORMS or filed is None or not isinstance(accession, str):
                continue
            index[accession] = Filing(accession, form, filed, parse_date(_at(columns[2], position)),
                                      acceptance_value(_at(columns[3], position)))
    return index


def _at(column, position):
    return column[position] if isinstance(column, list) and position < len(column) else None


def availability(filings: dict[str, Filing], accession: str, filed: date) -> tuple[datetime, str]:
    filing = filings.get(accession)
    if filing is not None and filing.acceptance is not None:
        return filing.acceptance, ACCEPTANCE_SOURCE
    return end_of_filing_day(filed), FALLBACK_SOURCE


def collect_rows(facts: dict, tags: tuple[str, ...]) -> list[Row]:
    """USD rows for an alias group. A version key (accession, period, form, filed) seen under an earlier alias is
    dropped, so the first alias wins across aliases only. Duplicates inside one tag are kept, and resolve_periods
    treats conflicting ones as no value (Java factRows coveredPeriods and uniqueFact)."""
    namespace = facts.get("facts", {}).get("us-gaap", {})
    if not isinstance(namespace, dict):
        raise FundamentalsError("companyfacts has no us-gaap namespace")
    rows: list[Row] = []
    covered: set[tuple] = set()
    for tag in tags:
        units = namespace.get(tag, {}).get("units", {}).get("USD", [])
        if not isinstance(units, list):
            continue
        current: list[Row] = []
        for item in units:
            form = item.get("form")
            end = parse_date(item.get("end"))
            filed = parse_date(item.get("filed"))
            accession = item.get("accn")
            value = _decimal(item.get("val"))
            if form not in ELIGIBLE_FORMS or end is None or filed is None or not accession or value is None:
                continue
            start = parse_date(item.get("start"))
            key = (start, end, accession, filed, form)
            if key in covered:
                continue
            current.append(Row(tag, start, end, value, accession, form, filed, _int(item.get("fy")),
                               item.get("fp") or None))
        covered.update((row.start, row.end, row.accession, row.filed, row.form) for row in current)
        rows.extend(current)
    return rows


def _decimal(value) -> Decimal | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None


def _int(value) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def derive_quarters(rows: list[Row], group: str, filings: dict[str, Filing]) -> list[Quarter]:
    """Standalone quarters per concept. YTD and annual differences only subtract values of the same concept:
    mixing aliases produced PG FY2012 Q4 revenue of -35.07bn (FY 'Revenues' 28.4bn minus 9M 'SalesRevenue' 63.5bn)."""
    by_concept: dict[str, list[Row]] = defaultdict(list)
    for row in rows:
        by_concept[row.concept].append(row)
    quarters: list[Quarter] = []
    for concept_rows in by_concept.values():
        quarters.extend(_derive_one_concept(concept_rows, group, filings))
    return quarters


def _derive_one_concept(rows: list[Row], group: str, filings: dict[str, Filing]) -> list[Quarter]:
    """Direct 3-month 10-Q facts, YTD differences, and Q4 = FY minus 9M (Java 947-1001).

    Differences use only values from the filing whose own period ends on the value's period end (reportDate).
    A later comparative is a restated vintage, and subtracting it from an original value mixes vintages
    (ABT 2011 Q4: a 2014 comparative of FY2011 minus the original 9M gave -7.07bn). Direct 3-month values keep
    their comparatives, because those are the restatement evidence the audit measures."""

    def own(row: Row) -> bool:
        filing = filings.get(row.accession)
        return filing is not None and filing.report_date == row.end

    candidates: list[tuple[date, date, Decimal, str, str, str, date, str, Row]] = []
    for row in rows:
        if row.start is None:
            continue
        days = inclusive_days(row.start, row.end)
        if QUARTER_DAYS[0] <= days <= QUARTER_DAYS[1] and row.form.startswith("10-Q"):
            candidates.append((row.start, row.end, row.value, row.concept, "DIRECT_3M", row.accession,
                               row.filed, row.form, row))
        if YTD_DAYS[0] <= days <= YTD_DAYS[1] and row.form.startswith("10-Q") and own(row):
            prior = _latest_prior(rows, row, days, lambda p, d: p.start == row.start and p.end < row.end
                                  and p.form.startswith("10-Q") and 1 <= inclusive_days(p.start, p.end) < d
                                  and QUARTER_DAYS[0] <= (row.end - p.end).days <= QUARTER_DAYS[1]
                                  and p.filed <= row.filed and own(p))
            if prior is not None:
                start = prior.end + timedelta(days=1)
                if QUARTER_DAYS[0] <= inclusive_days(start, row.end) <= QUARTER_DAYS[1]:
                    candidates.append((start, row.end, row.value - prior.value, row.concept, "YTD_DIFF",
                                       row.accession, row.filed, row.form, row))
        if ANNUAL_DAYS[0] <= days <= ANNUAL_DAYS[1] and row.form.startswith("10-K") and own(row):
            prior = _latest_prior(rows, row, None, lambda p, d: p.start == row.start and p.form.startswith("10-Q")
                                  and NINE_MONTH_DAYS[0] <= inclusive_days(p.start, p.end) <= NINE_MONTH_DAYS[1]
                                  and QUARTER_DAYS[0] <= (row.end - p.end).days <= QUARTER_DAYS[1]
                                  and p.filed <= row.filed and own(p))
            if prior is not None:
                start = prior.end + timedelta(days=1)
                if QUARTER_DAYS[0] <= inclusive_days(start, row.end) <= QUARTER_DAYS[1]:
                    candidates.append((start, row.end, row.value - prior.value, row.concept, "FY_MINUS_9M",
                                       row.accession, row.filed, row.form, row))
    quarters = []
    for start, end, value, concept, method, accession, filed, form, source_row in candidates:
        available_at, source = availability(filings, accession, filed)
        quarters.append(Quarter(group, start, end, value, concept, method, accession, form, filed,
                                available_at, source, source_row.fy, source_row.fp))
    return quarters


def _latest_prior(rows: list[Row], row: Row, days: int | None, predicate) -> Row | None:
    """Java takes the prior with max end (YTD) or max filed (Q4). Ambiguous ties yield no prior."""
    matches = [p for p in rows if p is not row and p.start is not None and predicate(p, days or 0)]
    if not matches:
        return None
    best = max(matches, key=lambda p: (p.end, p.filed)) if days is not None else max(matches, key=lambda p: p.filed)
    if days is not None:
        tied = [p for p in matches if (p.end, p.filed) == (best.end, best.filed)]
    else:
        tied = [p for p in matches if p.filed == best.filed]
    if any(p.value != best.value for p in tied):
        return None
    return best


@dataclass(frozen=True)
class Resolved:
    first: Quarter | None
    latest: Quarter | None
    ambiguous: bool
    versions: int


def resolve_periods(quarters: list[Quarter]) -> dict[tuple[date, date], Resolved]:
    """Per (start, end): first print (earliest availability) and latest print (latest filed). Java uniqueFact rule."""
    grouped: dict[tuple[date, date], list[Quarter]] = {}
    for quarter in quarters:
        grouped.setdefault((quarter.start, quarter.end), []).append(quarter)
    resolved: dict[tuple[date, date], Resolved] = {}
    for key, versions in grouped.items():
        ordered = sorted(versions, key=lambda q: (q.available_at, q.accession))
        first = _unique(ordered[0], [q for q in ordered if q.available_at == ordered[0].available_at])
        latest_filed = max(q.filed for q in versions)
        latest_rows = [q for q in versions if q.filed == latest_filed]
        latest = _unique(latest_rows[0], latest_rows)
        resolved[key] = Resolved(first, latest, first is None or latest is None, len(versions))
    return resolved


def _unique(head: Quarter, group: list[Quarter]) -> Quarter | None:
    if all(q.value == head.value and q.accession == head.accession and q.filed == head.filed for q in group):
        return head
    return None


def ttm_components(end: date, by_end: dict[date, list[tuple[tuple[date, date], Resolved]]],
                   use: str) -> list[Quarter] | None:
    """Four contiguous quarters ending at ``end``. Each start must be the day after the previous quarter ends."""
    chain: list[Quarter] = []
    current = end
    for _ in range(4):
        options = by_end.get(current, [])
        if len(options) != 1:
            return None
        (_, resolved) = options[0]
        quarter = resolved.first if use == "first" else resolved.latest
        if quarter is None:
            return None
        chain.append(quarter)
        current = quarter.start - timedelta(days=1)
    return chain


def build_symbol_panel(symbol: str, cik: str, facts: dict, submission_pages: list[dict]) -> list[dict]:
    """One row per period end where a TTM revenue, COGS or GrossProfit value can be formed."""
    filings = filing_index(submission_pages)
    raw_rows = {group: collect_rows(facts, tags) for group, tags in FLOW_GROUPS.items()}
    resolved = {group: resolve_periods(derive_quarters(rows, group, filings)) for group, rows in raw_rows.items()}
    by_end = {group: _index_by_end(resolved_group) for group, resolved_group in resolved.items()}
    assets = _assets(facts, filings)
    ends = sorted({end for group in resolved.values() for (_, end) in group})
    panel = []
    for end in ends:
        row = _panel_row(symbol, cik, end, by_end, filings, assets)
        panel.append(row)
    return panel


def _index_by_end(resolved_group: dict[tuple[date, date], Resolved]):
    index: dict[date, list] = {}
    for key, resolved in resolved_group.items():
        index.setdefault(key[1], []).append((key, resolved))
    return index


def _assets(facts: dict, filings: dict[str, Filing]) -> dict[date, dict]:
    """Total assets per balance-sheet date: first print (earliest availability) and latest print (latest filed)."""
    rows = []
    namespace = facts.get("facts", {}).get("us-gaap", {})
    for item in namespace.get(ASSETS_TAG, {}).get("units", {}).get("USD", []):
        end = parse_date(item.get("end"))
        filed = parse_date(item.get("filed"))
        value = _decimal(item.get("val"))
        if item.get("form") in ELIGIBLE_FORMS and end and filed and value is not None and item.get("accn"):
            rows.append({"end": end, "value": value, "accession": item["accn"], "filed": filed,
                         "form": item["form"], "fy": _int(item.get("fy")), "fp": item.get("fp") or None})
    result: dict[date, dict] = {}
    for end in {row["end"] for row in rows}:
        versions = [row for row in rows if row["end"] == end]
        ordered = sorted(versions, key=lambda r: (availability(filings, r["accession"], r["filed"])[0], r["accession"]))
        head = ordered[0]
        head_at, head_source = availability(filings, head["accession"], head["filed"])
        same_time = [r for r in ordered if availability(filings, r["accession"], r["filed"])[0] == head_at]
        first_value = head["value"] if all(r["value"] == head["value"] for r in same_time) else None
        latest_filed = max(r["filed"] for r in versions)
        latest_rows = [r for r in versions if r["filed"] == latest_filed]
        latest_value = (latest_rows[0]["value"]
                        if all(r["value"] == latest_rows[0]["value"] for r in latest_rows) else None)
        result[end] = {"first": first_value, "first_at": head_at, "first_source": head_source,
                       "accession": head["accession"], "form": head["form"], "filed": head["filed"],
                       "fiscal_year": head["fy"], "fiscal_period": head["fp"], "latest": latest_value,
                       "latest_accession": latest_rows[0]["accession"], "latest_filed": latest_filed}
    return result


def _iso(moment: datetime) -> str:
    return moment.isoformat().replace("+00:00", "Z")


def _component(quarter: Quarter, latest: Quarter | None) -> dict:
    data = {"group": quarter.group, "start": quarter.start.isoformat(), "end": quarter.end.isoformat(),
            "method": quarter.method, "concept": quarter.concept, "value": str(quarter.value),
            "accession": quarter.accession, "form": quarter.form, "filed": quarter.filed.isoformat(),
            "available_at": _iso(quarter.available_at), "availability_source": quarter.availability_source,
            "fiscal_year": quarter.fiscal_year, "fiscal_period": quarter.fiscal_period,
            "latest_value": None, "latest_accession": None, "latest_filed": None}
    if latest is not None:
        data.update({"latest_value": str(latest.value), "latest_accession": latest.accession,
                     "latest_filed": latest.filed.isoformat()})
    return data


def _panel_row(symbol, cik, end, by_end, filings, assets) -> dict:
    row: dict = {"symbol": symbol, "cik": cik, "period_end": end.isoformat(), "status": "OK",
                 "revenue_ttm_first": None, "revenue_ttm_latest": None, "revenue_concepts": None,
                 "cogs_ttm_first": None, "cogs_ttm_latest": None, "cogs_concepts": None,
                 "gross_profit_ttm_first": None, "gross_profit_ttm_latest": None, "gross_profit_method": None,
                 "assets_first": None, "assets_latest": None, "assets_concept": None,
                 "gp_a_first": None, "gp_a_latest": None, "quarter_method": None,
                 "available_at": None, "availability_source": None, "fallback_components": 0,
                 "amended_components": [], "components": []}
    chains = {group: (ttm_components(end, by_end[group], "first"), ttm_components(end, by_end[group], "latest"))
              for group in ("revenue", "cogs", "gross_profit")}
    rev_first, rev_latest = chains["revenue"]
    if rev_first is None:
        row["status"] = "NO_REVENUE_TTM"
        return row
    revenue_first = sum(q.value for q in rev_first)
    row["revenue_ttm_first"] = str(revenue_first)
    row["revenue_concepts"] = [q.concept for q in rev_first]
    row["quarter_method"] = rev_first[0].method
    if rev_latest:
        row["revenue_ttm_latest"] = str(sum(q.value for q in rev_latest))
    components = [_component(q, rev_latest[i] if rev_latest else None) for i, q in enumerate(rev_first)]
    used = list(rev_first)
    cogs_first, cogs_latest = chains["cogs"]
    gp_first, gp_latest = chains["gross_profit"]
    if cogs_first is not None:
        cogs_value = sum(q.value for q in cogs_first)
        gross = revenue_first - cogs_value
        cogs_latest_value = sum(q.value for q in cogs_latest) if cogs_latest else None
        gross_latest = (sum(q.value for q in rev_latest) - cogs_latest_value
                        if rev_latest and cogs_latest_value is not None else None)
        method = "REVENUE_MINUS_COGS"
        row["cogs_ttm_first"] = str(cogs_value)
        row["cogs_concepts"] = [q.concept for q in cogs_first]
        if cogs_latest_value is not None:
            row["cogs_ttm_latest"] = str(cogs_latest_value)
        components += [_component(q, cogs_latest[i] if cogs_latest else None) for i, q in enumerate(cogs_first)]
        used += cogs_first
    elif gp_first is not None:
        gross = sum(q.value for q in gp_first)
        gross_latest = sum(q.value for q in gp_latest) if gp_latest else None
        method = "GROSS_PROFIT_TAG"
        row["cogs_concepts"] = [GROSS_PROFIT_TAG]
        components += [_component(q, gp_latest[i] if gp_latest else None) for i, q in enumerate(gp_first)]
        used += gp_first
    else:
        row["status"] = "NO_COGS_OR_GROSS_PROFIT_TTM"
        return row
    row["gross_profit_method"] = method
    row["gross_profit_ttm_first"] = str(gross)
    if gross_latest is not None:
        row["gross_profit_ttm_latest"] = str(gross_latest)
    asset = assets.get(end)
    if asset is None or asset["first"] is None:
        row["status"] = "NO_ASSETS"
        return row
    row["assets_first"] = str(asset["first"])
    row["assets_concept"] = ASSETS_TAG
    if asset["latest"] is not None:
        row["assets_latest"] = str(asset["latest"])
    available = max([q.available_at for q in used] + [asset["first_at"]])
    fallback_count = sum(1 for q in used if q.availability_source == FALLBACK_SOURCE)
    fallback_count += 1 if asset["first_source"] == FALLBACK_SOURCE else 0
    row["available_at"] = _iso(available)
    row["availability_source"] = FALLBACK_SOURCE if fallback_count else ACCEPTANCE_SOURCE
    row["fallback_components"] = fallback_count
    if asset["first"] > 0:
        row["gp_a_first"] = float(gross / asset["first"])
    if gross_latest is not None and asset["latest"]:
        row["gp_a_latest"] = float(gross_latest / asset["latest"])
    amended = []
    for name, first_chain, latest_chain in (("revenue", rev_first, rev_latest), ("cogs", cogs_first, cogs_latest),
                                            ("gross_profit", gp_first, gp_latest)):
        if first_chain and latest_chain and [q.value for q in first_chain] != [q.value for q in latest_chain]:
            amended.append(name)
    if asset["latest"] is not None and asset["latest"] != asset["first"]:
        amended.append("assets")
    row["amended_components"] = amended
    components.append({"group": "assets", "end": end.isoformat(), "method": "INSTANT", "concept": ASSETS_TAG,
                       "value": str(asset["first"]), "accession": asset["accession"], "form": asset["form"],
                       "filed": asset["filed"].isoformat(), "available_at": _iso(asset["first_at"]),
                       "availability_source": asset["first_source"], "fiscal_year": asset["fiscal_year"],
                       "fiscal_period": asset["fiscal_period"],
                       "latest_value": None if asset["latest"] is None else str(asset["latest"]),
                       "latest_accession": asset["latest_accession"],
                       "latest_filed": asset["latest_filed"].isoformat()})
    row["components"] = components
    return row


def load_symbol_inputs(sec_dir: Path, manifest: dict) -> list[dict]:
    """Yields one input per manifest entry with status OK (companyfacts present, submissions pages recorded)."""
    inputs = []
    for label, entry in sorted(manifest.get("symbols", {}).items()):
        if entry.get("status") != "OK":
            continue
        facts_path = next((path for path in entry["files"] if "/companyfacts/" in path), None)
        sub_paths = [path for path in entry["files"] if "/submissions/" in path]
        if facts_path is None or not sub_paths:
            raise FundamentalsError(f"{label}: companyfacts or submissions missing from manifest")
        facts = json.loads((sec_dir / facts_path).read_text(encoding="utf-8"))
        pages = [json.loads((sec_dir / path).read_text(encoding="utf-8")) for path in sub_paths]
        inputs.append({"label": label, "cik": entry["cik"], "facts": facts, "submission_pages": pages,
                       "sic": entry.get("sic"), "sic_description": entry.get("sic_description"),
                       "name": entry.get("name")})
    return inputs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build the point-in-time GP/A panel from collected SEC data.")
    parser.add_argument("--sec", default="../research-data/sec", help="directory written by the SEC collector")
    parser.add_argument("--out", default="../research-data/" + PANEL_NAME)
    args = parser.parse_args(argv)
    sec_dir = Path(args.sec)
    manifest = json.loads((sec_dir / "manifest.json").read_text(encoding="utf-8"))
    panel = []
    for item in load_symbol_inputs(sec_dir, manifest):
        panel.extend(build_symbol_panel(item["label"], item["cik"], item["facts"], item["submission_pages"]))
    out = Path(args.out)
    out.write_text(json.dumps({"rows": panel}, sort_keys=True, indent=1), encoding="utf-8")
    print(f"panel rows={len(panel)} -> {out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
