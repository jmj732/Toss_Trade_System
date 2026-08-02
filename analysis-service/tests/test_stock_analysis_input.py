import asyncio
import json
from pathlib import Path

from httpx import ASGITransport, AsyncClient

from app.main import app


ROOT = Path(__file__).parents[2]
CONTRACTS = ROOT / "contracts" / "analysis" / "v2"


async def request(method: str, path: str, **kwargs):
    async with AsyncClient(
        transport=ASGITransport(app=app),
        base_url="http://analysis.test",
    ) as client:
        return await client.request(method, path, **kwargs)


def test_stock_analysis_input_matches_canonical_contract() -> None:
    payload = json.loads((CONTRACTS / "stock-analysis-input-request.json").read_text())
    expected = json.loads((CONTRACTS / "stock-analysis-input-response.json").read_text())

    response = asyncio.run(
        request("POST", "/internal/v2/stock-analysis-inputs", json=payload)
    )

    assert response.status_code == 200
    assert response.json() == expected


def test_stock_analysis_response_has_no_forecast_or_explain_fields() -> None:
    payload = json.loads((CONTRACTS / "stock-analysis-input-request.json").read_text())

    response = asyncio.run(
        request("POST", "/internal/v2/stock-analysis-inputs", json=payload)
    )

    assert response.status_code == 200
    assert "forecast" not in response.json()
    assert "explain" not in response.json()
