from datetime import datetime, timedelta, timezone
from decimal import Decimal

from .models import Cadence, MarketInput, MarketSeries, Point


MAX_AGE = {
    Cadence.INTRADAY: timedelta(minutes=30),
    Cadence.DAILY: timedelta(days=4),
    Cadence.WEEKLY: timedelta(days=12),
    Cadence.MONTHLY: timedelta(days=45),
}

# Thresholds are changes in the bad direction; ratio/index series use relative changes,
# breadth and spread series use absolute changes in their declared unit.
RULES = {
    "equity.rsp_spy": ("EQUITY", "relative", -1, ("0.02", "0.03", "0.05"), ("0.04", "0.06", "0.10")),
    "equity.iwm_spy": ("EQUITY", "relative", -1, ("0.02", "0.03", "0.05"), ("0.04", "0.06", "0.10")),
    "equity.soxx_spy": ("EQUITY", "relative", -1, ("0.02", "0.03", "0.05"), ("0.04", "0.06", "0.10")),
    "equity.mega_cap_leadership": ("EQUITY", "relative", -1, ("0.02", "0.03", "0.05"), ("0.04", "0.06", "0.10")),
    "breadth.above_20dma": ("EQUITY", "absolute", -1, ("0.08", "0.10", "0.15"), ("0.14", "0.20", "0.30")),
    "breadth.above_50dma": ("EQUITY", "absolute", -1, ("0.08", "0.10", "0.15"), ("0.14", "0.20", "0.30")),
    "breadth.above_200dma": ("EQUITY", "absolute", -1, ("0.08", "0.10", "0.15"), ("0.14", "0.20", "0.30")),
    "breadth.ad_line": ("EQUITY", "relative", -1, ("0.05", "0.08", "0.12"), ("0.10", "0.15", "0.22")),
    "breadth.new_highs_lows": ("EQUITY", "absolute", -1, ("25", "50", "100"), ("50", "100", "200")),
    "credit.hy_oas": ("CREDIT", "absolute", 1, ("0.20", "0.35", "0.60"), ("0.45", "0.75", "1.00")),
    "credit.ig_oas": ("CREDIT", "absolute", 1, ("0.10", "0.20", "0.35"), ("0.20", "0.35", "0.50")),
    "credit.hyg_lqd": ("CREDIT", "relative", -1, ("0.015", "0.025", "0.04"), ("0.03", "0.05", "0.08")),
    "credit.kre_xlf": ("CREDIT", "relative", -1, ("0.02", "0.03", "0.05"), ("0.04", "0.06", "0.10")),
    "credit.leveraged_loan_price": ("CREDIT", "relative", -1, ("0.015", "0.025", "0.04"), ("0.03", "0.05", "0.08")),
    "funding.sofr_repo_spread": ("FUNDING", "absolute", 1, ("5", "10", "15"), ("10", "20", "30")),
    "funding.repo_stress": ("FUNDING", "absolute", 1, ("5", "10", "15"), ("10", "20", "30")),
    "funding.move": ("FUNDING", "absolute", 1, ("25", "40", "60"), ("50", "75", "100")),
    "funding.treasury_bid_ask": ("FUNDING", "absolute", 1, ("1", "2", "3"), ("2", "4", "6")),
    "fx.dxy": ("FX", "relative", 1, ("0.015", "0.025", "0.04"), ("0.03", "0.05", "0.08")),
    "fx.usdjpy": ("FX", "relative", -1, ("0.02", "0.03", "0.05"), ("0.04", "0.06", "0.08")),
    "fx.usd_jpy": ("FX", "relative", -1, ("0.02", "0.03", "0.05"), ("0.04", "0.06", "0.08")),
    "fx.broad_dollar_index": ("FX", "relative", 1, ("0.015", "0.025", "0.04"), ("0.03", "0.05", "0.08")),
    "fx.global_risk_assets": ("FX", "relative", -1, ("0.02", "0.03", "0.05"), ("0.04", "0.06", "0.10")),
    "funding.sofr_minus_fed_funds": ("FUNDING", "absolute", 1, ("5", "10", "15"), ("10", "20", "30")),
}

AXIS_EXPECTED = {
    "EQUITY": [
        "equity.rsp_spy", "equity.iwm_spy", "equity.soxx_spy", "equity.mega_cap_leadership",
        "breadth.above_20dma", "breadth.above_50dma", "breadth.above_200dma",
        "breadth.ad_line", "breadth.new_highs_lows",
    ],
    "CREDIT": ["credit.hy_oas", "credit.ig_oas", "credit.hyg_lqd", "credit.kre_xlf", "credit.leveraged_loan_price"],
    "FUNDING": [
        "funding.sofr", "funding.sofr_minus_fed_funds", "funding.repo_stress",
        "funding.standing_repo_usage", "funding.move", "funding.treasury_bid_ask",
    ],
    "FX": ["fx.broad_dollar_index", "fx.usd_jpy", "fx.global_risk_assets"],
}

SHOCK_EXPECTED = (
    "INFLATION", "OIL", "GROWTH", "TREASURY_FISCAL", "AI_MEGA_CAP",
    "DXY_USDJPY", "GEOPOLITICAL", "INSTITUTION",
)

BUFFER_EXPECTED = (
    "buffers.bank_capital_ratio", "buffers.liquidity_coverage",
    "buffers.household_debt_service", "buffers.corporate_leverage",
    "buffers.eps_revisions_3m", "buffers.revenue_revisions_3m",
    "buffers.lending_standards", "buffers.repo_stability",
    "buffers.fed_treasury_response",
)

VULNERABILITY_RULES = {
    "valuation.forward_pe": ("high", Decimal("25"), Decimal("30")),
    "valuation.erp": ("low", Decimal("0.025"), Decimal("0.02")),
    "concentration.top10_weight": ("high", Decimal("0.30"), Decimal("0.35")),
    "rates.real_10y": ("high", Decimal("2.0"), Decimal("2.5")),
    "cre.cmbs_delinquency": ("high", Decimal("0.05"), Decimal("0.07")),
    "private_credit.bdc_non_accrual": ("high", Decimal("0.04"), Decimal("0.06")),
    "leverage.treasury_basis": ("high", Decimal("5"), Decimal("8")),
    "credit.mortgage_delinquency": ("high", Decimal("0.04"), Decimal("0.06")),
    "credit.consumer_delinquency": ("high", Decimal("0.05"), Decimal("0.07")),
}


def _grouped_points(series: MarketSeries, as_of: datetime) -> list:
    grouped = {}
    for point in sorted(series.points, key=lambda item: item.as_of):
        if point.as_of > as_of or point.collected_at > as_of:
            continue
        grouped[point.as_of.astimezone(timezone.utc).date()] = point
    return [grouped[day] for day in sorted(grouped)]


def _point_is_fresh(series: MarketSeries, point, as_of: datetime) -> bool:
    age = as_of - point.as_of
    return timedelta(0) <= age <= MAX_AGE[series.cadence] and point.value is not None


def _velocity_offsets(cadence: Cadence) -> dict[int, int]:
    if cadence in (Cadence.DAILY, Cadence.INTRADAY):
        return {5: 5, 10: 10, 20: 20}
    return {}


def _velocity(series: MarketSeries, as_of: datetime) -> tuple[dict[str, Decimal], dict[str, object], list[str]]:
    points = _grouped_points(series, as_of)
    if not points or not _point_is_fresh(series, points[-1], as_of):
        return {}, {}, [f"{series.metric}.current"]
    latest = points[-1]
    velocities: dict[str, Decimal] = {}
    references: dict[str, object] = {}
    missing = []
    offsets = _velocity_offsets(series.cadence)
    if not offsets:
        return {}, {}, [f"{series.metric}.{days}D" for days in (5, 10, 20)]
    for days, offset in offsets.items():
        if len(points) <= offset:
            missing.append(f"{series.metric}.{days}D")
            continue
        reference = points[-1 - offset]
        elapsed = (latest.as_of - reference.as_of).total_seconds() / 86400
        if elapsed < days or elapsed > days * 2 or reference.value in (None, Decimal(0)):
            missing.append(f"{series.metric}.{days}D")
            continue
        if series.metric in RULES and RULES[series.metric][1] == "relative":
            change = latest.value / reference.value - Decimal(1)
        else:
            change = latest.value - reference.value
        velocities[f"{days}D"] = change
        references[f"{days}D"] = reference
    return velocities, references, missing


def _evidence(series: MarketSeries, point) -> dict:
    return {
        "metric": series.metric,
        "value": point.value,
        "unit": series.unit,
        "source": series.source,
        "asOf": point.as_of,
        "collectedAt": point.collected_at,
    }


def _series_map(market: MarketInput, as_of: datetime):
    by_metric = {}
    duplicates = set()
    for series in market.series:
        if series.metric in by_metric:
            duplicates.add(series.metric)
        by_metric[series.metric] = series
    current = {}
    unknown = set(duplicates)
    for name, series in by_metric.items():
        if name in duplicates:
            continue
        points = _grouped_points(series, as_of)
        if points and _point_is_fresh(series, points[-1], as_of):
            current[name] = (series, points[-1])
        else:
            unknown.add(name)
    return by_metric, current, unknown


def _with_derived_funding_spread(market: MarketInput) -> MarketInput:
    raw = {item.metric: item for item in market.series}
    sofr = raw.get("funding.sofr")
    fed_funds = raw.get("funding.fed_funds")
    if sofr is None or fed_funds is None:
        return market

    def by_date(series):
        points = {}
        for point in series.points:
            if point.value is not None:
                points[point.as_of.astimezone(timezone.utc).date()] = point
        return points

    sofr_points = by_date(sofr)
    fed_points = by_date(fed_funds)
    derived = []
    for day in sorted(sofr_points.keys() & fed_points.keys()):
        left, right = sofr_points[day], fed_points[day]
        derived.append(Point(
            value=(left.value - right.value) * Decimal(100),
            as_of=max(left.as_of, right.as_of),
            collected_at=max(left.collected_at, right.collected_at),
        ))
    return market.model_copy(update={"series": [
        *market.series,
        MarketSeries(
            metric="funding.sofr_minus_fed_funds", unit="bps",
            source="DERIVED:FRED(SOFR-DFF)", cadence=Cadence.DAILY, points=derived,
        ),
    ]})


def _vulnerability(by_metric, current, as_of):
    known = 0
    elevated = 0
    high = 0
    worsening = False
    evidence = []
    unknown = []
    expected = [
        "valuation.forward_pe", "valuation.erp", "concentration.top10_weight",
        "rates.nominal_10y", "rates.real_10y", "rates.breakeven_10y",
        "cre.cmbs_delinquency", "private_credit.bdc_non_accrual",
        "leverage.hedge_fund", "leverage.treasury_basis",
        "credit.mortgage_delinquency", "credit.consumer_delinquency",
    ]
    for name in expected:
        if name not in current:
            unknown.append(name)
    # Leverage is exposed for visibility, but no source-calibrated cutoff is configured.
    if "leverage.hedge_fund" in current:
        unknown.append("leverage.hedge_fund.threshold")
    for name, (direction, warning, severe) in VULNERABILITY_RULES.items():
        if name not in current:
            continue
        series, point = current[name]
        value = point.value
        known += 1
        is_elevated = value >= warning if direction == "high" else value <= warning
        is_high = value >= severe if direction == "high" else value <= severe
        if is_elevated:
            elevated += 1
        if is_high:
            high += 1
        velocity, _, _ = _velocity(series, as_of)
        ten_day = velocity.get("10D")
        worsening_now = ten_day is not None and (
            (direction == "high" and ten_day >= Decimal("0.03"))
            or (direction == "low" and ten_day <= Decimal("-0.002"))
        )
        worsening = worsening or (is_elevated and worsening_now)
        evidence_item = _evidence(series, point)
        evidence_item.update({"elevated": is_elevated, "high": is_high, "worsening": worsening_now})
        evidence.append(evidence_item)
    if known == 0:
        status = "UNKNOWN"
    elif high >= 1 or elevated >= 2:
        status = "HIGH"
    elif elevated:
        status = "ELEVATED"
    elif known >= 3:
        status = "LOW"
    else:
        status = "UNKNOWN"
    return {"status": status, "worsening": worsening, "evidence": evidence, "unknownFields": unknown}


def _axis_results(by_metric, current, as_of):
    output = {}
    for axis, expected in AXIS_EXPECTED.items():
        signals = []
        usable = 0
        missing = [name for name in expected if name not in current]
        for name, rule in RULES.items():
            if rule[0] != axis or name not in current:
                continue
            series, point = current[name]
            velocities, references, unavailable = _velocity(series, as_of)
            missing.extend(unavailable)
            if not velocities:
                continue
            usable += 1
            _, method, direction, warning_thresholds, stress_thresholds = rule
            warn = tuple(Decimal(value) for value in warning_thresholds)
            stress = tuple(Decimal(value) for value in stress_thresholds)
            if name in ("credit.hy_oas", "credit.ig_oas") and series.unit.lower() in ("bps", "bp"):
                warn = tuple(value * Decimal(100) for value in warn)
                stress = tuple(value * Decimal(100) for value in stress)
            level = None
            for index, days in enumerate((5, 10, 20)):
                velocity = velocities.get(f"{days}D")
                if velocity is None:
                    continue
                adverse_change = velocity * direction
                if adverse_change >= stress[index]:
                    level = "STRESS"
                elif adverse_change >= warn[index] and level is None:
                    level = "WARN"
            if level:
                signals.append({
                    "metric": name,
                    "severity": level,
                    "velocity": velocities,
                    "evidence": [_evidence(series, point)],
                })
        stress_count = sum(item["severity"] == "STRESS" for item in signals)
        warn_count = len(signals)
        if usable == 0:
            status = "UNKNOWN"
        elif stress_count or warn_count >= 2:
            status = "STRESS"
        elif warn_count:
            status = "WARN"
        else:
            status = "NORMAL"
        output[axis] = {"status": status, "signals": signals, "unknownFields": sorted(set(missing))}
    return output


def _shock_result(market: MarketInput, as_of: datetime):
    fresh = [shock for shock in market.shocks if timedelta(0) <= as_of - shock.as_of <= timedelta(days=7)]
    signals = [
        {"category": shock.category.value, "severity": shock.severity.value, "evidence": shock.evidence, "source": shock.source, "asOf": shock.as_of}
        for shock in fresh
    ]
    if signals:
        status = "STRESS" if any(item["severity"] == "STRESS" for item in signals) else "WARN"
    else:
        status = "NONE" if market.shock_coverage_complete else "UNKNOWN"
    covered = {item["category"] for item in signals}
    unknown = [] if market.shock_coverage_complete else sorted(set(SHOCK_EXPECTED) - covered)
    return {"status": status, "signals": signals, "unknownFields": unknown}


def _buffer_result(current):
    supportive = 0
    weak = 0
    signals = []
    for name, (series, point) in current.items():
        value = point.value
        state = None
        if name == "buffers.bank_capital_ratio":
            state = "SUPPORTIVE" if value >= 12 else "WEAK" if value < 10 else None
        elif name == "buffers.liquidity_coverage":
            state = "SUPPORTIVE" if value >= 120 else "WEAK" if value < 100 else None
        elif name == "buffers.household_debt_service":
            state = "SUPPORTIVE" if value < 10 else "WEAK" if value > 12 else None
        elif name == "buffers.corporate_leverage":
            state = "SUPPORTIVE" if value < 3 else "WEAK" if value > 5 else None
        elif name == "buffers.eps_revisions_3m":
            state = "SUPPORTIVE" if value > 0 else "WEAK" if value < 0 else None
        elif name == "buffers.revenue_revisions_3m":
            state = "SUPPORTIVE" if value > 0 else "WEAK" if value < 0 else None
        elif name == "buffers.lending_standards":
            state = "SUPPORTIVE" if value < 0 else "WEAK" if value > 10 else None
        elif name == "buffers.repo_stability":
            state = "SUPPORTIVE" if value >= Decimal("0.9") else "WEAK" if value < Decimal("0.7") else None
        elif name == "buffers.fed_treasury_response":
            state = "SUPPORTIVE" if value > 0 else "WEAK" if value < 0 else None
        if state:
            supportive += state == "SUPPORTIVE"
            weak += state == "WEAK"
            signals.append({"metric": name, "status": state, "evidence": _evidence(series, point)})
    unknown = sorted(set(BUFFER_EXPECTED) - set(current))
    if not signals:
        status = "UNKNOWN" if not any(name.startswith("buffers.") for name in current) else "MIXED"
    elif supportive and weak:
        status = "MIXED"
    elif supportive:
        status = "SUPPORTIVE"
    else:
        status = "WEAK"
    return {"status": status, "signals": signals, "unknownFields": unknown}


def _yield_decomposition(current, as_of):
    changes = {}
    evidence = []
    unknown = []
    metric_names = {
        "nominalChange": "rates.nominal_10y",
        "realChange": "rates.real_10y",
        "breakevenChange": "rates.breakeven_10y",
        "termPremiumChange": "rates.term_premium_10y",
    }
    nominal_value = None
    for field, name in metric_names.items():
        item = current.get(name)
        if item is None:
            changes[field] = None
            unknown.append(name)
            continue
        series, point = item
        if field == "nominalChange":
            nominal_value = point.value
        velocities, references, _ = _velocity(series, as_of)
        changes[field] = velocities.get("10D")
        reference = references.get("10D")
        if changes[field] is None or reference is None:
            unknown.append(f"{name}.10D")
        else:
            evidence.extend([_evidence(series, reference), _evidence(series, point)])
    observation_zone = None if nominal_value is None else Decimal("5.3") <= nominal_value <= Decimal("5.5")
    return {
        **changes,
        "observationZone": observation_zone,
        "evidence": evidence,
        "unknownFields": unknown,
    }


def evaluate_market(market: MarketInput, as_of: datetime) -> dict:
    market = _with_derived_funding_spread(market)
    by_metric, current, _ = _series_map(market, as_of)
    vulnerability = _vulnerability(by_metric, current, as_of)
    contagion = _axis_results(by_metric, current, as_of)
    shock = _shock_result(market, as_of)
    buffers = _buffer_result({name: item for name, item in current.items() if name.startswith("buffers.")})
    yield_decomposition = _yield_decomposition(current, as_of)
    axes = [item["status"] for item in contagion.values()]
    if all(axis == "UNKNOWN" for axis in axes):
        quality = "INSUFFICIENT_DATA"
    elif (
        any(axis == "UNKNOWN" for axis in axes)
        or any(axis["unknownFields"] for axis in contagion.values())
        or vulnerability["unknownFields"]
        or shock["unknownFields"]
        or buffers["unknownFields"]
        or yield_decomposition["unknownFields"]
    ):
        quality = "PARTIAL"
    else:
        quality = "COMPLETE"
    official_sources = {"SEC", "COMPANY_IR", "EXCHANGE", "GOVERNMENT"}
    incident = next((
        item for item in market.incidents
        if item.kind.value in {"INSTITUTION", "FUND", "MARKET_INFRASTRUCTURE"}
        and item.official
        and item.source_type.value in official_sources
        and item.forced_deleveraging
        and timedelta(0) <= as_of - item.as_of <= timedelta(days=1)
    ), None)
    if incident:
        state = "P0_SYSTEMIC"
    elif contagion["EQUITY"]["status"] == "STRESS" and (
        contagion["CREDIT"]["status"] == "STRESS" or contagion["FUNDING"]["status"] == "STRESS"
    ):
        state = "STRONG_RISK_OFF"
    elif sum(axis in {"WARN", "STRESS"} for axis in axes) >= 2 and (
        contagion["CREDIT"]["status"] in {"WARN", "STRESS"}
        or contagion["FUNDING"]["status"] in {"WARN", "STRESS"}
    ):
        state = "RISK_TRANSITION"
    elif vulnerability["worsening"] and any(axis in {"WARN", "STRESS"} for axis in axes):
        state = "EARLY_WARNING"
    else:
        state = "NORMAL"
    systemic_evidence = ([{
        "type": "SYSTEMIC_INCIDENT",
        "kind": incident.kind.value,
        "forcedDeleveraging": incident.forced_deleveraging,
        "evidence": incident.evidence,
        "source": incident.source,
        "sourceType": incident.source_type.value,
        "asOf": incident.as_of,
    }] if incident else [])
    state_evidence = list(systemic_evidence)
    for axis in contagion.values():
        if axis["status"] in {"WARN", "STRESS"}:
            state_evidence.extend(
                item for signal in axis["signals"] for item in signal["evidence"]
            )
    if vulnerability["worsening"]:
        state_evidence.extend(
            item for item in vulnerability["evidence"] if item["worsening"]
        )
    return {
        "state": state,
        "dataQuality": quality,
        "stateEvidence": state_evidence,
        "systemicEvidence": systemic_evidence,
        "vulnerability": vulnerability,
        "shock": shock,
        "contagion": contagion,
        "buffers": buffers,
        "yieldDecomposition": yield_decomposition,
    }
