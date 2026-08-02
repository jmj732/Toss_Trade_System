import asyncio
import json
from pathlib import Path

import httpx

from app.main import app


AT = "2026-08-02T00:00:00Z"
AS_OF = "2026-08-01T20:00:00Z"


def request(method: str, path: str, **kwargs):
    async def call():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://analysis.test"
        ) as client:
            return await client.request(method, path, **kwargs)

    return asyncio.run(call())


def observation(field, value, provider="SEC", as_of=AS_OF, missing_data=None):
    return {
        "field": field,
        "value": value,
        "unit": "ratio" if field.startswith(("fundamental.", "technical.", "macro.")) else "USD",
        "period": None,
        "identifier": "AAPL",
        "provider": provider,
        "asOf": as_of,
        "collectedAt": AT,
        "missingData": missing_data or [],
    }


def complete_input(observations):
    return {
        "requestId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
        "input": {
            "snapshotId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
            "symbol": "AAPL",
            "schemaVersion": "1",
            "collectedAt": AT,
            "observations": observations,
        },
    }


def complete_observations():
    return [
        observation("fundamental.net_income", "100"),
        observation("fundamental.revenue", "400"),
        observation("fundamental.equity", "500"),
        observation("fundamental.total_liabilities", "250"),
        observation("fundamental.operating_cash_flow", "120"),
        observation("fundamental.eps", "5"),
        observation("fundamental.book_value_per_share", "10"),
        observation("fundamental.revenue_per_share", "20"),
        observation("fundamental.free_cash_flow_per_share", "4"),
        observation("quote.price", "200", provider="FMP"),
        observation("technical.sma20", "190", provider="FMP"),
        observation("technical.sma50", "180", provider="FMP"),
        observation("technical.rsi14", "55", provider="FMP"),
        observation("technical.volatility20", "0.2", provider="FMP"),
        observation("macro.vix", "18", provider="FRED"),
        observation("macro.sp500_return20d", "0.04", provider="FRED"),
    ]


def analyzer(body, name):
    return next(item for item in body["analyzers"] if item["analyzer"] == name)


def metric(body, analyzer_name, metric_name):
    return next(item for item in analyzer(body, analyzer_name)["metrics"] if item["name"] == metric_name)


def test_core_analysis_is_deterministic_and_provenance_preserving():
    payload = complete_input(complete_observations())

    first = request("POST", "/internal/v3/stock-analyses", json=payload)
    second = request("POST", "/internal/v3/stock-analyses", json=payload)

    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json() == second.json()
    body = first.json()
    assert body["status"] == "COMPLETED"
    assert body["asOf"] == AT
    assert [item["analyzer"] for item in body["analyzers"]] == [
        "fundamental", "valuation", "technical", "marketRegime"
    ]
    assert metric(body, "fundamental", "fundamental.profit_margin")["value"] == "0.25"
    assert metric(body, "valuation", "valuation.pe")["value"] == "40"
    assert metric(body, "technical", "technical.price_vs_sma20")["value"] == "0.0526315789"
    assert metric(body, "marketRegime", "marketRegime.state")["value"] == "RISK_ON"
    assert [analyzer(body, name)["confidence"] for name in (
        "fundamental", "valuation", "technical", "marketRegime"
    )] == ["1", "1", "1", "1"]
    assert metric(body, "valuation", "valuation.pe")["provenance"] == [
        {"provider": "FMP", "field": "quote.price", "asOf": AS_OF, "collectedAt": AT},
        {"provider": "SEC", "field": "fundamental.eps", "asOf": AS_OF, "collectedAt": AT},
    ]


def test_missing_and_duplicate_fields_degrade_only_dependent_metrics():
    observations = [
        observation("fundamental.net_income", "100"),
        observation("fundamental.revenue", "400"),
        observation("quote.price", "200", provider="FMP"),
        observation("quote.price", "201", provider="FINNHUB"),
        observation("technical.sma20", "190", provider="FMP"),
        observation("technical.sma50", "180", provider="FMP"),
        observation("technical.rsi14", "55", provider="FMP"),
        observation("technical.volatility20", "0.2", provider="FMP"),
        observation("macro.vix", "35", provider="FRED"),
        observation("macro.sp500_return20d", "-0.1", provider="FRED"),
    ]

    response = request("POST", "/internal/v3/stock-analyses", json=complete_input(observations))

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "DEGRADED"
    assert metric(body, "fundamental", "fundamental.profit_margin")["value"] == "0.25"
    assert metric(body, "fundamental", "fundamental.roe")["value"] is None
    assert metric(body, "fundamental", "fundamental.roe")["missingData"] == [
        "FIELD_MISSING:fundamental.equity"
    ]
    assert metric(body, "valuation", "valuation.pe")["value"] is None
    assert metric(body, "valuation", "valuation.pe")["missingData"] == [
        "AMBIGUOUS_DUPLICATE_FIELD:quote.price", "FIELD_MISSING:fundamental.eps"
    ]
    assert metric(body, "technical", "technical.sma_trend")["value"] == "0.0555555556"
    assert metric(body, "marketRegime", "marketRegime.state")["value"] == "RISK_OFF"
    assert analyzer(body, "valuation")["confidence"] == "0"


def test_direct_values_keep_precision_and_regime_boundary_is_neutral():
    observations = complete_observations()
    observations[-4] = observation("technical.rsi14", "55.1234567890123", provider="FMP")
    observations[-2] = observation("macro.vix", "20.1234567890123", provider="FRED")
    observations[-1] = observation("macro.sp500_return20d", "-0.01", provider="FRED")

    body = request(
        "POST", "/internal/v3/stock-analyses", json=complete_input(observations)
    ).json()

    assert metric(body, "technical", "technical.rsi14")["value"] == "55.1234567890123"
    assert metric(body, "marketRegime", "marketRegime.vix")["value"] == "20.1234567890123"
    assert metric(body, "marketRegime", "marketRegime.state")["value"] == "NEUTRAL"


def test_zero_denominator_is_missing_without_inventing_a_ratio():
    observations = complete_observations()
    observations[1] = observation("fundamental.revenue", "0")

    body = request(
        "POST", "/internal/v3/stock-analyses", json=complete_input(observations)
    ).json()

    assert metric(body, "fundamental", "fundamental.profit_margin") == {
        "name": "fundamental.profit_margin",
        "value": None,
        "unit": "ratio",
        "asOf": None,
        "provenance": [
            {"provider": "SEC", "field": "fundamental.net_income", "asOf": AS_OF, "collectedAt": AT},
            {"provider": "SEC", "field": "fundamental.revenue", "asOf": AS_OF, "collectedAt": AT},
        ],
        "missingData": ["DIVISION_BY_ZERO:fundamental.profit_margin"],
    }


def test_pinned_v3_fixture_preserves_degraded_shape():
    root = Path(__file__).parents[2]
    payload = json.loads(
        (root / "contracts/analysis/v3/stock-analysis-core-request.json").read_text()
    )
    expected = json.loads(
        (root / "contracts/analysis/v3/stock-analysis-core-response.json").read_text()
    )

    response = request("POST", "/internal/v3/stock-analyses", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == expected["status"]
    assert [item["analyzer"] for item in body["analyzers"]] == [
        item["analyzer"] for item in expected["analyzers"]
    ]
    assert [len(item["metrics"]) for item in body["analyzers"]] == [
        len(item["metrics"]) for item in expected["analyzers"]
    ]
