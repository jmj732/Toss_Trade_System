"use client";

import { createElement as h } from "react";

import { formatAmount, formatDecimal, formatInstant, formatRatio, formatSignedAmount, UNKNOWN_TEXT } from "../lib/format.js";
import { MarketCandleChart } from "./market-candle-chart.js";

// 판단 라벨은 decision-center.js 의 DECISION_LABELS 와 동일한 어휘를 쓴다(두 표면이
// 서로 다른 말을 쓰지 않도록). decision-center.js 는 이 상수를 export 하지 않으므로
// (BC-2 판단 계약과 무관한 stock-analysis 쪽 decision.action 어휘라) 여기서 그대로 복제한다.
const DECISION_LABELS = { BUY: "매수", ADD: "추가 매수", HOLD: "보유", REDUCE: "축소", SELL: "매도", WAIT: "대기" };

const REASON_LABELS = {
  UNAUTHORIZED: "권한 확인 필요",
  PROVIDER_UNSUPPORTED: "지원되지 않는 제공자 데이터",
  PROVIDER_TIMEOUT: "제공자 시간 초과",
  PROVIDER_RATE_LIMITED: "제공자 호출 제한",
  PROVIDER_MALFORMED: "제공자 응답 형식 오류",
  PROVIDER_STALE: "제공자 데이터 만료",
  PROVIDER_UNSUPPORTED_MARKET_CAP_RANKING: "지원되지 않는 시가총액 랭킹",
  GEMINI_UPSTREAM_ERROR: "설명 제공자 오류",
  ERROR: "조회 오류"
};

const MISSING_DATA_LABELS = {
  "marketRegime:FIELD_MISSING:macro.vix": "시장 변동성 지표",
  GEMINI_UPSTREAM_ERROR: "설명 제공자 오류",
  PROVIDER_UNSUPPORTED: "지원되지 않는 제공자 데이터"
};

function readableCode(value) {
  return REASON_LABELS[value] ?? value;
}

function readableMissingData(value) {
  return MISSING_DATA_LABELS[value] ?? value;
}

function surfaceData(value) {
  return value?.data ?? value;
}

function surfaceMessage(value, loadingText) {
  if (!value) return loadingText;
  if (value.status === "UNAUTHORIZED" || value.unavailableReason === "UNAUTHORIZED") {
    return "권한 확인 필요";
  }
  if (value.status === "UNAVAILABLE" || value.unavailable) {
    const reason = value.unavailableReason ?? "PROVIDER_UNSUPPORTED";
    return reason.startsWith("PROVIDER_UNSUPPORTED")
      ? `지원되지 않음 (${readableCode(reason)})`
      : `조회 실패 (${readableCode(reason)})`;
  }
  if (value.status === "DEGRADED") return "부분 데이터만 확인할 수 있습니다";
  if (value.status === "ERROR") return `조회 실패 (${readableCode(value.unavailableReason ?? "ERROR")})`;
  return null;
}

// 상태 코드는 화이트리스트로만 한국어 라벨에 매핑한다. 미등록 값은 원문을 함께 노출한다.
const STATE_LABELS = {
  IDLE: "조회 전",
  READY: "완료",
  PROGRESS: "진행 중",
  DEGRADED: "부분 저하",
  FAILED: "실패",
  UNKNOWN: "알 수 없음"
};

function stateLabel(value) {
  const key = String(value ?? "").toUpperCase();
  if (STATE_LABELS[key]) {
    return STATE_LABELS[key];
  }
  return value ? `알 수 없음 (${value})` : STATE_LABELS.UNKNOWN;
}

// V-36: 상태 배지는 공통 .badge-pill modifier 로만 색을 표현한다. 라벨 텍스트가
// progress("진행 중")·degraded("부분 저하")를 구분하므로 색에만 의존하지 않는다.
const STATE_MODIFIER = {
  IDLE: "neutral",
  READY: "ok",
  PROGRESS: "warn",
  DEGRADED: "warn",
  FAILED: "danger",
  UNKNOWN: "neutral"
};

function State({ value }) {
  const key = String(value ?? "").toUpperCase();
  const modifier = STATE_MODIFIER[key] ?? "neutral";
  return h("span", { className: `badge-pill badge-pill--${modifier}` }, stateLabel(value));
}

function panelEmptyCopy(state, kind) {
  if (state === "IDLE") {
    return `${kind} 조회 전입니다`;
  }
  if (state === "PROGRESS") {
    return `${kind} 진행 중…`;
  }
  return `${kind} 결과가 아직 없습니다`;
}

function instantCell(value) {
  return value ? formatInstant(value) : UNKNOWN_TEXT;
}

function candleItems(candles) {
  const data = surfaceData(candles);
  return data?.candles ?? data?.data ?? [];
}

function referenceLabel(candle) {
  if (candle?.asOf) return instantCell(candle.asOf);
  if (candle?.timestamp) return instantCell(candle.timestamp);
  return candle?.date ?? candle?.time ?? UNKNOWN_TEXT;
}

function surfaceProvenance(value) {
  const item = value?.provenance?.[0];
  if (!item) return null;
  return `출처 ${item.provider}${item.asOf ? ` · 기준 ${formatInstant(item.asOf)}` : ""}${item.observedAt ? ` · 수집 ${formatInstant(item.observedAt)}` : ""}`;
}

function toFiniteNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function findQuote(symbol, realtimePrices) {
  const data = surfaceData(realtimePrices);
  const items = Array.isArray(data) ? data : data?.data ?? [];
  return items.find(item => item?.symbol?.toUpperCase() === symbol?.toUpperCase());
}

function latestQuote(symbol, realtimePrices) {
  const quote = findQuote(symbol, realtimePrices);
  const price = quote?.lastPrice ?? quote?.price;
  return {
    price: formatAmount(quote?.currency ?? "USD", price),
    change: UNKNOWN_TEXT,
    referenceTime: quote?.brokerTimestamp || quote?.observedAt
      ? instantCell(quote.brokerTimestamp ?? quote.observedAt) : UNKNOWN_TEXT
  };
}

// Position Plan 가격대 시각화의 "현재가" 마커 전용 — 화면 표시용 문자열이 아니라
// 트랙 위치 계산에 쓸 수 있는 실수값이 필요하다. quote 조회 자체는 latestQuote 와 동일한
// realtimePrices 페이로드를 쓴다(별도 값을 새로 만들지 않는다).
function latestQuotePrice(symbol, realtimePrices) {
  const quote = findQuote(symbol, realtimePrices);
  return toFiniteNumber(quote?.lastPrice ?? quote?.price);
}

function providerStatus(value, hasData) {
  if (!value) return "loading";
  if (value.status === "UNAUTHORIZED" || value.unavailableReason === "UNAUTHORIZED") return "unauthorized";
  if (value.status === "UNAVAILABLE" || value.unavailable) return "unsupported";
  if (value.status === "ERROR") return "error";
  if (value.status === "DEGRADED") return hasData ? "partial" : "degraded";
  if (value.stale || surfaceData(value)?.stale) return "stale";
  return hasData ? "available" : "empty";
}

function qualitySummary({ analysis, realtimePrices, candles, orderbook, investorTrading, stockWarnings, commissions }) {
  const statuses = [
    providerStatus(realtimePrices, Boolean(surfaceData(realtimePrices)?.length || surfaceData(realtimePrices)?.data?.length)),
    providerStatus(candles, candleItems(candles).length > 0),
    providerStatus(orderbook, Boolean(surfaceData(orderbook)?.asks?.length || surfaceData(orderbook)?.bids?.length)),
    providerStatus(investorTrading, (Array.isArray(surfaceData(investorTrading))
      ? surfaceData(investorTrading)
      : surfaceData(investorTrading)?.data ?? surfaceData(investorTrading)?.investors ?? []).length > 0),
    providerStatus(stockWarnings, (surfaceData(stockWarnings)?.warnings ?? surfaceData(stockWarnings)?.data ?? []).length > 0),
    providerStatus(commissions, surfaceData(commissions)?.buy != null || surfaceData(commissions)?.sell != null)
  ];
  if (statuses.includes("unauthorized")) return ["권한 확인", "danger"];
  if (statuses.includes("error")) return ["오류", "danger"];
  if (statuses.includes("degraded") || statuses.includes("partial") || analysis?.result?.status === "DEGRADED") {
    return ["부분 데이터", "warn"];
  }
  if (statuses.includes("unsupported")) return ["부분 데이터", "warn"];
  if (statuses.includes("stale")) return ["지연 데이터", "warn"];
  if (statuses.every(status => status === "loading")) return ["불러오는 중", "neutral"];
  if (statuses.every(status => status === "empty" || status === "loading")) return ["데이터 없음", "neutral"];
  return [UNKNOWN_TEXT, "neutral"];
}

function riskSummary(analysis) {
  const level = analysis?.result?.riskLevel ?? analysis?.riskLevel;
  if (level === "HIGH") return ["높음", "danger"];
  if (level === "MEDIUM") return ["보통", "warn"];
  if (level === "LOW") return ["낮음", "ok"];
  return [UNKNOWN_TEXT, "neutral"];
}

function decisionAction(decision) {
  return decision && typeof decision === "object" ? decision.action : decision;
}

function decisionConfidence(decision, analysis) {
  return decision && typeof decision === "object"
    ? decision.confidence
    : analysis?.result?.confidence ?? analysis?.confidence;
}

function MissingData({ values = [] }) {
  return h("div", { className: "missing-data" },
    h("h3", null, "누락 데이터"),
    values.length
      ? h("ul", { className: "list" }, ...values.map(value => h("li", { key: value }, readableMissingData(value))))
      : h("p", { className: "empty" }, "보고된 항목 없음"));
}

function Provenance({ values = [] }) {
  return h("div", { className: "provenance" },
    h("h3", null, "데이터 출처"),
    values.length
      ? h("div", {
        className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "데이터 출처 표"
      }, h("table", null,
        h("thead", null, h("tr", null,
          ...["제공자", "필드", "기준 시각", "수집 시각"].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...values.map((item, index) => h("tr", { key: `${item.provider}-${item.field}-${index}` },
          h("td", null, item.provider ?? UNKNOWN_TEXT), h("td", null, item.field ?? UNKNOWN_TEXT),
          h("td", null, instantCell(item.asOf)), h("td", null, instantCell(item.collectedAt)))))))
      : h("p", { className: "empty" }, "보고된 출처가 없습니다"));
}

function analysisParts(analysis) {
  const result = analysis?.result;
  const analyzers = result?.analyzers ?? [];
  const metrics = analyzers.flatMap(analyzer => analyzer.metrics ?? []);
  const provenance = [
    ...(result?.observations ?? []),
    ...metrics.flatMap(metric => metric.provenance ?? [])
  ];
  return { result, metrics, provenance };
}

function Panel({ title, state, error, action, children }) {
  return h("section", { className: "panel stock-surface-panel", "aria-busy": state === "PROGRESS" ? "true" : undefined },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "종목 화면"), h("h2", null, title)),
      h("div", { className: "panel-actions" }, h(State, { value: state }), action)),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    children);
}

// 스톱/진입/추가/현재가/목표가1/목표가2 중 실제 값이 있는 마커만 골라 가격순으로 정렬한다.
// 값이 없는 마커는 추정/기본값으로 채우지 않고 그냥 목록에서 빠진다(DESIGN 원칙 3).
function positionPlanMarkers(plan, currentPrice) {
  if (!plan) return [];
  return [
    { key: "stop", label: "손절", price: toFiniteNumber(plan.stop) },
    { key: "entry", label: "진입", price: toFiniteNumber(plan.entry) },
    { key: "add", label: "추가", price: toFiniteNumber(plan.add) },
    { key: "current", label: "현재가", price: currentPrice },
    { key: "target1", label: "목표가1", price: toFiniteNumber(plan.target1) },
    { key: "target2", label: "목표가2", price: toFiniteNumber(plan.target2) }
  ].filter(marker => marker.price !== null).sort((a, b) => a.price - b.price);
}

// 매수/매도 구도와 무관하게 가격 오름차순으로만 배치한다(숏 포지션이면 손절이 오른쪽에
// 올 수도 있다) — currencyPath(portfolio-history-view.js)와 같은 "실측값에서 축 범위를
// 뽑아낸다" 방식. 마커가 2개 미만이면(예: WAIT 이고 plan 자체가 없는 경우) 값을 지어내는
// 대신 아무것도 그리지 않고 기존 텍스트 필드만 남긴다.
function PositionPlanRange({ plan, currentPrice, currency }) {
  const markers = positionPlanMarkers(plan, currentPrice);
  if (markers.length < 2) return null;
  const prices = markers.map(marker => marker.price);
  const min = Math.min(...prices);
  const max = Math.max(...prices);
  const span = max - min || 1;
  const placed = markers.map(marker => ({
    ...marker,
    percent: Math.min(98, Math.max(2, ((marker.price - min) / span) * 100))
  }));
  const summary = placed.map(marker => `${marker.label} ${formatAmount(currency, marker.price)}`).join(", ");
  return h("div", { className: "position-plan-range" },
    h("div", {
      className: "position-plan-range-track", role: "img", "aria-label": `가격 범위: ${summary}`
    },
    ...placed.map(marker => h("span", {
      key: marker.key,
      "aria-hidden": "true",
      className: marker.key === "current"
        ? "position-plan-range-tick position-plan-range-tick--current"
        : "position-plan-range-tick",
      style: { left: `${marker.percent}%` }
    }))),
    // 트랙의 점 위치는 시각 보조이고, 실제 접근성 텍스트는 이 범례가 담당한다
    // (WCAG 2.2 AA — 위치·색만으로 정보를 전달하지 않는다).
    h("ul", { className: "position-plan-range-legend" },
      ...placed.map(marker => h("li", {
        key: marker.key,
        className: marker.key === "current" ? "position-plan-range-legend-current" : undefined
      }, `${marker.label} ${formatAmount(currency, marker.price)}`))));
}

function PositionPlan({ analysis, position, symbol, realtimePrices }) {
  const plan = analysis?.result?.positionPlan ?? analysis?.positionPlan;
  // decision 은 analyzer 판단 오브젝트({action, confidence, ruleVersion, basis, missingData}) 다.
  // 리스크 등급(riskLevel)은 이 계약에는 없다 — home 판단 센터(decision-center.js)의
  // ctx.decisions 맵(다른 API·다른 prop)에만 있고 StockAnalysisProductSurface 에는
  // 내려오지 않으므로, 실제 필드가 없는 리스크 미터는 지어내지 않고 생략한다.
  const decision = analysis?.result?.decision ?? analysis?.decision;
  const action = decisionAction(decision);
  const confidence = toFiniteNumber(decision?.confidence);
  const currency = plan?.currency ?? position?.currency;
  const value = key => plan?.[key] ?? UNKNOWN_TEXT;
  const currentPrice = latestQuotePrice(symbol, realtimePrices);
  return h("section", { className: "position-plan", "data-position-plan": plan ? "available" : "unavailable" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Position Plan"), h("h3", null, "포지션 계획"))),
    h("dl", { className: "decision-metrics" },
      h("div", null, h("dt", null, "진입가"), h("dd", null, formatAmount(currency, value("entry")))),
      h("div", null, h("dt", null, "추가 진입가"), h("dd", null, formatAmount(currency, value("add")))),
      h("div", null, h("dt", null, "손절가"), h("dd", null, formatAmount(currency, value("stop")))),
      h("div", null, h("dt", null, "목표가1"), h("dd", null, formatAmount(currency, value("target1")))),
      h("div", null, h("dt", null, "목표가2"), h("dd", null, formatAmount(currency, value("target2")))),
      h("div", null, h("dt", null, "R:R"), h("dd", null, formatDecimal(plan?.riskReward))),
      h("div", null, h("dt", null, "최대 손실"), h("dd", null, formatAmount(currency, value("maxLossPerShare")))),
      h("div", null, h("dt", null, "현재 포지션"), h("dd", null, formatAmount(position?.currency, position?.marketValueAmount))),
      action
        ? h("div", null, h("dt", null, "판단"),
          h("dd", null, h("span", { className: "badge-pill badge-pill--neutral" },
            DECISION_LABELS[action] ?? action)))
        : null,
      confidence !== null
        ? h("div", null, h("dt", null, "신뢰도"),
          // 신뢰도는 0..1 점수이지 "현재값 / 서버가 준 한도" 가 아니다 — meter 규칙에 맞지 않아
          // 막대를 걷어내고 비율 텍스트만 남긴다(장식용 progress bar 금지).
          h("dd", null, formatRatio(decision.confidence)))
        : null),
    h(PositionPlanRange, { plan, currentPrice, currency }));
}

function StockSummary({ symbol, analysis, position, realtimePrices, candles, orderbook, investorTrading, stockWarnings, commissions, onCreateOrder }) {
  const quote = latestQuote(symbol, realtimePrices);
  const [quality, qualityModifier] = qualitySummary({
    analysis, realtimePrices, candles, orderbook, investorTrading, stockWarnings, commissions
  });
  const [risk, riskModifier] = riskSummary(analysis);
  const decision = analysis?.result?.decision ?? analysis?.decision;
  const action = decisionAction(decision);
  const confidence = decisionConfidence(decision, analysis);
  return h("section", { className: "panel stock-surface-panel stock-surface-summary" },
    h("div", { className: "stock-surface-summary-main" },
      h("p", { className: "eyebrow" }, "종목 분석"),
      h("h2", null, symbol),
      h("span", { className: "metric-label" }, "현재가"),
      h("span", { className: "metric-value-large" }, quote.price),
      h("span", { className: "stock-surface-change" },
        h("span", { className: quote.change.startsWith("-") ? "negative" : "positive" }, quote.change))),
    h("dl", { className: "stock-surface-summary-meta" },
      h("div", null, h("dt", null, "기준 시각"), h("dd", null, quote.referenceTime)),
      h("div", null, h("dt", null, "데이터 품질"), h("dd", null,
        h("span", { className: `badge-pill badge-pill--${qualityModifier}` }, quality))),
      h("div", null, h("dt", null, "리스크"), h("dd", null,
        h("span", { className: `badge-pill badge-pill--${riskModifier}` }, risk))),
      h("div", null, h("dt", null, "판단"), h("dd", null,
        action ? DECISION_LABELS[action] ?? action : UNKNOWN_TEXT)),
      h("div", null, h("dt", null, "신뢰도"), h("dd", null,
        confidence === null ? UNKNOWN_TEXT : formatRatio(confidence))),
      h("div", null, h("dt", null, "보유 손익"), h("dd", null,
        formatSignedAmount(position?.currency, position?.profitLossAmount))),
      h("div", null, h("dt", null, "포트폴리오 비중"), h("dd", null, formatRatio(position?.weight))),
      h("div", null, h("dt", null, "주문"), h("dd", null,
        h("button", { type: "button", className: "secondary", onClick: onCreateOrder }, "이 종목 주문 작성"))),
      ),
    h(PositionPlan, { analysis, position, symbol, realtimePrices }));
}

function AnalysisPanel({ analysis, state, error, busy, onCreate }) {
  const { result, metrics, provenance } = analysisParts(analysis);
  return h(Panel, {
    title: "분석",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS" || busy, onClick: onCreate
    // 결과 없는 빈 envelope({result:null})는 "분석 없음"으로 보고 생성 버튼을 노출한다.
    }, result ? "분석 재실행" : "분석 생성")
  },
  result ? h("div", null,
    h("p", { className: "disclaimer" },
      `스냅샷 ${analysis.inputSnapshotId ?? UNKNOWN_TEXT} · 기준 ${instantCell(result.asOf)}`),
    h("div", { className: "analysis-metrics" },
      metrics.length
        ? metrics.map(metric => h("div", { className: "metric", key: metric.name },
          h("span", { className: "metric-label" }, metric.name ?? UNKNOWN_TEXT),
          h("span", { className: "metric-value" }, metric.value ?? UNKNOWN_TEXT),
          h("small", null, metric.unit ?? "")))
        : h("p", { className: "empty" }, "분석 지표가 없습니다")),
    h(MissingData, { values: result.missingData }),
    h(Provenance, { values: provenance }))
    : h("p", { className: "empty" }, panelEmptyCopy(state, "분석")));
}

function ForecastPanel({ forecast, state, error, busy, onCreate }) {
  const result = forecast?.result ?? forecast;
  const metrics = result?.forecasts ?? [];
  const provenance = metrics.flatMap(metric => metric.provenance ?? []);
  return h(Panel, {
    title: "예측",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS" || busy, onClick: onCreate
    }, forecast ? "예측 재실행" : "예측 생성")
  },
  result ? h("div", null,
    h("p", { className: "disclaimer" },
      `스냅샷 ${forecast.inputSnapshotId ?? result.inputSnapshotId ?? UNKNOWN_TEXT} · 신뢰도 ${result.confidence ?? UNKNOWN_TEXT}`),
    metrics.length
      ? h("ul", { className: "list" }, ...metrics.map(metric => h("li", { key: metric.name },
        h("strong", null, metric.name ?? UNKNOWN_TEXT), h("span", null, metric.value ?? UNKNOWN_TEXT))))
      : h("p", { className: "empty" }, "예측 지표가 없습니다"),
    h(MissingData, { values: result.missingData }),
    h(Provenance, { values: provenance }))
    : h("p", { className: "empty" }, panelEmptyCopy(state, "예측")));
}

function ExplanationPanel({ explanation, state, error, busy, onCreate }) {
  const claims = explanation?.explanation ?? {};
  const citations = explanation?.citations ?? [];
  const evidence = [...(claims.evidence ?? []), ...(claims.counterArguments ?? [])];
  return h(Panel, {
    title: "Gemini 설명",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS" || busy, onClick: onCreate
    }, explanation ? "설명 재생성" : "설명 생성")
  },
  explanation ? h("div", null,
    h("p", { className: "disclaimer" }, `스냅샷 ${explanation.inputSnapshotId ?? UNKNOWN_TEXT}`),
    evidence.length
      ? h("ul", { className: "list" }, ...evidence.map((claim, index) => h("li", { key: `${claim.text}-${index}` },
        h("strong", null, claim.text), h("span", null, claim.citationIds?.join(", ") ?? "출처 없음"))))
      : h("p", { className: "empty" }, "설명 근거가 없습니다"),
    h("h3", null, "출처"),
    citations.length
      ? h("ul", { className: "list" }, ...citations.map(citation => h("li", { key: citation.id },
        `${citation.id} · ${citation.provider} · ${citation.field}`)))
      : h("p", { className: "empty" }, "출처가 없습니다"),
    h(MissingData, { values: [...(explanation.missingData ?? []), ...(claims.missingData ?? [])] }))
    : h("p", { className: "empty" }, panelEmptyCopy(state, "설명")));
}

function RelatedEvents({ events = [] }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "이벤트 레이더"), h("h2", null, "관련 이벤트"))),
    events.length
      ? h("ul", { className: "list" }, ...events.map(event => h("li", { key: event.id },
        h("strong", null, event.summary), h("span", null,
          `${event.source ?? UNKNOWN_TEXT} · ${instantCell(event.occurredAt)} · ${event.reviewStatus ?? "PENDING"}`))))
      : h("p", { className: "empty" }, "관련 이벤트가 없습니다"));
}

function SnapshotHistory({ history = [], onSelectSnapshot }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "불변 입력"), h("h2", null, "스냅샷 이력"))),
    history.length
      ? h("ul", { className: "list" }, ...history.map(item => h("li", { key: item.runId },
        h("div", null,
          h("strong", null, `${item.status ?? UNKNOWN_TEXT} · ${instantCell(item.completedAt ?? item.startedAt)}`),
          h("span", null, `실행 ${item.runId ?? UNKNOWN_TEXT} · 스냅샷 ${item.inputSnapshotId ?? UNKNOWN_TEXT}`),
          item.errorCode ? h("small", null, item.errorCode) : null),
        h("button", { type: "button", className: "secondary", onClick: () => onSelectSnapshot(item.runId) },
          "스냅샷 보기"))))
      : h("p", { className: "empty" }, "스냅샷이 아직 없습니다"));
}

function OrderbookPanel({ orderbook }) {
  const message = surfaceMessage(orderbook, "호가 데이터를 불러오는 중…");
  const data = surfaceData(orderbook);
  const asks = data?.asks ?? [];
  const bids = data?.bids ?? [];
  const hasData = asks.length > 0 || bids.length > 0;
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 호가"), h("h2", null, "호가 잔량"))),
    hasData
      ? h("div", { className: "orderbook-widget", style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px", marginTop: "12px" } },
        h("div", { className: "asks", style: { background: "rgba(240, 68, 82, 0.05)", padding: "12px", borderRadius: "var(--r-md)" } },
          h("h4", { style: { color: "var(--danger-text)", margin: "0 0 8px" } }, "매도 호가 (Asks)"),
          h("ul", { className: "list", style: { fontSize: "var(--fs-xs)" } },
            ...asks.slice(0, 10).map((level, i) =>
              h("li", { key: `ask-${i}` },
                h("span", null, `호가 ${i + 1}`),
                h("strong", null, `${data?.currency ?? ""} ${level.price != null ? Number(level.price).toLocaleString() : UNKNOWN_TEXT}`,
                  ` (${level.volume != null ? Number(level.volume).toLocaleString() : UNKNOWN_TEXT}주)`))))),
        h("div", { className: "bids", style: { background: "rgba(49, 130, 246, 0.05)", padding: "12px", borderRadius: "var(--r-md)" } },
          h("h4", { style: { color: "var(--accent-text)", margin: "0 0 8px" } }, "매수 호가 (Bids)"),
          h("ul", { className: "list", style: { fontSize: "var(--fs-xs)" } },
            ...bids.slice(0, 10).map((level, i) =>
              h("li", { key: `bid-${i}` },
                h("span", null, `호가 ${i + 1}`),
                h("strong", null, `${data?.currency ?? ""} ${level.price != null ? Number(level.price).toLocaleString() : UNKNOWN_TEXT}`,
                  ` (${level.volume != null ? Number(level.volume).toLocaleString() : UNKNOWN_TEXT}주)`))))))
      : h("p", { className: "empty" }, message ?? "호가 데이터가 없습니다"),
    orderbook?.unknownFields?.length
      ? h("small", { className: "metric-freshness" }, `누락 필드: ${orderbook.unknownFields.join(", ")}`)
      : null,
    surfaceProvenance(orderbook)
      ? h("small", { className: "metric-freshness" }, surfaceProvenance(orderbook)) : null);
}

// ---------------------------------------------------------------------------
// 캔들 차트 패널 — loadCandles()
// ---------------------------------------------------------------------------
function CandleChartPanel({ symbol, candles, timeframe, onTimeframeChange }) {
  return h(MarketCandleChart, {
    envelope: candles,
    symbol,
    interval: timeframe,
    onIntervalChange: onTimeframeChange
  });
}

// ---------------------------------------------------------------------------
// 투자자별 매매동향 — loadInvestorTrading()
// ---------------------------------------------------------------------------
function InvestorTradingPanel({ investorTrading }) {
  const message = surfaceMessage(investorTrading, "매매동향 데이터를 불러오는 중…");
  const data = surfaceData(investorTrading);
  const items = Array.isArray(data) ? data : data?.data ?? data?.investors ?? [];
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 매매동향"), h("h2", null, "투자자별 매매동향"))),
    items.length
      ? h("div", { className: "investor-trading-grid", style: { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(200px, 1fr))", gap: "12px", marginTop: "12px" } },
        ...items.map((item, i) => {
          const hasVolumes = item.buyVolume != null && item.sellVolume != null;
          const net = hasVolumes ? item.buyVolume - item.sellVolume : null;
          const positive = (net ?? 0) >= 0;
          return h("div", { key: `inv-${i}`, style: { background: "var(--panel-raised)", padding: "16px", borderRadius: "var(--r-md)" } },
            h("strong", { style: { fontSize: "var(--fs-sm)" } }, item.investorType ?? item.name ?? UNKNOWN_TEXT),
            h("div", { style: { marginTop: "8px", display: "flex", justifyContent: "space-between" } },
              h("span", { style: { color: "var(--danger-text)", fontSize: "var(--fs-xs)" } },
                `매수 ${item.buyVolume != null ? Number(item.buyVolume).toLocaleString() : UNKNOWN_TEXT}`),
              h("span", { style: { color: "var(--accent-text)", fontSize: "var(--fs-xs)" } },
                `매도 ${item.sellVolume != null ? Number(item.sellVolume).toLocaleString() : UNKNOWN_TEXT}`)),
            h("div", { style: { marginTop: "6px", fontWeight: "var(--fw-bold)", color: positive ? "var(--danger-text)" : "var(--accent-text)" } },
              `순매수 ${net == null ? UNKNOWN_TEXT : `${positive ? "+" : ""}${net.toLocaleString()}`}`));
        }))
      : h("p", { className: "empty" }, message ?? "매매동향 데이터가 없습니다"));
}

// ---------------------------------------------------------------------------
// 투자유의/경고 — loadStockWarnings()
// ---------------------------------------------------------------------------
const WARNING_SEVERITY = { INFO: "neutral", CAUTION: "warn", WARNING: "warn", DANGER: "danger" };

function StockWarningsPanel({ warnings }) {
  const message = surfaceMessage(warnings, "투자 경고 데이터를 불러오는 중…");
  const data = surfaceData(warnings);
  const items = data?.warnings ?? data?.data ?? [];
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 경고"), h("h2", null, "투자 경고"))),
    items.length
      ? h("ul", { className: "list" }, ...items.map((w, i) =>
        h("li", { key: `warn-${i}`, style: { alignItems: "center" } },
          h("span", { className: `badge-pill badge-pill--${WARNING_SEVERITY[w.severity] ?? "warn"}` },
            w.severity ?? "경고"),
          h("span", null, w.message ?? w.description ?? UNKNOWN_TEXT),
          w.effectiveDate ? h("small", { style: { color: "var(--muted)" } }, `발효일 ${w.effectiveDate}`) : null)))
      : h("p", { className: "empty" }, message ?? "투자 경고가 없습니다"));
}

// ---------------------------------------------------------------------------
// 수수료 조회 — loadCommissions()
// ---------------------------------------------------------------------------
function CommissionsPanel({ commissions }) {
  const message = surfaceMessage(commissions, "수수료 정보를 불러오는 중…");
  const data = surfaceData(commissions);
  const buy = data?.buy ?? data?.buyCommission;
  const sell = data?.sell ?? data?.sellCommission;
  const hasBuy = buy != null;
  const hasSell = sell != null;
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 수수료"), h("h2", null, "거래 수수료"))),
    (hasBuy || hasSell)
      ? h("div", { className: "commissions-grid", style: { display: "grid", gridTemplateColumns: "1fr 1fr", gap: "12px", marginTop: "12px" } },
        h("div", { style: { background: "rgba(240, 68, 82, 0.05)", padding: "16px", borderRadius: "var(--r-md)", textAlign: "center" } },
          h("span", { style: { fontSize: "var(--fs-sm)", color: "var(--muted)" } }, "매수 수수료"),
          h("strong", { style: { display: "block", fontSize: "var(--fs-lg)", marginTop: "4px" } },
            hasBuy ? `${(Number(buy.rate ?? buy) * 100).toFixed(4)}%` : UNKNOWN_TEXT)),
        h("div", { style: { background: "rgba(49, 130, 246, 0.05)", padding: "16px", borderRadius: "var(--r-md)", textAlign: "center" } },
          h("span", { style: { fontSize: "var(--fs-sm)", color: "var(--muted)" } }, "매도 수수료"),
          h("strong", { style: { display: "block", fontSize: "var(--fs-lg)", marginTop: "4px" } },
            hasSell ? `${(Number(sell.rate ?? sell) * 100).toFixed(4)}%` : UNKNOWN_TEXT)))
      : h("p", { className: "empty" }, message ?? "수수료 정보가 없습니다"));
}

export function StockAnalysisProductSurface({
  symbol,
  analysis,
  forecast,
  explanation,
  relatedEvents,
  history,
  status = {},
  errors = {},
  busy = false,
  onCreateAnalysis,
  onCreateForecast,
  onCreateExplanation,
  onSelectSnapshot,
  orderbook,
  realtimePrices,
  candles,
  candleTimeframe,
  onCandleTimeframeChange,
  investorTrading,
  stockWarnings,
  commissions,
  position,
  onCreateOrder
}) {
  return h("main", { className: "stock-surface" },
    h("div", { className: "stock-surface-grid" },
      h(StockSummary, { symbol, analysis, position, realtimePrices, candles, orderbook, investorTrading, stockWarnings, commissions, onCreateOrder }),
      h(AnalysisPanel, {
        analysis, state: status.analysis ?? "IDLE", error: errors.analysis, busy, onCreate: onCreateAnalysis
      }),
      h(ForecastPanel, {
        forecast, state: status.forecast ?? "IDLE", error: errors.forecast, busy, onCreate: onCreateForecast
      }),
      h(ExplanationPanel, {
        explanation, state: status.explanation ?? "IDLE", error: errors.explanation, busy, onCreate: onCreateExplanation
      }),
      h(StockWarningsPanel, { warnings: stockWarnings }),
      h(CandleChartPanel, { symbol, candles, timeframe: candleTimeframe, onTimeframeChange: onCandleTimeframeChange }),
      h(OrderbookPanel, { orderbook }),
      h(InvestorTradingPanel, { investorTrading }),
      h(CommissionsPanel, { commissions }),
      h(RelatedEvents, { events: relatedEvents }),
      h(SnapshotHistory, { history, onSelectSnapshot })));
}
