import asyncio
import json
import logging
from pathlib import Path

from httpx import ASGITransport, AsyncClient

from app.main import app


ROOT = Path(__file__).parents[2]
CONTRACTS = ROOT / "contracts" / "analysis" / "v1"


async def request(method: str, path: str, **kwargs):
    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://analysis.test",
    ) as client:
        return await client.request(method, path, **kwargs)


def test_portfolio_analysis_matches_canonical_contract() -> None:
    payload = json.loads((CONTRACTS / "portfolio-analysis-request.json").read_text())
    expected = json.loads((CONTRACTS / "portfolio-analysis-response.json").read_text())

    response = asyncio.run(
        request("POST", "/internal/v1/portfolio-analyses", json=payload)
    )

    assert response.status_code == 200
    assert response.json() == expected


def test_complete_zero_value_portfolio_has_zero_concentration() -> None:
    response = asyncio.run(
        request(
            "POST",
            "/internal/v1/portfolio-analyses",
            json={
                "requestId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "schemaVersion": "1",
                "asOf": "2026-07-28T06:00:00Z",
                "quality": {"stale": False, "partial": False, "unknownFields": []},
                "positions": [
                    {
                        "symbol": "CASHLESS",
                        "currency": "USD",
                        "marketValue": "0",
                        "profitLoss": "0",
                    }
                ],
            },
        )
    )

    assert response.status_code == 200
    assert response.json()["status"] == "COMPLETED"
    assert response.json()["positions"][0]["weight"] == "0"
    assert response.json()["currencyTotals"] == [
        {
            "currency": "USD",
            "marketValue": "0",
            "profitLoss": "0",
            "concentration": "0",
        }
    ]


def test_negative_profit_loss_is_summed() -> None:
    response = asyncio.run(
        request(
            "POST",
            "/internal/v1/portfolio-analyses",
            json={
                "requestId": "cccccccc-cccc-cccc-cccc-cccccccccccc",
                "schemaVersion": "1",
                "asOf": "2026-07-28T06:00:00Z",
                "quality": {"stale": False, "partial": False, "unknownFields": []},
                "positions": [
                    {
                        "symbol": "LOSS",
                        "currency": "USD",
                        "marketValue": "100",
                        "profitLoss": "-25",
                    }
                ],
            },
        )
    )

    assert response.status_code == 200
    assert response.json()["currencyTotals"][0]["profitLoss"] == "-25"


def test_health_is_process_only() -> None:
    response = asyncio.run(request("GET", "/internal/v1/health"))

    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_readiness_reports_analysis_service_ready() -> None:
    response = asyncio.run(request("GET", "/internal/v1/ready"))

    assert response.status_code == 200
    assert response.json() == {"status": "READY"}


def test_correlation_id_is_echoed_and_completion_log_excludes_request_data(caplog) -> None:
    payload = json.loads((CONTRACTS / "portfolio-analysis-request.json").read_text())
    caplog.set_level(logging.INFO, logger="trade.analysis")

    response = asyncio.run(
        request(
            "POST",
            "/internal/v1/portfolio-analyses",
            json=payload,
            headers={
                "X-Correlation-Id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "Authorization": "Bearer top-secret",
                "X-Account-Number": "123-456-7890",
            },
        )
    )

    assert response.headers["X-Correlation-Id"] == "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    event = json.loads(caplog.records[-1].message)
    assert event["correlationId"] == "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    assert event["operation"] == "analysis"
    assert event["outcome"] == "success"
    assert "top-secret" not in caplog.text
    assert "123-456-7890" not in caplog.text


def test_invalid_correlation_id_is_replaced() -> None:
    response = asyncio.run(
        request(
            "GET",
            "/internal/v1/ready",
            headers={"X-Correlation-Id": "123-456-7890"},
        )
    )

    assert response.headers["X-Correlation-Id"]
    assert response.headers["X-Correlation-Id"] != "123-456-7890"
