import asyncio
import json
from decimal import Decimal
from pathlib import Path

import httpx

from app.main import app


AT = "2026-08-02T00:00:00Z"
AS_OF = "2026-08-01T20:00:00Z"
V5 = Path(__file__).parents[2] / "contracts" / "analysis" / "v5"


def request(method: str, path: str, **kwargs):
    async def call():
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://analysis.test"
        ) as client:
            return await client.request(method, path, **kwargs)

    return asyncio.run(call())


def observation(field, value, provider="SEC", unit=None):
    return {
        "field": field,
        "value": value,
        "unit": unit
        or ("ratio" if field.startswith(("fundamental.", "technical.", "macro.")) else "USD"),
        "period": None,
        "identifier": "AAPL",
        "provider": provider,
        "asOf": AS_OF,
        "collectedAt": AT,
        "missingData": [],
    }


def payload(observations):
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


def analyze(observations):
    response = request("POST", "/internal/v3/stock-analyses", json=payload(observations))
    assert response.status_code == 200
    return response.json()


def technical_only(price="200", sma20="190", sma50="180", rsi="55", volatility="0.2"):
    """필수 지표 4개를 모두 채우는 최소 관측 집합."""
    return [
        observation("quote.price", price, provider="FMP"),
        observation("technical.sma20", sma20, provider="FMP"),
        observation("technical.sma50", sma50, provider="FMP"),
        observation("technical.rsi14", rsi, provider="FMP"),
        observation("technical.volatility20", volatility, provider="FMP"),
    ]


def basis(body, metric):
    return next(item for item in body["decision"]["basis"] if item["metric"] == metric)


def test_missing_every_metric_yields_no_decision_and_no_plan():
    body = analyze([])

    assert body["decision"] is None
    assert body["positionPlan"] is None


def test_partial_required_inputs_yield_wait_not_a_forged_hold():
    body = analyze([
        observation("quote.price", "200", provider="FMP"),
        observation("technical.sma20", "190", provider="FMP"),
        observation("technical.volatility20", "0.2", provider="FMP"),
    ])

    decision = body["decision"]
    assert decision["action"] == "WAIT"
    assert decision["ruleVersion"] == "decision-rule-v1"
    assert "DECISION_REQUIRED_INPUT_MISSING:technical.sma_trend" in decision["missingData"]
    assert "DECISION_REQUIRED_INPUT_MISSING:technical.rsi14" in decision["missingData"]
    assert basis(body, "technical.rsi14") == {
        "metric": "technical.rsi14",
        "value": None,
        "contribution": "NEUTRAL",
    }
    assert basis(body, "technical.price_vs_sma20")["contribution"] == "POSITIVE"


def test_every_action_band_is_reachable_from_the_same_metric_set():
    # 필수 4개만 주면 정규화 분모는 0.55(0.15+0.15+0.15+0.10)로 고정된다.
    # sell:   sma20 -1, trend -1, rsi -1(과매수), vol -1  -> -0.55/0.55 = -1
    # reduce: sma20 -1, trend  0, rsi  0,         vol  0  -> -0.15/0.55 = -0.2727
    # hold:   sma20  0, trend  0, rsi  0,         vol  0  ->  0
    # add:    sma20 +1, trend  0, rsi  0,         vol  0  ->  0.15/0.55 = 0.2727
    # buy:    sma20 +1, trend +1, rsi  0,         vol +1  ->  0.40/0.55 = 0.7273
    cases = {
        "SELL": technical_only(price="80", sma20="100", sma50="120", rsi="70", volatility="0.6"),
        "REDUCE": technical_only(price="97", sma20="100", sma50="100", rsi="50", volatility="0.3"),
        "HOLD": technical_only(price="100", sma20="100", sma50="100", rsi="50", volatility="0.3"),
        "ADD": technical_only(price="103", sma20="100", sma50="100", rsi="50", volatility="0.3"),
        "BUY": technical_only(price="103", sma20="100", sma50="98", rsi="50", volatility="0.2"),
    }

    for expected, observations in cases.items():
        body = analyze(observations)
        assert body["decision"]["action"] == expected, expected


def test_action_band_boundaries_belong_to_the_decisive_side():
    # price_vs_sma20 는 +-0.02 를 포함해야 방향 신호가 된다.
    neutral = analyze(technical_only(price="101.9", sma20="100", sma50="100", rsi="50", volatility="0.3"))
    positive = analyze(technical_only(price="102", sma20="100", sma50="100", rsi="50", volatility="0.3"))
    negative = analyze(technical_only(price="98", sma20="100", sma50="100", rsi="50", volatility="0.3"))

    assert neutral["decision"]["action"] == "HOLD"
    assert positive["decision"]["action"] == "ADD"
    assert negative["decision"]["action"] == "REDUCE"


def test_rsi_boundaries_follow_the_documented_70_30_thresholds():
    overbought = analyze(technical_only(rsi="70"))
    inside = analyze(technical_only(rsi="69.9999999999"))
    oversold = analyze(technical_only(rsi="30"))

    assert basis(overbought, "technical.rsi14")["contribution"] == "NEGATIVE"
    assert basis(inside, "technical.rsi14")["contribution"] == "NEUTRAL"
    assert basis(oversold, "technical.rsi14")["contribution"] == "POSITIVE"


def test_volatility_boundaries_follow_the_documented_25_50_thresholds():
    calm = analyze(technical_only(volatility="0.25"))
    middle = analyze(technical_only(volatility="0.4"))
    wild = analyze(technical_only(volatility="0.5"))

    assert basis(calm, "technical.volatility20")["contribution"] == "POSITIVE"
    assert basis(middle, "technical.volatility20")["contribution"] == "NEUTRAL"
    assert basis(wild, "technical.volatility20")["contribution"] == "NEGATIVE"


def test_confidence_is_input_completeness_and_stays_within_zero_and_one():
    empty = analyze([])
    partial = analyze(technical_only())
    complete = analyze(json.loads(
        (V5 / "stock-analysis-core-complete-request.json").read_text()
    )["input"]["observations"])

    assert empty["decision"] is None
    # 5개(price_vs_sma20/price_vs_sma50/sma_trend/rsi14/volatility20) / 15개 신호
    assert partial["decision"]["confidence"] == "0.3333333333"
    assert complete["decision"]["confidence"] == "1"
    for body in (partial, complete):
        assert Decimal("0") <= Decimal(body["decision"]["confidence"]) <= Decimal("1")


def test_position_plan_needs_both_price_and_volatility():
    without_price = analyze([
        observation("technical.sma20", "190", provider="FMP"),
        observation("technical.volatility20", "0.2", provider="FMP"),
    ])
    without_volatility = analyze([
        observation("quote.price", "200", provider="FMP"),
        observation("technical.sma20", "190", provider="FMP"),
    ])
    both = analyze(technical_only())

    assert without_price["positionPlan"] is None
    assert without_volatility["positionPlan"] is None
    assert both["positionPlan"]["ruleVersion"] == "position-plan-v1"


def test_position_plan_levels_are_ordered_and_derived_from_the_basis_price():
    plan = analyze(technical_only())["positionPlan"]

    entry = Decimal(plan["entry"])
    stop = Decimal(plan["stop"])
    assert plan["basisPrice"] == "200"
    assert plan["currency"] == "USD"
    assert stop < Decimal(plan["add"]) < entry < Decimal(plan["target1"]) < Decimal(plan["target2"])
    assert Decimal(plan["maxLossPerShare"]) == entry - stop
    assert Decimal(plan["riskReward"]) > 0
    assert plan["invalidation"] == f"종가가 {plan['stop']} USD 아래로 마감하면 계획 무효"
    assert plan["missingData"] == []


def test_position_plan_reports_missing_structure_stop_and_currency_without_inventing_them():
    plan = analyze([
        observation("quote.price", "200", provider="FMP", unit="EUR"),
        observation("technical.volatility20", "0.2", provider="FMP"),
    ])["positionPlan"]

    assert plan["currency"] is None
    assert plan["invalidation"] == f"종가가 {plan['stop']} 아래로 마감하면 계획 무효"
    assert plan["missingData"] == [
        "MISSING_CURRENCY:quote.price",
        "PLAN_STRUCTURE_STOP_MISSING:technical.sma20",
    ]


def test_position_plan_refuses_to_price_out_of_range_volatility():
    plan = analyze([
        observation("quote.price", "200", provider="FMP"),
        observation("technical.sma20", "190", provider="FMP"),
        observation("technical.volatility20", "0", provider="FMP"),
    ])["positionPlan"]

    assert plan["stop"] is None
    assert plan["riskReward"] is None
    assert plan["invalidation"] is None
    assert plan["basisPrice"] == "200"
    assert "PLAN_INPUT_OUT_OF_RANGE:technical.volatility20" in plan["missingData"]

    # 손절폭이 계약 정밀도(1e-10) 아래로 붕괴하면 손익비를 만들 수 없다 -> 값을 지어내지 않는다.
    collapsed = analyze([
        observation("quote.price", "200", provider="FMP"),
        observation("technical.sma20", "190", provider="FMP"),
        observation("technical.volatility20", "0.00000000000001", provider="FMP"),
    ])["positionPlan"]

    assert collapsed["stop"] is None
    assert collapsed["riskReward"] is None
    assert "PLAN_INPUT_OUT_OF_RANGE:technical.volatility20" in collapsed["missingData"]


def test_decision_and_plan_are_deterministic_for_identical_input():
    observations = json.loads(
        (V5 / "stock-analysis-core-complete-request.json").read_text()
    )["input"]["observations"]

    first = analyze(observations)
    second = analyze(observations)

    assert first == second


def test_v5_fixtures_are_byte_for_byte_reproducible():
    for name in ("complete", "partial", "missing"):
        payload_json = json.loads((V5 / f"stock-analysis-core-{name}-request.json").read_text())
        expected = json.loads((V5 / f"stock-analysis-core-{name}-response.json").read_text())

        response = request("POST", "/internal/v3/stock-analyses", json=payload_json)

        assert response.status_code == 200
        assert response.json() == expected


def test_v3_fixture_response_shape_is_extended_not_changed():
    root = Path(__file__).parents[2]
    v3_request = json.loads(
        (root / "contracts/analysis/v3/stock-analysis-core-request.json").read_text()
    )
    v3_response = json.loads(
        (root / "contracts/analysis/v3/stock-analysis-core-response.json").read_text()
    )

    body = request("POST", "/internal/v3/stock-analyses", json=v3_request).json()

    # v5 는 v3 응답에 두 개의 nullable 필드만 더한다. 기존 키는 이름/타입/의미가 그대로다.
    assert set(body) - set(v3_response) == {"decision", "positionPlan"}
    for key in ("requestId", "schemaVersion", "inputSnapshotId", "symbol", "asOf",
                "status", "observations"):
        assert body[key] == v3_response[key]
    assert body["analyzers"] == v3_response["analyzers"]


def test_v4_forecast_still_accepts_an_analysis_without_the_new_fields():
    root = Path(__file__).parents[2]
    forecast_request = json.loads(
        (root / "contracts/analysis/v4/stock-forecast-core-request.json").read_text()
    )

    legacy = request("POST", "/internal/v4/stock-forecasts", json=forecast_request)
    forecast_request["analysis"]["decision"] = None
    forecast_request["analysis"]["positionPlan"] = None
    extended = request("POST", "/internal/v4/stock-forecasts", json=forecast_request)

    assert legacy.status_code == 200
    assert extended.status_code == 200
    assert legacy.json() == extended.json()
