import json
import logging
import re
import time
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_EVEN
from enum import Enum
from typing import Annotated, Any, Literal
from uuid import UUID, uuid4

from fastapi import FastAPI, Request
from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


RATIO_SCALE = Decimal("0.0000000001")
CORE_SCALE = Decimal("0.0000000001")
CORRELATION_HEADER = "X-Correlation-Id"
CORRELATION_ID = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
    r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)
LOG = logging.getLogger("trade.analysis")
Money = Annotated[Decimal, Field(ge=0, allow_inf_nan=False)]
SignedMoney = Annotated[Decimal, Field(allow_inf_nan=False)]


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class Currency(str, Enum):
    KRW = "KRW"
    USD = "USD"


class Quality(ContractModel):
    stale: bool
    partial: bool
    unknown_fields: list[str]


class PositionInput(ContractModel):
    symbol: Annotated[str, Field(min_length=1, max_length=30)]
    currency: Currency
    market_value: Money
    profit_loss: SignedMoney | None


class PortfolioAnalysisRequest(ContractModel):
    request_id: UUID
    schema_version: Literal["1"]
    as_of: datetime
    quality: Quality
    positions: list[PositionInput]


class PositionAnalysis(PositionInput):
    weight: Decimal


class CurrencyTotal(ContractModel):
    currency: Currency
    market_value: Decimal
    profit_loss: Decimal | None
    concentration: Decimal


class Status(str, Enum):
    COMPLETED = "COMPLETED"
    DEGRADED = "DEGRADED"


class PortfolioAnalysisResponse(ContractModel):
    request_id: UUID
    schema_version: Literal["1"]
    as_of: datetime
    status: Status
    quality: Quality
    positions: list[PositionAnalysis]
    currency_totals: list[CurrencyTotal]


ProviderId = Literal[
    "TOSS",
    "SEC",
    "FRED",
    "BLS",
    "BEA",
    "FED",
    "FMP",
    "FINNHUB",
    "POLYGON",
    "TWELVE_DATA",
]


class StockAnalysisObservation(ContractModel):
    field: Annotated[str, Field(min_length=1, max_length=120)]
    value: Any | None
    unit: str | None
    period: str | None
    identifier: str | None
    provider: ProviderId
    as_of: datetime | None
    collected_at: datetime
    missing_data: list[str]

    @model_validator(mode="after")
    def require_missing_data_for_null(self) -> "StockAnalysisObservation":
        if not self.missing_data and (self.value is None or self.as_of is None):
            raise ValueError("null value or asOf requires missingData")
        return self


class StockAnalysisInput(ContractModel):
    snapshot_id: UUID
    symbol: Annotated[str, Field(min_length=1, max_length=32)]
    schema_version: Literal["1"]
    collected_at: datetime
    observations: list[StockAnalysisObservation]


class StockAnalysisRequest(ContractModel):
    request_id: UUID
    input: StockAnalysisInput


class StockAnalysisResponse(ContractModel):
    request_id: UUID
    schema_version: Literal["1"]
    input_snapshot_id: UUID
    symbol: str
    status: Status
    missing_data: list[str]
    observations: list[StockAnalysisObservation]


class AnalysisProvenance(ContractModel):
    provider: ProviderId
    field: str
    as_of: datetime | None
    collected_at: datetime


class AnalysisMetric(ContractModel):
    name: str
    value: Any | None
    unit: str
    as_of: datetime | None
    provenance: list[AnalysisProvenance]
    missing_data: list[str]

    @model_validator(mode="after")
    def require_missing_data_for_null(self) -> "AnalysisMetric":
        if self.missing_data and (self.value is not None or self.as_of is not None):
            raise ValueError("missing metric must have null value and asOf")
        if not self.missing_data and (
            self.value is None
            or self.as_of is None
            or any(provenance.as_of is None for provenance in self.provenance)
        ):
            raise ValueError("null metric value or asOf requires missingData")
        return self


class AnalyzerResult(ContractModel):
    analyzer: Literal["fundamental", "valuation", "technical", "marketRegime"]
    confidence: Decimal
    missing_data: list[str]
    metrics: list[AnalysisMetric]


class DecisionAction(str, Enum):
    BUY = "BUY"
    ADD = "ADD"
    HOLD = "HOLD"
    REDUCE = "REDUCE"
    SELL = "SELL"
    WAIT = "WAIT"


class DecisionContribution(str, Enum):
    POSITIVE = "POSITIVE"
    NEGATIVE = "NEGATIVE"
    NEUTRAL = "NEUTRAL"


class DecisionBasis(ContractModel):
    metric: str
    value: Decimal | None
    contribution: DecisionContribution


class Decision(ContractModel):
    action: DecisionAction
    confidence: Decimal | None
    rule_version: str
    basis: list[DecisionBasis]
    missing_data: list[str]


class PositionPlan(ContractModel):
    entry: Decimal | None
    add: Decimal | None
    stop: Decimal | None
    target1: Decimal | None
    target2: Decimal | None
    risk_reward: Decimal | None
    max_loss_per_share: Decimal | None
    invalidation: str | None
    rule_version: str
    currency: str | None
    basis_price: Decimal | None
    missing_data: list[str]


class StockAnalysisCoreResponse(ContractModel):
    request_id: UUID
    schema_version: Literal["1"]
    input_snapshot_id: UUID
    symbol: str
    as_of: datetime
    status: Status
    missing_data: list[str]
    observations: list[StockAnalysisObservation]
    analyzers: list[AnalyzerResult]
    decision: Decision | None = None
    position_plan: PositionPlan | None = None


DECISION_RULE_VERSION = "decision-rule-v1"
POSITION_PLAN_RULE_VERSION = "position-plan-v1"

# 연율 변동성 -> 20 거래일 변동폭 환산 계수. sqrt(20/252) ~= 0.2817.
# forecast.expected_max_loss 가 이미 쓰는 계수와 동일하게 유지한다(새 계수를 만들지 않는다).
HORIZON_SCALE = Decimal("0.2817")

# 판단 신호. (지표, 가중치, 필수 여부, 점수 함수)
# 점수 함수는 -1 / 0 / +1 만 낸다. 어떤 신호도 값을 예측하지 않고, 임계값 비교만 한다.
# 임계값 근거는 각 항목 주석에 남긴다. 경계값은 "더 결정적인" 쪽에 귀속시킨다.
DECISION_SIGNALS: tuple[tuple[str, Decimal, bool, Any], ...] = (
    # 20일선 대비 위치. +-2% 는 일간 노이즈 대역으로 보고 중립 처리.
    ("technical.price_vs_sma20", Decimal("0.15"), True,
     lambda value: 1 if value >= Decimal("0.02") else -1 if value <= Decimal("-0.02") else 0),
    # 50일선 대비 위치. 중기 추세이므로 노이즈 대역을 20일선의 2.5배(+-5%)로 잡는다.
    ("technical.price_vs_sma50", Decimal("0.10"), False,
     lambda value: 1 if value >= Decimal("0.05") else -1 if value <= Decimal("-0.05") else 0),
    # 20일선/50일선 이격. +-1% 미만은 교차 직전/직후 구간으로 보고 중립.
    ("technical.sma_trend", Decimal("0.15"), True,
     lambda value: 1 if value >= Decimal("0.01") else -1 if value <= Decimal("-0.01") else 0),
    # RSI14. Wilder 원저의 70/30 경계. 과매수는 신규 진입에 불리(-1), 과매도는 유리(+1).
    ("technical.rsi14", Decimal("0.15"), True,
     lambda value: -1 if value >= Decimal("70") else 1 if value <= Decimal("30") else 0),
    # 연율 변동성. 25% 이하는 저위험, 50% 이상은 고위험으로 본다.
    ("technical.volatility20", Decimal("0.10"), True,
     lambda value: 1 if value <= Decimal("0.25") else -1 if value >= Decimal("0.50") else 0),
    # 순이익률 10% 이상 우량, 0 이하 적자.
    ("fundamental.profit_margin", Decimal("0.05"), False,
     lambda value: 1 if value >= Decimal("0.10") else -1 if value <= Decimal("0") else 0),
    # ROE 15% 이상 우량, 0 이하 자본 훼손.
    ("fundamental.roe", Decimal("0.05"), False,
     lambda value: 1 if value >= Decimal("0.15") else -1 if value <= Decimal("0") else 0),
    # 부채비율(총부채/자본) 1배 이하 보수적, 2배 이상 과다.
    ("fundamental.debt_to_equity", Decimal("0.05"), False,
     lambda value: 1 if value <= Decimal("1") else -1 if value >= Decimal("2") else 0),
    # 영업현금흐름률 15% 이상 우량, 0 이하 현금 유출.
    ("fundamental.operating_cash_flow_margin", Decimal("0.05"), False,
     lambda value: 1 if value >= Decimal("0.15") else -1 if value <= Decimal("0") else 0),
    # PER 15배 이하 저평가, 40배 이상 고평가. 0 이하(적자)는 밸류에이션 근거 없음 -> 부정.
    ("valuation.pe", Decimal("0.05"), False,
     lambda value: -1 if value <= Decimal("0")
     else 1 if value <= Decimal("15") else -1 if value >= Decimal("40") else 0),
    # PBR 1.5배 이하 저평가, 5배 이상 고평가. 0 이하(자본잠식)는 부정.
    ("valuation.price_to_book", Decimal("0.05"), False,
     lambda value: -1 if value <= Decimal("0")
     else 1 if value <= Decimal("1.5") else -1 if value >= Decimal("5") else 0),
    # PSR 2배 이하 저평가, 10배 이상 고평가. 0 이하는 부정.
    ("valuation.price_to_sales", Decimal("0.05"), False,
     lambda value: -1 if value <= Decimal("0")
     else 1 if value <= Decimal("2") else -1 if value >= Decimal("10") else 0),
    # FCF 수익률 5% 이상 우량, 0 이하 현금 유출.
    ("valuation.fcf_yield", Decimal("0.05"), False,
     lambda value: 1 if value >= Decimal("0.05") else -1 if value <= Decimal("0") else 0),
    # VIX 20/30 경계는 marketRegime.state 가 이미 쓰는 경계와 동일하게 유지한다.
    ("marketRegime.vix", Decimal("0.05"), False,
     lambda value: 1 if value <= Decimal("20") else -1 if value >= Decimal("30") else 0),
    # S&P500 20일 수익률. +-2% 미만은 방향 없음으로 본다.
    ("marketRegime.sp500Return20d", Decimal("0.05"), False,
     lambda value: 1 if value >= Decimal("0.02") else -1 if value <= Decimal("-0.02") else 0),
)
DECISION_REQUIRED = tuple(name for name, _, required, _ in DECISION_SIGNALS if required)

# 정규화 점수(-1..+1) 구간. 경계값은 더 결정적인 쪽(BUY/ADD/REDUCE/SELL)에 귀속.
ACTION_BUY_SCORE = Decimal("0.5")
ACTION_ADD_SCORE = Decimal("0.2")
ACTION_REDUCE_SCORE = Decimal("-0.2")
ACTION_SELL_SCORE = Decimal("-0.5")

# 포지션 계획 계수. 모두 20 거래일 변동폭(sigma20) 배수로 표현한다.
PLAN_WIDE_STOP_SIGMA = Decimal("1.5")     # 손절 허용 최대 폭
PLAN_TIGHT_STOP_SIGMA = Decimal("0.5")    # 손절 허용 최소 폭(노이즈 손절 방지)
PLAN_SMA20_BUFFER_SIGMA = Decimal("0.25")  # 20일선 아래 완충 구간
PLAN_TARGET1_SIGMA = Decimal("2")
PLAN_TARGET2_SIGMA = Decimal("4")
PLAN_ADD_FRACTION = Decimal("0.5")        # 진입가와 손절가 사이 중간 지점에서 분할 매수
PLAN_CURRENCIES = ("KRW", "USD")

FORECAST_METRIC_ORDER = [
    "forecast.d1_up_probability",
    "forecast.d5_expected_return",
    "forecast.d20_expected_return",
    "forecast.expected_max_loss",
]
FORECAST_FRESHNESS = timedelta(days=1)


class StockForecastRequest(ContractModel):
    request_id: UUID
    schema_version: Literal["1"]
    analysis: StockAnalysisCoreResponse
    evaluated_at: datetime
    model_version: Annotated[str, Field(min_length=1, max_length=50)]
    contract_version: Annotated[str, Field(min_length=1, max_length=50)]


class ForecastMetric(ContractModel):
    name: str
    value: str | None
    unit: str
    as_of: datetime | None
    provenance: list[AnalysisProvenance]
    missing_data: list[str]

    @model_validator(mode="after")
    def require_consistent_missing_shape(self) -> "ForecastMetric":
        if self.missing_data and (self.value is not None or self.as_of is not None):
            raise ValueError("missing forecast metric must have null value and asOf")
        if not self.missing_data and (
            self.value is None
            or self.as_of is None
            or any(item.as_of is None for item in self.provenance)
        ):
            raise ValueError("complete forecast metric requires value, asOf and provenance")
        return self


class StockForecastCoreResponse(ContractModel):
    request_id: UUID
    schema_version: Literal["1"]
    input_snapshot_id: UUID
    symbol: str
    as_of: datetime
    evaluated_at: datetime
    status: Status
    missing_data: list[str]
    confidence: str
    model_version: str
    contract_version: str
    forecasts: list[ForecastMetric]


app = FastAPI(title="Portfolio Analysis Service", version="1")


@app.middleware("http")
async def correlate(request: Request, call_next):
    candidate = request.headers.get(CORRELATION_HEADER)
    correlation_id = candidate if candidate and CORRELATION_ID.fullmatch(candidate) else str(uuid4())
    started = time.perf_counter()
    outcome = "failure"
    try:
        response = await call_next(request)
        outcome = "success" if response.status_code < 400 else "failure"
        response.headers[CORRELATION_HEADER] = correlation_id
        return response
    finally:
        operation = (
            "analysis"
            if request.url.path.endswith("/portfolio-analyses")
            else "stock-analysis-input"
            if request.url.path.endswith(("/stock-analysis-inputs", "/stock-analyses"))
            else "stock-forecast"
            if request.url.path.endswith("/stock-forecasts")
            else "request"
        )
        LOG.info(
            json.dumps(
                {
                    "correlationId": correlation_id,
                    "operation": operation,
                    "outcome": outcome,
                    "durationMs": round((time.perf_counter() - started) * 1000),
                },
                separators=(",", ":"),
            )
        )


@app.get("/internal/v1/health")
def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/internal/v1/ready")
def ready() -> dict[str, str]:
    return {"status": "READY"}


@app.post(
    "/internal/v1/portfolio-analyses",
    response_model=PortfolioAnalysisResponse,
)
def analyze_portfolio(request: PortfolioAnalysisRequest) -> PortfolioAnalysisResponse:
    totals = {
        currency: sum(
            (position.market_value for position in request.positions if position.currency == currency),
            Decimal(0),
        )
        for currency in sorted({position.currency for position in request.positions}, key=lambda item: item.value)
    }
    positions = [
        PositionAnalysis(
            **position.model_dump(),
            weight=ratio(position.market_value, totals[position.currency]),
        )
        for position in request.positions
    ]
    currency_totals = [
        CurrencyTotal(
            currency=currency,
            market_value=market_value,
            profit_loss=profit_loss(request.positions, currency),
            concentration=max(
                (position.weight for position in positions if position.currency == currency),
                default=Decimal(0),
            ),
        )
        for currency, market_value in totals.items()
    ]
    degraded = (
        request.quality.stale
        or request.quality.partial
        or bool(request.quality.unknown_fields)
    )
    return PortfolioAnalysisResponse(
        request_id=request.request_id,
        schema_version=request.schema_version,
        as_of=request.as_of,
        status=Status.DEGRADED if degraded else Status.COMPLETED,
        quality=request.quality,
        positions=positions,
        currency_totals=currency_totals,
    )


@app.post(
    "/internal/v2/stock-analysis-inputs",
    response_model=StockAnalysisResponse,
)
def analyze_stock_input(request: StockAnalysisRequest) -> StockAnalysisResponse:
    missing_data = [
        f"{observation.provider}:{observation.field}:{reason}"
        for observation in request.input.observations
        for reason in observation.missing_data
    ]
    degraded = not request.input.observations or bool(missing_data)
    return StockAnalysisResponse(
        request_id=request.request_id,
        schema_version=request.input.schema_version,
        input_snapshot_id=request.input.snapshot_id,
        symbol=request.input.symbol,
        status=Status.DEGRADED if degraded else Status.COMPLETED,
        missing_data=missing_data,
        observations=request.input.observations,
    )


@app.post(
    "/internal/v3/stock-analyses",
    response_model=StockAnalysisCoreResponse,
)
def analyze_stock_core(request: StockAnalysisRequest) -> StockAnalysisCoreResponse:
    indexed = _index_observations(request.input.observations)
    analyzers = [
        _fundamental(indexed, request.input.collected_at),
        _valuation(indexed, request.input.collected_at),
        _technical(indexed, request.input.collected_at),
        _market_regime(indexed, request.input.collected_at),
    ]
    input_missing = [
        f"{observation.provider}:{observation.field}:{reason}"
        for observation in request.input.observations
        for reason in observation.missing_data
    ]
    analyzer_missing = [
        f"{result.analyzer}:{reason}"
        for result in analyzers
        for reason in result.missing_data
    ]
    missing_data = _stable_unique(input_missing + analyzer_missing)
    return StockAnalysisCoreResponse(
        request_id=request.request_id,
        schema_version=request.input.schema_version,
        input_snapshot_id=request.input.snapshot_id,
        symbol=request.input.symbol,
        as_of=request.input.collected_at,
        status=Status.DEGRADED if missing_data else Status.COMPLETED,
        missing_data=missing_data,
        observations=request.input.observations,
        analyzers=analyzers,
        decision=_decision(analyzers),
        position_plan=_position_plan(indexed, analyzers),
    )


@app.post(
    "/internal/v4/stock-forecasts",
    response_model=StockForecastCoreResponse,
)
def forecast_stock(request: StockForecastRequest) -> StockForecastCoreResponse:
    metrics = _forecast_metrics(request.analysis, request.evaluated_at)
    missing_data = _stable_unique(
        reason for metric in metrics for reason in metric.missing_data
    )
    confidence = min(
        (Decimal("0") if metric.missing_data else _metric_confidence(
            metric, request.analysis
        ) for metric in metrics),
        default=Decimal("0"),
    )
    return StockForecastCoreResponse(
        request_id=request.request_id,
        schema_version=request.schema_version,
        input_snapshot_id=request.analysis.input_snapshot_id,
        symbol=request.analysis.symbol,
        as_of=request.analysis.as_of,
        evaluated_at=request.evaluated_at,
        status=Status.DEGRADED if missing_data else Status.COMPLETED,
        missing_data=missing_data,
        confidence=_decimal_text(confidence),
        model_version=request.model_version,
        contract_version=request.contract_version,
        forecasts=metrics,
    )


def _decision_inputs(analyzers: list[AnalyzerResult]) -> dict[str, Decimal]:
    """이미 계산된 analyzer metric 중 판단에 쓸 수 있는 수치만 뽑는다.

    metric 이 missingData 를 달고 있거나, 값이 없거나, 수치가 아니면 판단 입력에서 제외한다.
    새 데이터를 만들지 않고 기존 missingData 규율을 그대로 따른다.
    """
    values: dict[str, Decimal] = {}
    for name, candidates in _index_metrics(analyzers).items():
        if len(candidates) != 1:
            continue
        metric = candidates[0]
        if metric.missing_data or metric.value is None:
            continue
        try:
            value = Decimal(str(metric.value))
        except ArithmeticError:
            continue
        if value.is_finite():
            values[name] = value
    return values


def _decision_action(score: Decimal) -> DecisionAction:
    if score >= ACTION_BUY_SCORE:
        return DecisionAction.BUY
    if score >= ACTION_ADD_SCORE:
        return DecisionAction.ADD
    if score > ACTION_REDUCE_SCORE:
        return DecisionAction.HOLD
    if score > ACTION_SELL_SCORE:
        return DecisionAction.REDUCE
    return DecisionAction.SELL


def _decision(analyzers: list[AnalyzerResult]) -> Decision | None:
    values = _decision_inputs(analyzers)
    basis: list[DecisionBasis] = []
    missing: list[str] = []
    weighted = Decimal("0")
    total_weight = Decimal("0")
    available = 0
    for name, weight, required, score in DECISION_SIGNALS:
        value = values.get(name)
        if value is None:
            basis.append(DecisionBasis(
                metric=name, value=None, contribution=DecisionContribution.NEUTRAL))
            missing.append(
                f"DECISION_REQUIRED_INPUT_MISSING:{name}"
                if required
                else f"DECISION_INPUT_MISSING:{name}"
            )
            continue
        available += 1
        signal = score(value)
        weighted += weight * Decimal(signal)
        total_weight += weight
        basis.append(DecisionBasis(
            metric=name,
            value=value,
            contribution=DecisionContribution.POSITIVE
            if signal > 0
            else DecisionContribution.NEGATIVE
            if signal < 0
            else DecisionContribution.NEUTRAL,
        ))
    if not any(name in values for name in DECISION_REQUIRED):
        # 필수 지표가 하나도 없다 = 판단 근거가 없다. HOLD 같은 기본값으로 위조하지 않는다.
        # 사유는 응답 최상위 missingData 의 FIELD_MISSING 항목이 이미 담고 있다.
        return None
    action = (
        DecisionAction.WAIT
        if any(name not in values for name in DECISION_REQUIRED)
        else _decision_action((weighted / total_weight).quantize(
            CORE_SCALE, rounding=ROUND_HALF_EVEN))
    )
    confidence = (Decimal(available) / Decimal(len(DECISION_SIGNALS))).quantize(
        CORE_SCALE, rounding=ROUND_HALF_EVEN
    )
    return Decision(
        action=action,
        confidence=_decimal_text(confidence),
        rule_version=DECISION_RULE_VERSION,
        basis=basis,
        missing_data=_stable_unique(missing),
    )


def _plan_currency(indexed) -> str | None:
    matches = indexed.get("quote.price", [])
    if len(matches) != 1 or not matches[0].unit:
        return None
    unit = matches[0].unit.upper()
    return unit if unit in PLAN_CURRENCIES else None


def _position_plan(indexed, analyzers: list[AnalyzerResult]) -> PositionPlan | None:
    price, _, _ = _resolve(indexed, "quote.price")
    sigma = _decision_inputs(analyzers).get("technical.volatility20")
    if price is None or sigma is None:
        # 기준가와 변동성이 둘 다 있어야만 계획을 만든다. 사유는 최상위 missingData 에 있다.
        return None
    currency = _plan_currency(indexed)
    missing = [] if currency else ["MISSING_CURRENCY:quote.price"]
    sma20, _, _ = _resolve(indexed, "technical.sma20")
    if sma20 is None or sma20 <= 0:
        sma20 = None
        missing.append("PLAN_STRUCTURE_STOP_MISSING:technical.sma20")
    sigma20 = sigma * HORIZON_SCALE
    if price <= 0:
        missing.append("PLAN_INPUT_OUT_OF_RANGE:quote.price")
    if sigma20 <= 0 or PLAN_WIDE_STOP_SIGMA * sigma20 >= 1:
        missing.append("PLAN_INPUT_OUT_OF_RANGE:technical.volatility20")
    if not any(reason.startswith("PLAN_INPUT_OUT_OF_RANGE:") for reason in missing):
        wide_stop = price * (1 - PLAN_WIDE_STOP_SIGMA * sigma20)
        tight_stop = price * (1 - PLAN_TIGHT_STOP_SIGMA * sigma20)
        # 계약에 실리는 정밀도로 먼저 고정한 뒤 나머지를 유도한다.
        # 그래야 maxLossPerShare / riskReward 가 게시된 entry·stop 과 정확히 맞아떨어진다.
        entry = _quantized(price)
        stop = _quantized(
            min(max(sma20 * (1 - PLAN_SMA20_BUFFER_SIGMA * sigma20), wide_stop), tight_stop)
            if sma20 is not None
            else wide_stop
        )
        if stop <= 0 or stop >= entry:
            missing.append("PLAN_INPUT_OUT_OF_RANGE:technical.volatility20")
    if any(reason.startswith("PLAN_INPUT_OUT_OF_RANGE:") for reason in missing):
        return PositionPlan(
            entry=None, add=None, stop=None, target1=None, target2=None,
            risk_reward=None, max_loss_per_share=None, invalidation=None,
            rule_version=POSITION_PLAN_RULE_VERSION, currency=currency,
            basis_price=_decimal_text(price), missing_data=_stable_unique(missing),
        )
    target1 = _quantized(price * (1 + PLAN_TARGET1_SIGMA * sigma20))
    max_loss = entry - stop
    stop_text = _decimal_text(stop)
    return PositionPlan(
        entry=_decimal_text(entry),
        add=_decimal_text(_quantized(entry - max_loss * PLAN_ADD_FRACTION)),
        stop=stop_text,
        target1=_decimal_text(target1),
        target2=_decimal_text(_quantized(price * (1 + PLAN_TARGET2_SIGMA * sigma20))),
        risk_reward=_decimal_text((target1 - entry) / max_loss),
        max_loss_per_share=_decimal_text(max_loss),
        invalidation=(
            f"종가가 {stop_text} {currency} 아래로 마감하면 계획 무효"
            if currency
            else f"종가가 {stop_text} 아래로 마감하면 계획 무효"
        ),
        rule_version=POSITION_PLAN_RULE_VERSION,
        currency=currency,
        basis_price=_decimal_text(entry),
        missing_data=_stable_unique(missing),
    )


def _index_observations(observations: list[StockAnalysisObservation]):
    indexed: dict[str, list[StockAnalysisObservation]] = {}
    for observation in observations:
        indexed.setdefault(observation.field, []).append(observation)
    return indexed


def _forecast_metrics(analysis: StockAnalysisCoreResponse, evaluated_at: datetime):
    indexed = _index_metrics(analysis.analyzers)
    return [
        _forecast_metric(
            "forecast.d1_up_probability",
            "probability",
            [
                "technical.price_vs_sma20",
                "technical.rsi14",
                "technical.volatility20",
                "marketRegime.sp500Return20d",
            ],
            indexed,
            evaluated_at,
            lambda values: Decimal("0.5")
            + values[0] / Decimal("4")
            + (values[1] - Decimal("50")) / Decimal("200")
            + values[3] / Decimal("4")
            - values[2] / Decimal("2"),
            probability=True,
        ),
        _forecast_metric(
            "forecast.d5_expected_return",
            "ratio",
            [
                "technical.price_vs_sma20",
                "technical.price_vs_sma50",
                "technical.volatility20",
                "marketRegime.sp500Return20d",
            ],
            indexed,
            evaluated_at,
            lambda values: values[0] / Decimal("5")
            + values[1] / Decimal("5")
            + values[3] / Decimal("2")
            - values[2] / Decimal("5"),
        ),
        _forecast_metric(
            "forecast.d20_expected_return",
            "ratio",
            [
                "fundamental.profit_margin",
                "fundamental.roe",
                "valuation.fcf_yield",
                "technical.sma_trend",
                "marketRegime.sp500Return20d",
                "technical.volatility20",
            ],
            indexed,
            evaluated_at,
            lambda values: values[0] / Decimal("5")
            + values[1] / Decimal("10")
            + values[2]
            + values[3] / Decimal("2")
            + values[4]
            - values[5],
        ),
        _forecast_metric(
            "forecast.expected_max_loss",
            "ratio",
            ["technical.volatility20"],
            indexed,
            evaluated_at,
            lambda values: -values[0] * Decimal("0.2817"),
        ),
    ]


def _index_metrics(analyzers: list[AnalyzerResult]):
    indexed: dict[str, list[AnalysisMetric]] = {}
    for analyzer in analyzers:
        for metric in analyzer.metrics:
            indexed.setdefault(metric.name, []).append(metric)
    return indexed


def _metric_confidence(metric: ForecastMetric, analysis: StockAnalysisCoreResponse):
    source_to_analyzer = {"quote": "technical", "macro": "marketRegime"}
    analyzer_confidence = {
        analyzer.analyzer: Decimal(str(analyzer.confidence))
        for analyzer in analysis.analyzers
    }
    return min(
        (
            analyzer_confidence.get(
                source_to_analyzer.get(
                    metric_name.split(".", 1)[0], metric_name.split(".", 1)[0]
                ),
                Decimal("0"),
            )
            for item in metric.provenance
            for metric_name in [item.field]
        ),
        default=Decimal("0"),
    )


def _forecast_metric(
    name,
    unit,
    dependencies,
    indexed,
    evaluated_at,
    operation,
    probability=False,
):
    matches = []
    provenance = []
    missing = []
    for dependency in dependencies:
        candidates = indexed.get(dependency, [])
        if len(candidates) != 1:
            missing.append(
                f"AMBIGUOUS_DUPLICATE_METRIC:{dependency}"
                if candidates
                else f"FORECAST_METRIC_MISSING:{dependency}"
            )
            provenance.extend(
                item for candidate in candidates for item in candidate.provenance
            )
            continue
        metric = candidates[0]
        matches.append(metric)
        provenance.extend(metric.provenance)
        if metric.missing_data:
            missing.extend(f"ANALYSIS_METRIC_MISSING:{dependency}:{reason}" for reason in metric.missing_data)
        elif metric.value is None:
            missing.append(f"FORECAST_METRIC_MISSING:{dependency}")
        elif metric.as_of is None:
            missing.append(f"MISSING_AS_OF:{dependency}")

    provenance = _unique_provenance(provenance)
    as_ofs = {
        item.as_of for metric in matches for item in metric.provenance if item.as_of is not None
    }
    if len(as_ofs) > 1:
        missing.append(f"TIME_SERIES_INCONSISTENT:{name}")
    if as_ofs:
        source_as_of = max(as_ofs)
        if source_as_of > evaluated_at or evaluated_at - source_as_of > FORECAST_FRESHNESS:
            missing.append(f"STALE_DATA:{name}")
    else:
        source_as_of = None

    if missing:
        return ForecastMetric(
            name=name,
            value=None,
            unit=unit,
            as_of=None,
            provenance=provenance,
            missing_data=_stable_unique(missing),
        )

    try:
        values = [Decimal(str(metric.value)) for metric in matches]
        if any(not value.is_finite() for value in values):
            raise ValueError("non-finite forecast input")
        value = operation(values)
        if not value.is_finite():
            raise ValueError("non-finite forecast output")
        if probability and not Decimal("0") <= value <= Decimal("1"):
            raise ValueError("probability out of range")
    except (ArithmeticError, ValueError):
        reason = (
            f"PROBABILITY_OUT_OF_RANGE:{name}"
            if probability
            else f"INVALID_FORECAST_VALUE:{name}"
        )
        return ForecastMetric(
            name=name,
            value=None,
            unit=unit,
            as_of=None,
            provenance=provenance,
            missing_data=[reason],
        )
    return ForecastMetric(
        name=name,
        value=_decimal_text(value),
        unit=unit,
        as_of=source_as_of,
        provenance=provenance,
        missing_data=[],
    )


def _unique_provenance(values):
    result = []
    seen = set()
    for value in values:
        key = (value.provider, value.field, value.as_of, value.collected_at)
        if key not in seen:
            seen.add(key)
            result.append(value)
    return result


def _provenance(observation: StockAnalysisObservation) -> AnalysisProvenance:
    return AnalysisProvenance(
        provider=observation.provider,
        field=observation.field,
        as_of=observation.as_of,
        collected_at=observation.collected_at,
    )


def _resolve(indexed, field: str):
    matches = indexed.get(field, [])
    references = [_provenance(observation) for observation in matches]
    if not matches:
        return None, references, [f"FIELD_MISSING:{field}"]
    if len(matches) != 1:
        return None, references, [f"AMBIGUOUS_DUPLICATE_FIELD:{field}"]
    observation = matches[0]
    if observation.missing_data:
        return None, references, [f"{reason}:{field}" for reason in observation.missing_data]
    if observation.value is None:
        return None, references, [f"MISSING_VALUE:{field}"]
    if observation.as_of is None:
        return None, references, [f"MISSING_AS_OF:{field}"]
    try:
        value = Decimal(str(observation.value))
        if not value.is_finite():
            raise ValueError("non-finite numeric value")
        return value, references, []
    except (ArithmeticError, ValueError):
        return None, references, [f"INVALID_NUMERIC_VALUE:{field}"]


def _metric(name, unit, basis_as_of, fields, indexed, operation=None):
    resolved = [_resolve(indexed, field) for field in fields]
    values = [item[0] for item in resolved]
    references = [reference for item in resolved for reference in item[1]]
    missing = [reason for item in resolved for reason in item[2]]
    if missing:
        return AnalysisMetric(
            name=name,
            value=None,
            unit=unit,
            as_of=None,
            provenance=references,
            missing_data=_stable_unique(missing),
        )
    try:
        value = values[0] if operation is None else operation(values)
    except ArithmeticError:
        return AnalysisMetric(
            name=name,
            value=None,
            unit=unit,
            as_of=None,
            provenance=references,
            missing_data=[f"DIVISION_BY_ZERO:{name}"],
        )
    return AnalysisMetric(
        name=name,
        value=_decimal_text(value)
        if operation is not None and isinstance(value, Decimal)
        else value,
        unit=unit,
        as_of=basis_as_of,
        provenance=references,
        missing_data=[],
    )


def _direct_metric(name, unit, field, indexed, basis_as_of):
    return _metric(name, unit, basis_as_of, [field], indexed)


def _result(name, metrics):
    missing = _stable_unique(reason for metric in metrics for reason in metric.missing_data)
    complete = sum(not metric.missing_data for metric in metrics)
    confidence = (Decimal(complete) / Decimal(len(metrics))).quantize(
        CORE_SCALE, rounding=ROUND_HALF_EVEN
    )
    return AnalyzerResult(
        analyzer=name,
        confidence=_decimal_text(confidence),
        missing_data=missing,
        metrics=metrics,
    )


def _fundamental(indexed, basis_as_of):
    return _result("fundamental", [
        _metric("fundamental.profit_margin", "ratio", basis_as_of,
                ["fundamental.net_income", "fundamental.revenue"], indexed,
                lambda values: values[0] / values[1]),
        _metric("fundamental.roe", "ratio", basis_as_of,
                ["fundamental.net_income", "fundamental.equity"], indexed,
                lambda values: values[0] / values[1]),
        _metric("fundamental.debt_to_equity", "ratio", basis_as_of,
                ["fundamental.total_liabilities", "fundamental.equity"], indexed,
                lambda values: values[0] / values[1]),
        _metric("fundamental.operating_cash_flow_margin", "ratio", basis_as_of,
                ["fundamental.operating_cash_flow", "fundamental.revenue"], indexed,
                lambda values: values[0] / values[1]),
    ])


def _valuation(indexed, basis_as_of):
    return _result("valuation", [
        _metric("valuation.pe", "multiple", basis_as_of,
                ["quote.price", "fundamental.eps"], indexed,
                lambda values: values[0] / values[1]),
        _metric("valuation.price_to_book", "multiple", basis_as_of,
                ["quote.price", "fundamental.book_value_per_share"], indexed,
                lambda values: values[0] / values[1]),
        _metric("valuation.price_to_sales", "multiple", basis_as_of,
                ["quote.price", "fundamental.revenue_per_share"], indexed,
                lambda values: values[0] / values[1]),
        _metric("valuation.fcf_yield", "ratio", basis_as_of,
                ["fundamental.free_cash_flow_per_share", "quote.price"], indexed,
                lambda values: values[0] / values[1]),
    ])


def _technical(indexed, basis_as_of):
    return _result("technical", [
        _metric("technical.price_vs_sma20", "ratio", basis_as_of,
                ["quote.price", "technical.sma20"], indexed,
                lambda values: values[0] / values[1] - 1),
        _metric("technical.price_vs_sma50", "ratio", basis_as_of,
                ["quote.price", "technical.sma50"], indexed,
                lambda values: values[0] / values[1] - 1),
        _metric("technical.sma_trend", "ratio", basis_as_of,
                ["technical.sma20", "technical.sma50"], indexed,
                lambda values: values[0] / values[1] - 1),
        _direct_metric("technical.rsi14", "ratio", "technical.rsi14", indexed, basis_as_of),
        _direct_metric("technical.volatility20", "ratio", "technical.volatility20", indexed, basis_as_of),
    ])


def _market_regime(indexed, basis_as_of):
    return _result("marketRegime", [
        _direct_metric("marketRegime.vix", "index", "macro.vix", indexed, basis_as_of),
        _direct_metric("marketRegime.sp500Return20d", "ratio", "macro.sp500_return20d", indexed, basis_as_of),
        _metric("marketRegime.state", "state", basis_as_of,
                ["macro.vix", "macro.sp500_return20d"], indexed,
                lambda values: (
                    "RISK_ON" if values[0] <= 20 and values[1] >= 0
                    else "RISK_OFF" if values[0] >= 30 and values[1] < 0
                    else "NEUTRAL"
                )),
    ])


def _quantized(value: Decimal) -> Decimal:
    return value.quantize(CORE_SCALE, rounding=ROUND_HALF_EVEN)


def _decimal_text(value: Decimal) -> str:
    quantized = value.quantize(CORE_SCALE, rounding=ROUND_HALF_EVEN)
    text = format(quantized.normalize(), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def _stable_unique(values):
    return list(dict.fromkeys(values))


def ratio(value: Decimal, total: Decimal) -> Decimal:
    if total == 0:
        return Decimal(0)
    return (value / total).quantize(RATIO_SCALE, rounding=ROUND_HALF_EVEN)


def profit_loss(positions: list[PositionInput], currency: Currency) -> Decimal | None:
    values = [
        position.profit_loss
        for position in positions
        if position.currency == currency
    ]
    if any(value is None for value in values):
        return None
    return sum(values, Decimal(0))
