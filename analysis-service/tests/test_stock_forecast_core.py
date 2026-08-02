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


def analysis(observations):
    response = request(
        "POST",
        "/internal/v3/stock-analyses",
        json={
            "requestId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            "input": {
                "snapshotId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "symbol": "AAPL",
                "schemaVersion": "1",
                "collectedAt": AT,
                "observations": observations,
            },
        },
    )
    assert response.status_code == 200
    return response.json()


def forecast_request(core):
    return {
        "requestId": "cccccccc-cccc-cccc-cccc-cccccccccccc",
        "schemaVersion": "1",
        "analysis": core,
        "evaluatedAt": AT,
        "modelVersion": "deterministic-v1",
        "contractVersion": "forecast-v1",
    }


def forecast_metric(body, name):
    return next(item for item in body["forecasts"] if item["name"] == name)


def test_forecast_is_deterministic_and_provenance_preserving():
    payload = forecast_request(analysis(complete_observations()))

    first = request("POST", "/internal/v4/stock-forecasts", json=payload)
    second = request("POST", "/internal/v4/stock-forecasts", json=payload)

    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json() == second.json()
    body = first.json()
    assert body["status"] == "COMPLETED"
    assert body["confidence"] == "1"
    assert forecast_metric(body, "forecast.d1_up_probability")["value"] == "0.4481578947"
    assert forecast_metric(body, "forecast.d5_expected_return")["value"] == "0.012748538"
    assert forecast_metric(body, "forecast.d20_expected_return")["value"] == "-0.0422222222"
    assert forecast_metric(body, "forecast.expected_max_loss")["value"] == "-0.05634"
    assert {
        item["provider"] for item in forecast_metric(body, "forecast.d5_expected_return")["provenance"]
    } == {"FMP", "FRED"}
    assert "explain" not in body


def test_missing_input_degrades_only_dependent_forecasts():
    observations = [item for item in complete_observations() if item["field"] != "technical.rsi14"]
    body = request(
        "POST", "/internal/v4/stock-forecasts", json=forecast_request(analysis(observations))
    ).json()

    assert body["status"] == "DEGRADED"
    assert forecast_metric(body, "forecast.d1_up_probability")["value"] is None
    assert forecast_metric(body, "forecast.d5_expected_return")["value"] is not None
    assert forecast_metric(body, "forecast.d20_expected_return")["value"] is not None
    assert body["confidence"] == "0"


def test_probability_out_of_range_is_missing_without_clamping():
    observations = complete_observations()
    observations[9] = observation("quote.price", "2000", provider="FMP")
    body = request(
        "POST", "/internal/v4/stock-forecasts", json=forecast_request(analysis(observations))
    ).json()

    metric = forecast_metric(body, "forecast.d1_up_probability")
    assert metric["value"] is None
    assert "PROBABILITY_OUT_OF_RANGE:forecast.d1_up_probability" in metric["missingData"]


def test_inconsistent_or_stale_series_is_not_repaired():
    observations = complete_observations()
    observations[9] = observation(
        "quote.price", "200", provider="FMP", as_of="2026-07-01T20:00:00Z"
    )
    body = request(
        "POST", "/internal/v4/stock-forecasts", json=forecast_request(analysis(observations))
    ).json()

    assert body["status"] == "DEGRADED"
    assert forecast_metric(body, "forecast.d1_up_probability")["value"] is None
    assert any(
        reason.startswith(("STALE_DATA:", "TIME_SERIES_INCONSISTENT:"))
        for reason in forecast_metric(body, "forecast.d1_up_probability")["missingData"]
    )


def test_pinned_v4_degraded_fixture():
    root = Path(__file__).parents[2]
    payload = json.loads(
        (root / "contracts/analysis/v4/stock-forecast-core-request.json").read_text()
    )
    expected = json.loads(
        (root / "contracts/analysis/v4/stock-forecast-core-response.json").read_text()
    )

    response = request("POST", "/internal/v4/stock-forecasts", json=payload)

    assert response.status_code == 200
    assert response.json() == expected
