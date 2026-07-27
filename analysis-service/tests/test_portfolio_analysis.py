import asyncio
import json
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
