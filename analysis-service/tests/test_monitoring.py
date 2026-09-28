import asyncio
from datetime import datetime, timedelta, timezone
from decimal import Decimal

import httpx

from app.main import app


AS_OF = datetime(2026, 9, 25, 21, tzinfo=timezone.utc)


def request(payload):
    async def call():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://analysis.test"
        ) as client:
            return await client.post("/internal/v1/monitoring/evaluations", json=payload)

    return asyncio.run(call())


def series(metric, values, *, unit="ratio", cadence="DAILY", source="FAKE"):
    points = []
    if cadence == "WEEKLY":
        dates = [AS_OF - timedelta(days=7 * (len(values) - index - 1)) for index in range(len(values))]
    elif cadence == "MONTHLY":
        dates = [AS_OF - timedelta(days=30 * (len(values) - index - 1)) for index in range(len(values))]
    else:
        dates = []
        day = AS_OF
        for _ in values:
            dates.append(day)
            day -= timedelta(days=1)
            while day.weekday() >= 5:
                day -= timedelta(days=1)
        dates.reverse()
    for offset, value in enumerate(values):
        observed_at = dates[offset]
        points.append(
            {
                "value": str(value) if value is not None else None,
                "asOf": observed_at.isoformat(),
                "collectedAt": observed_at.isoformat(),
            }
        )
    return {"metric": metric, "unit": unit, "source": source, "cadence": cadence, "points": points}


def market_request(*, series_data=None, shocks=None, shock_coverage_complete=False, incidents=None):
    return {
        "requestId": "11111111-1111-4111-8111-111111111111",
        "schemaVersion": "1",
        "asOf": AS_OF.isoformat(),
        "market": {
            "series": series_data or [],
            "shocks": shocks or [],
            "shockCoverageComplete": shock_coverage_complete,
            "incidents": incidents or [],
        },
        "portfolio": {"positions": [], "riskPolicy": {"maxConcentration": "0.50"}},
        "events": [],
        "watchlist": [],
    }


def response(payload):
    result = request(payload)
    assert result.status_code == 200, result.text
    return result.json()


def falling(start, end, count=21):
    return [start + (end - start) * index / (count - 1) for index in range(count)]


def test_vulnerability_alone_does_not_raise_market_risk_state():
    body = response(market_request(series_data=[
        series("valuation.forward_pe", [30] * 21),
        series("valuation.erp", [0.015] * 21),
        series("concentration.top10_weight", [0.36] * 21),
    ]))

    assert body["market"]["state"] == "NORMAL"
    assert body["market"]["vulnerability"]["status"] == "HIGH"
    assert body["market"]["dataQuality"] == "INSUFFICIENT_DATA"
    assert body["market"]["shock"]["status"] == "UNKNOWN"


def test_vulnerability_and_one_contagion_axis_raise_early_warning():
    body = response(market_request(series_data=[
        series("valuation.forward_pe", falling(20, 30)),
        series("valuation.erp", [0.015] * 21),
        series("equity.rsp_spy", falling(1.0, 0.94)),
    ]))

    assert body["market"]["state"] == "EARLY_WARNING"
    assert body["market"]["contagion"]["EQUITY"]["status"] == "WARN"
    assert body["market"]["contagion"]["CREDIT"]["status"] == "UNKNOWN"
    assert body["market"]["dataQuality"] == "PARTIAL"


def test_two_axes_including_credit_raise_risk_transition():
    credit = [100 + 3 * index for index in range(21)]
    body = response(market_request(series_data=[
        series("equity.rsp_spy", falling(1.0, 0.94)),
        series("credit.hy_oas", credit, unit="bps"),
    ]))

    assert body["market"]["state"] == "RISK_TRANSITION"
    assert body["market"]["contagion"]["EQUITY"]["status"] == "WARN"
    assert body["market"]["contagion"]["CREDIT"]["status"] == "WARN"


def test_equity_leadership_and_funding_stress_raise_strong_risk_off():
    body = response(market_request(series_data=[
        series("equity.rsp_spy", falling(1.0, 0.82)),
        series("equity.iwm_spy", falling(1.0, 0.80)),
        series("funding.move", falling(80, 150), unit="index"),
        series("funding.treasury_bid_ask", falling(1, 5), unit="bps"),
    ]))

    assert body["market"]["state"] == "STRONG_RISK_OFF"
    assert body["market"]["contagion"]["EQUITY"]["status"] == "STRESS"
    assert body["market"]["contagion"]["FUNDING"]["status"] == "STRESS"


def test_p0_requires_market_function_incident_and_forced_deleveraging():
    incident = {
        "kind": "MARKET_INFRASTRUCTURE",
        "forcedDeleveraging": True,
        "sourceType": "EXCHANGE",
        "official": True,
        "evidence": "Clearing disruption triggered forced liquidations",
        "source": "EXCHANGE",
        "asOf": AS_OF.isoformat(),
    }
    body = response(market_request(incidents=[incident]))

    assert body["market"]["state"] == "P0_SYSTEMIC"
    assert body["market"]["dataQuality"] == "INSUFFICIENT_DATA"


def test_secondary_incident_without_official_verification_cannot_raise_p0():
    incident = {
        "kind": "FUND",
        "forcedDeleveraging": True,
        "sourceType": "SECONDARY",
        "official": False,
        "evidence": "Unconfirmed fund liquidation rumor",
        "source": "NEWS",
        "asOf": AS_OF.isoformat(),
    }

    body = response(market_request(incidents=[incident]))

    assert body["market"]["state"] == "NORMAL"
    assert body["market"]["systemicEvidence"] == []


def test_complete_shock_feed_can_report_no_active_shocks():
    body = response(market_request(shock_coverage_complete=True))

    assert body["market"]["shock"]["status"] == "NONE"


def test_unavailable_requested_dimensions_are_explicit_unknown_coverage():
    market = response(market_request())["market"]

    assert {
        "breadth.above_20dma",
        "breadth.above_50dma",
        "breadth.above_200dma",
        "breadth.ad_line",
        "breadth.new_highs_lows",
    }.issubset(set(market["contagion"]["EQUITY"]["unknownFields"]))
    assert {
        "funding.sofr",
        "funding.repo_stress",
        "funding.standing_repo_usage",
        "funding.move",
        "funding.treasury_bid_ask",
    }.issubset(set(market["contagion"]["FUNDING"]["unknownFields"]))
    assert {"fx.usd_jpy", "fx.broad_dollar_index"}.issubset(
        set(market["contagion"]["FX"]["unknownFields"])
    )
    assert {
        "leverage.hedge_fund",
        "credit.mortgage_delinquency",
        "credit.consumer_delinquency",
    }.issubset(set(market["vulnerability"]["unknownFields"]))
    assert "buffers.revenue_revisions_3m" in market["buffers"]["unknownFields"]
    assert "buffers.bank_capital_ratio" in market["buffers"]["unknownFields"]
    assert "INFLATION" in market["shock"]["unknownFields"]


def test_revenue_revisions_are_a_reported_portfolio_buffer():
    body = response(market_request(series_data=[
        series("buffers.eps_revisions_3m", [0.02], unit="ratio"),
        series("buffers.revenue_revisions_3m", [0.01], unit="ratio"),
    ]))

    buffers = body["market"]["buffers"]
    assert buffers["status"] == "SUPPORTIVE"
    assert {item["metric"] for item in buffers["signals"]} == {
        "buffers.eps_revisions_3m",
        "buffers.revenue_revisions_3m",
    }


def test_stale_or_unknown_only_inputs_never_become_normal_confidence():
    payload = market_request(series_data=[
        series("equity.rsp_spy", [None] * 21),
        series("credit.hy_oas", [100] * 21, unit="bps"),
    ])
    for market_series in payload["market"]["series"]:
        for point in market_series["points"]:
            point["asOf"] = (AS_OF - timedelta(days=30)).isoformat()

    body = response(payload)

    assert body["market"]["state"] == "NORMAL"
    assert body["market"]["dataQuality"] == "INSUFFICIENT_DATA"
    assert body["market"]["contagion"]["EQUITY"]["status"] == "UNKNOWN"
    assert body["market"]["contagion"]["CREDIT"]["status"] == "UNKNOWN"
    assert body["market"]["vulnerability"]["status"] == "UNKNOWN"


def test_nominal_yield_decomposition_and_10y_observation_zone_are_reported():
    body = response(market_request(series_data=[
        series("rates.nominal_10y", [4.9] * 11 + [5.4] * 10, unit="percent"),
        series("rates.real_10y", [1.8] * 11 + [2.1] * 10, unit="percent"),
        series("rates.breakeven_10y", [2.5] * 11 + [2.7] * 10, unit="percent"),
        series("rates.term_premium_10y", [0.2] * 11 + [0.3] * 10, unit="percent"),
    ]))

    decomposition = body["market"]["yieldDecomposition"]
    assert decomposition["nominalChange"] == "0.5"
    assert decomposition["realChange"] == "0.3"
    assert decomposition["breakevenChange"] == "0.2"
    assert decomposition["termPremiumChange"] == "0.1"
    assert decomposition["observationZone"] is True
    assert {entry["source"] for entry in decomposition["evidence"]} == {"FAKE"}


def test_fred_sofr_and_fed_funds_series_form_a_funding_spread_signal():
    sofr = [Decimal("5.30") + Decimal(index) / 100 for index in range(21)]
    fed_funds = [Decimal("5.30")] * 21
    body = response(market_request(series_data=[
        series("funding.sofr", sofr, unit="percent", source="FRED"),
        series("funding.fed_funds", fed_funds, unit="percent", source="FRED"),
    ]))

    funding = body["market"]["contagion"]["FUNDING"]
    assert funding["status"] == "WARN"
    assert funding["signals"][0]["metric"] == "funding.sofr_minus_fed_funds"
    assert funding["signals"][0]["evidence"][0]["source"] == "DERIVED:FRED(SOFR-DFF)"


def test_weekly_series_uses_cadence_aware_windows_instead_of_calling_daily_bars_5d():
    body = response(market_request(series_data=[
        series("equity.rsp_spy", falling(1.0, 0.80, 5), cadence="WEEKLY"),
    ]))

    assert body["market"]["contagion"]["EQUITY"]["status"] == "UNKNOWN"
    assert "equity.rsp_spy.5D" in body["market"]["contagion"]["EQUITY"]["unknownFields"]


def test_portfolio_combined_symbol_concentration_applies_risk_policy():
    payload = market_request()
    payload["portfolio"] = {
        "asOf": AS_OF.isoformat(),
        "riskPolicy": {"maxConcentration": "0.50"},
        "positions": [
            {"symbol": "ABC", "quantity": "3", "avgCost": "10", "marketValue": "60", "weight": "0.30", "sector": "Tech", "factor": "Growth", "beta": "1.2", "correlation": "0.8", "thesis": "Growth", "primaryAlpha": "New product", "conditions": {"add": "Revenue grows", "reduce": "Margin slips", "exit": "Product fails", "invalidation": "Revenue reverses"}, "invalidationEvidence": []},
            {"symbol": "ABC", "quantity": "2", "avgCost": "10", "marketValue": "50", "weight": "0.25", "sector": "Tech", "factor": "Growth", "beta": "1.1", "correlation": "0.7", "thesis": "Growth", "primaryAlpha": "New product", "conditions": {"add": "Revenue grows", "reduce": "Margin slips", "exit": "Product fails", "invalidation": "Revenue reverses"}, "invalidationEvidence": []},
        ],
    }

    portfolio = response(payload)["portfolio"]

    assert [position["state"] for position in portfolio["positions"]] == ["REDUCE_CANDIDATE", "REDUCE_CANDIDATE"]
    assert portfolio["positions"][0]["combinedExposure"]["symbolWeight"] == "0.55"
    assert portfolio["positions"][0]["combinedExposure"]["sectorWeight"] == "0.55"
    assert portfolio["policyBreaches"][0]["policy"] == "maxConcentration"


def test_price_only_evidence_does_not_invalidate_thesis():
    payload = market_request()
    payload["portfolio"] = {
        "asOf": AS_OF.isoformat(),
        "riskPolicy": {"maxConcentration": "0.50"},
        "positions": [{"symbol": "ABC", "quantity": "1", "avgCost": "20", "marketValue": "10", "weight": "0.10", "sector": "Tech", "factor": "Growth", "beta": "1.2", "correlation": "0.8", "thesis": "Growth", "primaryAlpha": "New product", "conditions": {"add": "Revenue grows", "reduce": "Margin slips", "exit": "Product fails", "invalidation": "Revenue reverses"}, "invalidationEvidence": [{"kind": "PRICE", "conditionMatch": True, "evidence": "Price fell below 50DMA", "source": "FAKE", "asOf": AS_OF.isoformat()}]}],
    }

    assert response(payload)["portfolio"]["positions"][0]["state"] == "NORMAL"


def test_stale_portfolio_snapshot_cannot_create_a_policy_breach():
    payload = market_request()
    payload["portfolio"] = {
        "asOf": (AS_OF - timedelta(hours=2)).isoformat(),
        "riskPolicy": {"maxConcentration": "0.50"},
        "positions": [{"symbol": "ABC", "quantity": "1", "avgCost": "20", "marketValue": "80", "weight": "0.80", "sector": "Tech", "factor": "Growth", "beta": "1.2", "correlation": "0.8", "thesis": "Growth", "primaryAlpha": "New product", "conditions": {"add": "Revenue grows", "reduce": "Margin slips", "exit": "Product fails", "invalidation": "Revenue reverses"}, "invalidationEvidence": []}],
    }

    portfolio = response(payload)["portfolio"]
    assert portfolio["positions"][0]["state"] == "NORMAL"
    assert portfolio["positions"][0]["dataQuality"] == "INSUFFICIENT_DATA"
    assert portfolio["positions"][0]["policyBreaches"] == []


def test_matching_fundamental_invalidation_is_explicit_and_policy_caps_are_not_invented():
    payload = market_request()
    payload["portfolio"] = {
        "asOf": AS_OF.isoformat(),
        "riskPolicy": {"maxConcentration": "0.50"},
        "positions": [{"symbol": "ABC", "quantity": "1", "avgCost": "20", "marketValue": "10", "weight": "0.10", "sector": "Tech", "factor": "Growth", "beta": "1.2", "correlation": "0.8", "thesis": "Growth", "primaryAlpha": "New product", "conditions": {"add": "Revenue grows", "reduce": "Margin slips", "exit": "Product fails", "invalidation": "Revenue reverses"}, "invalidationEvidence": [{"kind": "FUNDAMENTAL", "conditionMatch": True, "evidence": "Revenue declined two quarters", "source": "SEC", "asOf": AS_OF.isoformat()}]}],
    }

    position = response(payload)["portfolio"]["positions"][0]
    assert position["state"] == "THESIS_INVALIDATED"
    assert position["policyBreaches"] == []
    assert position["combinedExposure"]["sectorWeight"] == "0.10"


def test_only_official_material_events_require_thesis_recheck():
    payload = market_request()
    payload["events"] = [
        {"sourceEventId": "sec-1", "symbol": "ABC", "kind": "GUIDANCE", "sourceType": "SEC", "title": "Guidance reduced", "evidence": "Company lowered revenue guidance", "publishedAt": AS_OF.isoformat(), "collectedAt": AS_OF.isoformat(), "official": True},
        {"sourceEventId": "rumor-1", "symbol": "ABC", "kind": "DILUTION", "sourceType": "SECONDARY", "sourceUrl": "https://news.example/1", "title": "Possible offering", "evidence": "Unconfirmed report", "publishedAt": AS_OF.isoformat(), "collectedAt": AS_OF.isoformat(), "official": False},
    ]

    events = response(payload)["events"]
    assert events[0]["material"] is True
    assert events[0]["thesisRecheckRequired"] is True
    assert events[1]["material"] is False
    assert events[1]["thesisRecheckRequired"] is False
    assert events[0]["sourceUrl"] is None


def test_watchlist_needs_price_volume_and_relative_strength_together():
    payload = market_request()
    base = {"symbol": "ONTO", "status": "WATCH", "levels": {"prepare": {"min": "295", "max": "302"}, "confirm": {"min": "306", "max": "310"}, "pullback": {"min": "262", "max": "270"}, "invalidate": {"min": "240", "max": "250"}}, "price": "298", "volumeMultiple": "1.4", "relativeStrength": "0.02", "source": "FAKE", "asOf": AS_OF.isoformat(), "collectedAt": AS_OF.isoformat()}
    payload["watchlist"] = [
        base,
        {**base, "symbol": "LOWVOL", "volumeMultiple": "0.7"},
        {**base, "symbol": "WEAKRS", "relativeStrength": "-0.01"},
    ]

    states = {item["symbol"]: item["state"] for item in response(payload)["watchlist"]}
    assert states == {"ONTO": "PREPARE", "LOWVOL": "WATCH", "WEAKRS": "WATCH"}


def test_watchlist_confirm_requires_both_price_breakout_and_strong_volume():
    payload = market_request()
    base = {"symbol": "ONTO", "status": "PREPARE", "levels": {"prepare": {"min": "295", "max": "302"}, "confirm": {"min": "306", "max": "310"}, "pullback": {"min": "262", "max": "270"}, "invalidate": {"min": "240", "max": "250"}}, "price": "308", "volumeMultiple": "1.3", "relativeStrength": "0.03", "source": "FAKE", "asOf": AS_OF.isoformat(), "collectedAt": AS_OF.isoformat()}
    body = response({**payload, "watchlist": [base, {**base, "symbol": "LOWVOL", "volumeMultiple": "0.9"}]})

    states = {item["symbol"]: item["state"] for item in body["watchlist"]}
    assert states == {"ONTO": "ACTION_CANDIDATE", "LOWVOL": "WATCH"}


def test_watchlist_explicit_invalidation_band_is_terminal():
    payload = market_request()
    item = {"symbol": "ONTO", "status": "PREPARE", "levels": {"prepare": {"min": "295", "max": "302"}, "confirm": {"min": "306", "max": "310"}, "pullback": {"min": "262", "max": "270"}, "invalidate": {"min": "240", "max": "250"}}, "price": "248", "volumeMultiple": "0.8", "relativeStrength": "-0.05", "source": "FAKE", "asOf": AS_OF.isoformat(), "collectedAt": AS_OF.isoformat()}

    result = response({**payload, "watchlist": [item]})["watchlist"][0]

    assert result["state"] == "INVALIDATED"
    assert result["changed"] is True


def test_missing_watchlist_inputs_keep_previous_state_without_inventing_values():
    payload = market_request()
    item = {"symbol": "ONTO", "status": "PREPARE", "levels": {"prepare": {"min": "295", "max": "302"}, "confirm": {"min": "306", "max": "310"}, "pullback": {"min": "262", "max": "270"}, "invalidate": {"min": "240", "max": "250"}}, "price": None, "volumeMultiple": "1.4", "relativeStrength": "0.02", "source": "FAKE", "asOf": AS_OF.isoformat(), "collectedAt": AS_OF.isoformat()}

    result = response({**payload, "watchlist": [item]})["watchlist"][0]

    assert result["state"] == "PREPARE"
    assert result["changed"] is False
    assert result["unknownFields"] == ["price"]
