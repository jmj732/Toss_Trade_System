"""Data-quality audit of the SEC EDGAR fundamentals used for the Q-1 GP/A hypothesis.

Stdlib only; no network I/O. Reads the raw directory written by scripts/collect-sec-companyfacts.py and writes
research-data/sec-audit.json and research-data/sec-audit.md (Korean). The audit only describes data. It computes
no returns and no strategy result.

Sections: a) publication timing, b) amendments and restatements, c) industry comparability and tag switches,
d) survivorship, e) per-symbol coverage.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from app.research import fundamentals as fd

NEW_YORK = ZoneInfo("America/New_York")
AS_OF = date(2026, 11, 24)
WINDOW_START = date(2009, 6, 30)  # first XBRL-era quarter end (phase-in began for periods after 2009-06-15)
WINDOW_END = date(2026, 9, 30)  # last quarter end whose 10-Q deadline (end + 45 days) has passed as of AS_OF
JSON_NAME = "sec-audit.json"
MD_NAME = "sec-audit.md"
DELISTED_TESTS = {"LEH": "Lehman Brothers Holdings (806085)", "SHLD": "Sears Holdings (1310067)",
                  "TWTR": "Twitter (1418091)"}
XOM_PAIR = ("XOM", "XOM_PRE")
GROUPS = ("revenue", "cogs", "gross_profit")


def calendar_quarter_ends(start: date, end: date) -> list[date]:
    ends = []
    year, quarter = start.year, (start.month - 1) // 3
    while True:
        month = (quarter + 1) * 3
        next_month_first = date(year + (month == 12), month % 12 + 1, 1)
        last = next_month_first - timedelta(days=1)
        if last > end:
            break
        if last >= start:
            ends.append(last)
        quarter += 1
        if quarter == 4:
            year, quarter = year + 1, 0
    return ends


CALENDAR_QUARTERS = calendar_quarter_ends(WINDOW_START, WINDOW_END)


def percentile(values: list[float], q: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def summary(values: list[float]) -> dict:
    if not values:
        return {"n": 0}
    return {"n": len(values), "min": round(min(values), 3), "p10": round(percentile(values, 0.10), 3),
            "median": round(percentile(values, 0.5), 3), "p90": round(percentile(values, 0.90), 3),
            "max": round(max(values), 3)}


def to_et(acceptance: datetime) -> datetime:
    return acceptance.astimezone(NEW_YORK)


def relative_change(first: Decimal, latest: Decimal) -> float | None:
    if first == 0:
        return None
    return float((latest - first) / abs(first))


def load_inputs(sec_dir: Path) -> tuple[dict, list[dict]]:
    manifest = json.loads((sec_dir / "manifest.json").read_text(encoding="utf-8"))
    return manifest, fd.load_symbol_inputs(sec_dir, manifest)


def facts_rows_for_timing(facts: dict, filings: dict) -> list[dict]:
    """USD values of the GP inputs (revenue, COGS, GrossProfit, Assets) with their filing timing. Each row carries
    ``own_period``: True when the period end equals the filing's reportDate. Prior-year comparatives are False."""
    rows = []
    for group, tags in list(fd.FLOW_GROUPS.items()) + [("assets", (fd.ASSETS_TAG,))]:
        for tag in tags:
            units = facts.get("facts", {}).get("us-gaap", {}).get(tag, {}).get("units", {}).get("USD", [])
            for item in units:
                end = fd.parse_date(item.get("end"))
                filed = fd.parse_date(item.get("filed"))
                accession = item.get("accn")
                if end is None or filed is None or not accession or item.get("form") not in fd.ELIGIBLE_FORMS:
                    continue
                filing = filings.get(accession)
                acceptance = filing.acceptance if filing else None
                own = filing is not None and filing.report_date == end
                rows.append({"group": group, "form": item["form"], "end": end, "filed": filed,
                             "accession": accession, "acceptance": acceptance, "fallback": acceptance is None,
                             "own_period": own})
    return rows


def section_a(inputs: list[dict]) -> dict:
    by_form: dict[str, list[float]] = defaultdict(list)
    after_close = Counter()
    fallback = Counter()
    total = Counter()
    filed_before_end = []
    hour_checks = Counter()
    early_anomaly = Counter()
    for item in inputs:
        filings = fd.filing_index(item["submission_pages"])
        for row in facts_rows_for_timing(item["facts"], filings):
            if not (date(2009, 1, 1) <= row["end"] <= date(2026, 12, 31)):
                continue
            total["facts"] += 1
            if row["filed"] < row["end"]:
                filed_before_end.append({"symbol": item["label"], "accession": row["accession"],
                                         "end": row["end"].isoformat(), "filed": row["filed"].isoformat()})
            if row["fallback"]:
                fallback["facts"] += 1
                continue
            total["accepted"] += 1
            et = to_et(row["acceptance"])
            if (et.hour, et.minute) >= (16, 0):
                after_close["all"] += 1
            if not row["own_period"]:
                continue
            days = (row["acceptance"] - datetime.combine(row["end"], time(0), tzinfo=timezone.utc)).total_seconds()
            by_form[row["form"]].append(days / 86400)
            total["own"] += 1
            if (et.hour, et.minute) >= (16, 0):
                after_close[row["form"]] += 1
        for filing in filings.values():
            if filing.acceptance is None or filing.acceptance.year < 2009:
                continue
            et = to_et(filing.acceptance)
            if (et.hour, et.minute) >= (17, 30):
                hour_checks["after_1730_et"] += 1
                if et.date() != filing.filed:
                    hour_checks["after_1730_et_next_day_filed"] += 1
        for filing in filings.values():
            if filing.acceptance is not None and filing.acceptance.year < 2003:
                early_anomaly[filing.acceptance.hour] += 1
    forms = {}
    for form, values in sorted(by_form.items()):
        forms[form] = {"days_after_period_end": summary(values),
                       "after_1600_et_share": round(after_close[form] / len(values), 4)}
    accepted_total = total["accepted"]
    return {
        "fact_versions_2009_2026": total["facts"],
        "accepted_versions": accepted_total,
        "own_period_versions": total["own"],
        "by_form_own_period": forms,
        "after_1600_et_share_all_versions": round(after_close["all"] / accepted_total, 4) if accepted_total else None,
        "fallback_versions": fallback["facts"],
        "fallback_share": round(fallback["facts"] / total["facts"], 4) if total["facts"] else None,
        "filed_before_period_end_errors": len(filed_before_end),
        "filed_before_period_end_examples": filed_before_end[:10],
        "utc_validation": {
            "filings_accepted_after_1730_et": hour_checks["after_1730_et"],
            "share_with_next_day_filing_date": round(
                hour_checks["after_1730_et_next_day_filed"] / hour_checks["after_1730_et"], 4)
            if hour_checks["after_1730_et"] else None,
        },
        "pre_2003_acceptance_utc_hours": dict(sorted(early_anomaly.items())),
    }


def versions_by_key(inputs: list[dict]) -> dict[tuple, list[dict]]:
    """Versions per (symbol, group, start, end) from every filing that reports the value."""
    grouped: dict[tuple, list[dict]] = defaultdict(list)
    for item in inputs:
        filings = fd.filing_index(item["submission_pages"])
        for group, tags in fd.FLOW_GROUPS.items():
            rows = fd.collect_rows(item["facts"], tags)
            for quarter in fd.derive_quarters(rows, group, filings):
                grouped[(item["label"], group, quarter.start, quarter.end)].append(
                    {"value": quarter.value, "accession": quarter.accession, "form": quarter.form,
                     "filed": quarter.filed, "available_at": quarter.available_at, "concept": quarter.concept})
        assets = item["facts"].get("facts", {}).get("us-gaap", {}).get(fd.ASSETS_TAG, {}).get("units", {}).get("USD", [])
        for entry in assets:
            end = fd.parse_date(entry.get("end"))
            filed = fd.parse_date(entry.get("filed"))
            value = fd._decimal(entry.get("val"))
            if end and filed and value is not None and entry.get("form") in fd.ELIGIBLE_FORMS and entry.get("accn"):
                at, _ = fd.availability(filings, entry["accn"], filed)
                grouped[(item["label"], "assets", None, end)].append(
                    {"value": value, "accession": entry["accn"], "form": entry["form"], "filed": filed,
                     "available_at": at, "concept": fd.ASSETS_TAG})
    return grouped


def section_b(inputs: list[dict], groups: dict[tuple, list[dict]]) -> dict:
    amendments = {}
    for item in inputs:
        filings = fd.filing_index(item["submission_pages"])
        counts = Counter(f.form for f in filings.values())
        amendments[item["label"]] = {"10-K/A": counts.get("10-K/A", 0), "10-Q/A": counts.get("10-Q/A", 0),
                                     "10-K": counts.get("10-K", 0), "10-Q": counts.get("10-Q", 0)}
    multi = differing = same_filing_conflicts = 0
    later_form = Counter()
    rel_changes: list[float] = []
    cases = []
    conflict_cases = []
    for key, versions in groups.items():
        values_by_filing: dict[str, set] = defaultdict(set)
        for v in versions:
            values_by_filing[v["accession"]].add(v["value"])
        conflicting = {acc for acc, vals in values_by_filing.items() if len(vals) > 1}
        if conflicting:
            # Same filing reports two values for one period (e.g. a YTD difference disagrees with the direct 3-month
            # print). fundamentals.resolve_periods treats this as no value, so it is excluded from version comparisons.
            same_filing_conflicts += 1
            if len(conflict_cases) < 10:
                conflict_cases.append({"symbol": key[0], "group": key[1], "period": f"{key[2] or ''}..{key[3]}",
                                       "filings": sorted(conflicting)})
        clean = [v for v in versions if v["accession"] not in conflicting]
        if len({v["accession"] for v in clean}) < 2:
            continue
        multi += 1
        ordered = sorted(clean, key=lambda v: (v["available_at"], v["accession"]))
        first = ordered[0]
        latest = max(clean, key=lambda v: (v["filed"], v["accession"]))
        if latest["value"] == first["value"]:
            continue
        differing += 1
        klass = "AMENDMENT" if latest["form"].endswith("/A") else "ORIGINAL_COMPARATIVE"
        later_form[klass] += 1
        rel = relative_change(first["value"], latest["value"])
        if rel is not None:
            rel_changes.append(abs(rel))
        cases.append((abs(rel) if rel is not None else float("inf"), key, first, latest, rel))
    cases.sort(key=lambda c: -c[0])
    top = []
    for _, key, first, latest, rel in cases[:10]:
        top.append({"symbol": key[0], "group": key[1], "period": f"{key[2] or ''}..{key[3]}",
                    "first": {"value": str(first["value"]), "form": first["form"], "filed": first["filed"].isoformat()},
                    "latest": {"value": str(latest["value"]), "form": latest["form"],
                               "filed": latest["filed"].isoformat()},
                    "relative_change": None if rel is None else round(rel, 4)})
    return {
        "amendment_counts_per_symbol": amendments,
        "periods_total": len(groups),
        "periods_with_same_filing_conflict": same_filing_conflicts,
        "same_filing_conflict_examples": conflict_cases,
        "periods_with_multiple_versions": multi,
        "periods_where_latest_differs_from_first": differing,
        "share_of_multi_version_periods_differing": round(differing / multi, 4) if multi else None,
        "latest_differs_by_later_form": dict(later_form),
        "relative_change_abs_distribution": summary(rel_changes),
        "largest_cases": top,
    }


def cogs_group_rows(panel_rows: list[dict], symbol: str) -> list[dict]:
    return sorted((r for r in panel_rows if r["symbol"] == symbol), key=lambda r: r["period_end"])


def coverage_of(rows: list[dict]) -> dict:
    """Calendar quarters (70 in the window) matched by the nearest computable fiscal quarter end within 45 days.
    Fiscal quarters of 52/53-week and non-December years fall a few weeks away from calendar quarter ends."""
    matched: set[date] = set()
    for row in rows:
        if row["status"] != "OK" or row["gp_a_first"] is None:
            continue
        period = date.fromisoformat(row["period_end"])
        if not (WINDOW_START <= period <= WINDOW_END):
            continue
        nearest = min(CALENDAR_QUARTERS, key=lambda calendar: abs((period - calendar).days))
        if abs((period - nearest).days) <= 45:
            matched.add(nearest)
    ok = [r for r in rows if r["status"] == "OK" and r["gp_a_first"] is not None
          and WINDOW_START <= date.fromisoformat(r["period_end"]) <= WINDOW_END]
    return {"covered_calendar_quarters": len(matched), "calendar_quarters": len(CALENDAR_QUARTERS),
            "coverage": round(len(matched) / len(CALENDAR_QUARTERS), 4),
            "first_computable_period_end": min((r["period_end"] for r in ok), default=None),
            "last_computable_period_end": max((r["period_end"] for r in ok), default=None)}


def tag_switches(rows: list[dict]) -> dict:
    series = [r for r in rows if r["status"] == "OK" and WINDOW_START <= date.fromisoformat(r["period_end"]) <= WINDOW_END]
    switches = jumps_with_switch = jumps_without_switch = 0
    examples = []
    for previous, current in zip(series, series[1:]):
        gap = (date.fromisoformat(current["period_end"]) - date.fromisoformat(previous["period_end"])).days
        if gap > 100 or not previous["revenue_ttm_first"] or not current["revenue_ttm_first"]:
            continue
        switch = previous["revenue_concepts"][0] != current["revenue_concepts"][0]
        ratio = float(Decimal(current["revenue_ttm_first"]) / Decimal(previous["revenue_ttm_first"]))
        jump = abs(ratio - 1) > 0.2
        switches += int(switch)
        if jump and switch:
            jumps_with_switch += 1
            if len(examples) < 5:
                examples.append({"period_end": current["period_end"], "from": previous["revenue_concepts"][0],
                                 "to": current["revenue_concepts"][0], "ratio": round(ratio, 3)})
        elif jump:
            jumps_without_switch += 1
    return {"revenue_tag_switches": switches, "jumps_over_20pct_at_switch": jumps_with_switch,
            "jumps_over_20pct_without_switch": jumps_without_switch, "examples": examples}


def concept_counts(rows: list[dict], field: str) -> dict:
    counts = Counter()
    for r in rows:
        if r.get(field) and WINDOW_START <= date.fromisoformat(r["period_end"]) <= WINDOW_END:
            counts[r[field][0]] += 1
    return dict(counts.most_common())


def section_c(inputs: list[dict], panel: list[dict]) -> dict:
    per_symbol = {}
    flags = []
    for item in inputs:
        rows = cogs_group_rows(panel, item["label"])
        sic = item.get("sic")
        financial = isinstance(sic, str) and sic.isdigit() and 6000 <= int(sic) <= 6999
        cov = coverage_of(rows)
        switches = tag_switches(rows)
        revenue_tags = concept_counts(rows, "revenue_concepts")
        cogs_tags = concept_counts(rows, "cogs_concepts")
        status_counts = Counter(r["status"] for r in rows if WINDOW_START <= date.fromisoformat(r["period_end"]) <= WINDOW_END)
        note = []
        if financial:
            note.append("금융업(SIC 6000-6999): 매출원가 개념이 정의되지 않아 GP/A 비교 불가")
        if not cogs_tags and not financial:
            note.append("매출원가/GrossProfit 태그 없음(프록시 미사용)")
        if status_counts.get("NO_COGS_OR_GROSS_PROFIT_TTM", 0) and not financial:
            note.append("일부 분기 매출원가 TTM 없음")
        per_symbol[item["label"]] = {"cik": item["cik"], "name": item.get("name"), "sic": sic,
                                     "sic_description": item.get("sic_description"), "financial_sic": financial,
                                     "coverage": cov, "revenue_concepts_first_print": revenue_tags,
                                     "cogs_concepts_first_print": cogs_tags, "tag_switches": switches,
                                     "status_counts_2009_2026": dict(status_counts), "notes": note}
        if financial or note:
            flags.append({"symbol": item["label"], "notes": note})
    return {"per_symbol": per_symbol, "flags": flags}


def section_d(inputs: list[dict], manifest: dict, panel: list[dict]) -> dict:
    labels = [i["label"] for i in inputs]
    sources = Counter(entry.get("cik_source") for entry in manifest["symbols"].values())
    ticker_selected = sum(1 for entry in manifest["symbols"].values() if entry.get("cik_source") == "company_tickers")
    delisted = {}
    for label, description in DELISTED_TESTS.items():
        entry = manifest["symbols"].get(label, {})
        rows = cogs_group_rows(panel, label)
        ok = [r for r in rows if r["status"] == "OK" and r["gp_a_first"] is not None]
        delisted[label] = {"description": description, "status": entry.get("status"), "name": entry.get("name"),
                           "companyfacts_present": any("/companyfacts/" in p for p in entry.get("files", {})),
                           "computable_gp_a_quarters": len(ok),
                           "last_computable_period_end": max((r["period_end"] for r in ok), default=None)}
    return {
        "universe_symbols": len(manifest["symbols"]),
        "cik_source_counts": dict(sources),
        "universe_selected_from_current_ticker_file": ticker_selected,
        "current_ticker_file_share": round(ticker_selected / len(manifest["symbols"]), 4),
        "delisted_tests": delisted,
        "conclusion": ("EDGAR supplies XBRL fundamentals for XBRL-era issuers that stopped filing "
                       "(delisted or acquired after 2009); pre-XBRL issuers (for example Lehman, 2008) have none. "
                       "Discovering delisted CIKs requires an EDGAR index outside the current allowlist. "
                       "Delisted prices still need another source."),
    }


def section_e(inputs: list[dict], panel: list[dict], manifest: dict) -> list[dict]:
    rows = []
    labels = [i["label"] for i in inputs]
    labels += [k for k in DELISTED_TESTS if k in manifest["symbols"] and k not in labels]
    for label in labels:
        panel_rows = cogs_group_rows(panel, label)
        cov = coverage_of(panel_rows)
        entry = manifest["symbols"].get(label, {})
        rows.append({"symbol": label, "cik": entry.get("cik"), "name": entry.get("name"), "sic": entry.get("sic"),
                     "status": entry.get("status"), **cov,
                     "panel_rows_2009_2026": sum(1 for r in panel_rows
                                                 if WINDOW_START <= date.fromisoformat(r["period_end"]) <= WINDOW_END)})
    return rows


def panel_for(inputs: list[dict]) -> list[dict]:
    panel = []
    for item in inputs:
        panel.extend(fd.build_symbol_panel(item["label"], item["cik"], item["facts"], item["submission_pages"]))
    return panel


def build_audit(sec_dir: Path) -> dict:
    manifest, inputs = load_inputs(sec_dir)
    panel = panel_for(inputs)
    groups = versions_by_key(inputs)
    amendments = section_b(inputs, groups)
    result = {
        "as_of": AS_OF.isoformat(),
        "window": {"start": WINDOW_START.isoformat(), "end": WINDOW_END.isoformat(),
                   "calendar_quarters": len(CALENDAR_QUARTERS)},
        "universe_size": len(manifest["symbols"]),
        "a_publication_timing": section_a(inputs),
        "b_amendments": amendments,
        "c_industry_comparability": section_c(inputs, panel),
        "d_survivorship": section_d(inputs, manifest, panel),
        "e_coverage": section_e(inputs, panel, manifest),
        "data_problems": data_problems(manifest, inputs, amendments),
    }
    return result


def data_problems(manifest: dict, inputs: list[dict], b: dict | None = None) -> list[str]:
    problems = []
    if b is not None:
        problems.append(f"같은 공시 안의 값 충돌 {b['periods_with_same_filing_conflict']}개 쌍: 6개월 누계 차분과 직접 3개월값이 "
                        "다름. GE 2024 Q2가 예 (2024 Q1 10-Q는 분사 전 기준, 2024 Q2 누계는 분사 후 기준). "
                        "계산에서 무효 처리하여 해당 분기가 빠짐.")
        for case in b["largest_cases"][:3]:
            problems.append(f"정정 사례 {case['symbol']} {case['group']} {case['period']}: 첫 공개 "
                            f"{case['first']['value']} ({case['first']['form']}, {case['first']['filed']}) → 최신 "
                            f"{case['latest']['value']} ({case['latest']['form']}, {case['latest']['filed']}). "
                            "원인은 수동 확인 전까지 미확정. 첫 공개값을 PIT 기준으로 쓰는 이유이기도 함.")
    if "XOM" in {i["label"] for i in inputs}:
        problems.append("XOM: ticker XOM maps to CIK 2115436 (ExxonMobil Holdings Corp, registered 2026-07). "
                        "2009-2026 history sits under CIK 34088 (EXXON MOBIL CORP, label XOM_PRE). Both are kept; "
                        "XOM has no COGS concept and is not computable.")
    problems.append("acceptanceDateTime carries a Z suffix and is UTC (AAPL 10-Q at 10:00-11:01Z = 06:0x ET). "
                    "Pre-2003 acceptances cluster at 04-05 UTC, which is not an intraday EDGAR time. Those rows are "
                    "outside the 2009-2026 window.")
    if manifest["symbols"].get("LEH", {}).get("status") == "NO_COMPANYFACTS":
        problems.append("LEH (806085): companyfacts returns 404 (no XBRL). Submissions index is present.")
    return problems


def render_markdown(audit: dict) -> str:
    a = audit["a_publication_timing"]
    b = audit["b_amendments"]
    c = audit["c_industry_comparability"]
    d = audit["d_survivorship"]
    e = audit["e_coverage"]
    lines = [
        "# SEC EDGAR 펀더멘털 데이터 감사 (Q-1 GP/A 입력)",
        "",
        f"- 기준일: {audit['as_of']} / 분석 창: {audit['window']['start']} ~ {audit['window']['end']} "
        f"({audit['window']['calendar_quarters']}개 분기)",
        f"- 유니버스: {audit['universe_size']}개 라벨 (LL-1 35종목 + XOM 선행 CIK 1개 + 지연 상장 시험 CIK 3개)",
        "- 이 감사는 데이터 품질만 다룹니다. 수익률 계산, 전략 백테스트, 파라미터 탐색은 포함하지 않습니다.",
        "",
        "## a. 공시 공개 시점",
        "",
        "**이유**: 재무값은 공시 시점에만 알 수 있으므로, 신호 사용 가능 시점(acceptanceDateTime)과 기간 말 사이의 "
        "간격, 장 마감 이후 공시 비율, 대체 타임스탬프 사용 비율을 확인해야 합니다.",
        "",
        "**결과**",
        "",
        f"- 팩트 버전(2009-2026): {a['fact_versions_2009_2026']}건, 접수시각 확인 {a['accepted_versions']}건, "
        f"대체(filed 기준) {a['fallback_versions']}건 ({_pct(a['fallback_share'])}).",
        f"- 16:00 ET 이후 접수 비율: 전체 버전 {_pct(a['after_1600_et_share_all_versions'])}, "
        "공시 자신의 기간 버전 기준은 아래 표 (16:00 ET 이후는 다음 영업일 사용 가능).",
        f"- 기간 말보다 filed가 앞선 오류: {a['filed_before_period_end_errors']}건.",
        "",
        "| 공시 양식 | 건수 | 기간 말 후 일수 중앙값 | p10 | p90 | 16:00 ET 이후 |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for form, info in a["by_form_own_period"].items():
        stats = info["days_after_period_end"]
        lines.append(f"| {form} | {stats['n']} | {stats.get('median')} | {stats.get('p10')} | {stats.get('p90')} | "
                     f"{_pct(info['after_1600_et_share'])} |")
    utc = a["utc_validation"]
    lines += [
        "",
        f"- UTC 해석 검증: 17:30 ET 이후 접수 {utc['filings_accepted_after_1730_et']}건 중 "
        f"{_pct(utc['share_with_next_day_filing_date'])}가 filingDate상 다음 날로 기록되어 Z=UTC 해석과 일치합니다.",
        f"- 2003년 이전 접수시각 UTC 시(hour) 분포: {a['pre_2003_acceptance_utc_hours']} (장중 접수로 볼 수 없음, 창 밖).",
        "",
        "## b. 정정공시와 재기술",
        "",
        "**이유**: GP/A의 첫 공개값과 최신값이 다르면 시점 일치 백테스트의 입력이 달라집니다. "
        "정정 공시(/A)와 후속 공시의 비교기간 재기술을 나누어 셉니다.",
        "",
        "**결과**",
        "",
        f"- 전체 (종목, 항목, 기간) 쌍: {b['periods_total']}개. 같은 공시 안에서 두 값이 충돌하는 쌍: "
        f"{b['periods_with_same_filing_conflict']}개 (계산에서 무효 처리, 예: 6개월 누계 차분과 직접 3개월값 불일치).",
        f"- 다중 버전 쌍: {b['periods_with_multiple_versions']}개.",
        f"- 최신값이 첫 공개값과 다른 쌍: {b['periods_where_latest_differs_from_first']}개 "
        f"({_pct(b['share_of_multi_version_periods_differing'])} of 다중 버전).",
        f"- 차이 발생 시 후속 공시 종류: {b['latest_differs_by_later_form']}.",
        f"- 상대 변화 |Δ|/|첫값| 분포: {b['relative_change_abs_distribution']}.",
        "",
        "| 종목 | 10-K/A | 10-Q/A | 10-K | 10-Q |",
        "|---|---:|---:|---:|---:|",
    ]
    for symbol, counts in b["amendment_counts_per_symbol"].items():
        lines.append(f"| {symbol} | {counts['10-K/A']} | {counts['10-Q/A']} | {counts['10-K']} | {counts['10-Q']} |")
    lines += ["", "최대 변화 사례 (상위 10):", "", "| 종목 | 항목 | 기간 | 첫 공개 | 최신 | 상대 변화 |", "|---|---|---|---|---|---:|"]
    for case in b["largest_cases"]:
        lines.append(f"| {case['symbol']} | {case['group']} | {case['period']} | "
                     f"{case['first']['value']} ({case['first']['form']}, {case['first']['filed']}) | "
                     f"{case['latest']['value']} ({case['latest']['form']}, {case['latest']['filed']}) | "
                     f"{case['relative_change']} |")
    lines += [
        "",
        "## c. 산업별 비교 가능성",
        "",
        "**이유**: 금융업은 매출원가가 정의되지 않고, 일부 기업은 2018년 ASC 606 전환 등으로 매출 태그가 바뀝니다. "
        "태그 전환 시점의 비율 점프를 확인해 기업 내부 불연속을 찾습니다. 프록시(예: CostsAndExpenses)는 쓰지 않습니다.",
        "",
        "**결과**",
        "",
        "| 종목 | SIC | 금융 | 커버리지 | 최초 계산 분기 | 최종 계산 분기 | 매출 태그 전환 | 전환+20%점프 | 비고 |",
        "|---|---|---|---:|---|---|---:|---:|---|",
    ]
    for symbol, info in c["per_symbol"].items():
        cov = info["coverage"]
        lines.append(f"| {symbol} | {info['sic']} | {'Y' if info['financial_sic'] else ''} | "
                     f"{_coverage_text(cov, info['financial_sic'])} | {cov['first_computable_period_end']} | "
                     f"{cov['last_computable_period_end']} | {info['tag_switches']['revenue_tag_switches']} | "
                     f"{info['tag_switches']['jumps_over_20pct_at_switch']} | {'; '.join(info['notes'])} |")
    lines += [
        "",
        "## d. 생존 편향",
        "",
        "**이유**: company_tickers.json은 현재 등록 종목만 담고 있어, 과거에 상장폐지된 종목이 자동으로 빠집니다.",
        "",
        "**결과**",
        "",
        f"- 라벨 {d['universe_symbols']}개 중 {d['universe_selected_from_current_ticker_file']}개가 현재 티커 파일에서 "
        f"선정됨 ({_pct(d['current_ticker_file_share'])}). 나머지는 명시 CIK(지연 상장 시험, XOM 선행 CIK). "
        "현재 등록 종목만 고른 선정 자체가 생존 편향을 가집니다.",
    ]
    for label, info in d["delisted_tests"].items():
        lines.append(f"- {label} ({info['description']}): 제출 상태 {info['status']}, 회사명 {info['name']}, "
                     f"companyfacts {'있음' if info['companyfacts_present'] else '없음'}, "
                     f"계산 가능 분기 {info['computable_gp_a_quarters']}개, 마지막 {info['last_computable_period_end']}.")
    lines += [f"- 결론: {d['conclusion']}", "",
              "## e. 종목별 커버리지", "",
              "| 라벨 | CIK | 회사명 | SIC | 상태 | 커버리지 | 계산 분기 수 | 최초 | 최종 |",
              "|---|---|---|---|---|---:|---:|---|---|"]
    for row in e:
        financial = isinstance(row["sic"], str) and row["sic"].isdigit() and 6000 <= int(row["sic"]) <= 6999
        lines.append(f"| {row['symbol']} | {row['cik']} | {row['name']} | {row['sic']} | {row['status']} | "
                     f"{_coverage_text(row, financial)} | {row['covered_calendar_quarters']} | "
                     f"{row['first_computable_period_end']} | {row['last_computable_period_end']} |")
    lines += ["", "## 방법론과 Java 대응", ""]
    lines += [f"- {item}" for item in METHODOLOGY]
    lines += ["", "## 데이터 문제", ""]
    lines += [f"- {problem}" for problem in audit["data_problems"]]
    lines.append("")
    return "\n".join(lines)


def _coverage_text(cov: dict, financial: bool) -> str:
    if financial:
        return "제외(금융업)"
    return _pct(cov["coverage"])


METHODOLOGY = [
    "매출: RevenueFromContractWithCustomerExcludingAssessedTax > Revenues > SalesRevenueNet "
    "(Java SecCompanyFactsProvider.java 1285-1287, 같은 우선순위).",
    "매출원가: CostOfRevenue > CostOfGoodsAndServicesSold > CostOfGoodsSold. Java에는 없는 새 매핑이며, "
    "MSFT(CostOfRevenue 총계)와 AAPL(CostOfGoodsAndServicesSold 총계)의 태그 사용으로 정함.",
    "매출총이익 태그(GrossProfit): 매출원가가 없을 때만 사용하고 방법을 기록. 총자산(Assets)은 Java에 없는 새 매핑.",
    "별칭 중복 제거: 같은 (공시, 기간, 양식, filed) 버전은 먼저 나온 별칭만 사용 (Java factRows 1268-1280).",
    "같은 태그 안의 상충 중복(같은 버전에 다른 값)은 값을 무효 처리 (Java uniqueFact 1417-1423).",
    "분기: 70-120일 직접 3개월 10-Q 값; YTD 차분은 106-285일 자체 10-Q 누계; Q4 = 자체 10-K 연간 - 자체 9M "
    "(Java 947-1001). Java는 70-105일이며, 120일은 16주 Q4 회계연도(PEP)를 위한 차이.",
    "차분은 같은 개념끼리만 수행하고, 연간 또는 누계가 자신의 기간 말 공시에서 나온 값만 사용. 후속 공시의 비교값으로 "
    "차분하면 서로 다른 시점(vintage)의 값이 섞임 (ABT 2011 Q4, PG FY2012 Q4 사례).",
    "시점 정보: 첫 공개 = 가장 이른 접수시각(acceptanceDateTime, submissions 색인과 accession으로 매칭). 접수시각이 없으면 "
    "filed 날짜의 뉴욕 23:59:59로 대체하고 표시 (Java는 UTC 날짜 시작 159, 167행, 더 이른 시각이라 미래 정보 위험).",
    "TTM: 기간 말에서 시작일 전날이 이전 분기 말과 정확히 일치하는 4개 분기. 가치 시점 = 구성요소 접수시각의 최댓값.",
    "GP/A(t) = (매출 TTM - 매출원가 TTM) / 총자산(t). 첫 공개값(PIT)과 최신값을 둘 다 보관.",
    "Java 제공자는 COGS, 매출총이익, 총자산을 다루지 않으며 인라인 XBRL 보완도 이 Python 수집에서 구현하지 않음 (companyfacts만 사용).",
    "커버리지 분모: 2009-06-30 ~ 2026-09-30의 달력 분기 70개. 계산 가능 분기 말을 가장 가까운 달력 분기 말에 45일 이내로 매칭.",
]


def _pct(value) -> str:
    return "-" if value is None else f"{value * 100:.1f}%"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Audit the collected SEC fundamentals (no network).")
    parser.add_argument("--sec", default="../research-data/sec")
    parser.add_argument("--out-dir", default="../research-data")
    args = parser.parse_args(argv)
    audit = build_audit(Path(args.sec))
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / JSON_NAME).write_text(json.dumps(audit, ensure_ascii=False, sort_keys=True, indent=1), encoding="utf-8")
    (out_dir / MD_NAME).write_text(render_markdown(audit), encoding="utf-8")
    print(f"wrote {out_dir / JSON_NAME} and {out_dir / MD_NAME}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
