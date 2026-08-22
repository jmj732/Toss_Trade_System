"use client";

import { createElement as h } from "react";

import {
  buyingPowerAmounts,
  formatAmount,
  formatFreshness,
  formatRatio,
  formatSignedAmount,
  UNKNOWN_TEXT
} from "../lib/format.js";
import { ActionQueue, PortfolioPositionTable, PortfolioRiskPanel } from "./decision-center.js";
import { PortfolioHistoryTrend } from "./portfolio-history-view.js";

// 상태별 region weight. 0=미렌더/접힘, 1=한 줄, 2=기본, 3=주요, 4=HERO 전폭.
const WEIGHTS = {
  BLOCKED: { actions: 0, risk: 0, summary: 0, positions: 0, trend: 0, market: 0 },
  CRITICAL: { actions: 4, risk: 3, summary: 1, positions: 1, trend: 0, market: 0 },
  RISK: { actions: 3, risk: 4, summary: 2, positions: 3, trend: 0, market: 0 },
  ACTIVE: { actions: 3, risk: 2, summary: 2, positions: 2, trend: 0, market: 0 },
  CALM: { actions: 1, risk: 1, summary: 3, positions: 3, trend: 1, market: 1 }
};

// 동점 weight 시 DOM 순서 tie-break(actions → risk → summary → positions → trend → market).
const REGION_ORDER = ["actions", "risk", "summary", "positions", "trend", "market"];

// 추세·시장 맥락은 차트를 담은 무거운 영역이다. 낮은 weight(0·1)에서는 전폭 카드로 펼치지
// 않고 접이식 details 로 접는다 — weight 1("한 줄")도 요약 한 줄 + 펼침으로만 노출한다.
// (decision 영역인 actions/risk/summary/positions 는 weight 1 에서 panel--compact 로 처리한다.)
const CONTEXT_REGIONS = new Set(["trend", "market"]);

const CASH_STATUS_LABELS = { KNOWN: "확인됨", UNKNOWN: "확인 필요" };

function Amounts({ values, signed = false }) {
  const entries = Object.entries(values ?? {});
  const format = signed ? formatSignedAmount : formatAmount;
  if (!entries.length) {
    return h("span", { className: "unknown-text" }, UNKNOWN_TEXT);
  }
  return h("span", null, ...entries.map(([currency, amount]) =>
    h("span", { className: "amount", key: currency }, format(currency, amount))));
}

// account.dailyProfitLossRate / account.profitLossRate 는 서버가 내려주는 단일 스칼라
// 비율(통화별이 아니다) — Amounts 처럼 통화별 span 옆에 붙이면 다통화 계좌에서 "이 통화의
// 비율"처럼 오독될 수 있어 금액 줄과 분리한 자체 줄로 렌더한다. rate 가 0 또는 null 이면
// "–" 글리프만 쓰고 .positive/.negative 클래스는 붙이지 않는다(변동 없음/미확인은 색이 없다).
function RateChange({ rate }) {
  const text = formatRatio(rate);
  const positive = rate != null && rate > 0;
  const negative = rate != null && rate < 0;
  const glyph = positive ? "▲" : negative ? "▼" : "–";
  const word = positive ? "상승" : negative ? "하락" : rate === 0 ? "보합" : null;
  const cls = positive ? "positive" : negative ? "negative" : "";
  return h("span", { className: "rate-change", "aria-label": word ? `${word} ${text}` : text },
    h("span", { "aria-hidden": "true" }, `${glyph} `),
    h("span", { className: cls }, text));
}

const SUMMARY_SPARK_WIDTH = 72;
const SUMMARY_SPARK_HEIGHT = 22;

// portfolio-history-view.js 의 currencyPath() 와 동일한 산식이다. 그 파일은 이 작업 범위 밖(수정 금지
// 대상)이라 export 추가 대신 요약 바 전용 소형 변형으로 이 파일 안에 그대로 옮겨 적었다 — 값을
// 새로 계산하는 게 아니라 이미 존재하는 실좌표 점들을 SVG 좌표로 변환만 한다.
function summarySparkPath(points, field, currency) {
  const present = points
    .map((point, index) => ({ index, value: point[field]?.[currency] }))
    .filter(entry => entry.value != null);
  if (present.length < 2) return "";
  const values = present.map(entry => Number(entry.value));
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const lastIndex = points.length - 1;
  return present.map((entry, i) => {
    const x = lastIndex === 0 ? SUMMARY_SPARK_WIDTH / 2 : (entry.index / lastIndex) * SUMMARY_SPARK_WIDTH;
    const y = SUMMARY_SPARK_HEIGHT - ((Number(entry.value) - min) / span) * SUMMARY_SPARK_HEIGHT;
    return `${i === 0 ? "M" : "L"}${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(" ");
}

// 총 손익(profitLossAmounts) 추이만 그린다 — portfolioHistory 포인트는 시점별 누적값이라
// "오늘 손익"(하루 변화분)에 대응하는 계열이 없다. 데이터가 없으면 기존 Trend 와 같은 패턴으로
// 아무것도 렌더하지 않는다(빈 상태를 카드로 만들지 않는다).
function SummaryTrendSparkline({ portfolioHistory }) {
  const points = portfolioHistory?.data?.points ?? [];
  if (points.length < 2) return null;
  const krwPath = summarySparkPath(points, "profitLossAmounts", "KRW");
  const usdPath = summarySparkPath(points, "profitLossAmounts", "USD");
  if (!krwPath && !usdPath) return null;
  // 두 통화 선은 각각 다른 스케일로 정규화되어 서로 비교 가능한 값이 아니다(범례 없이 겹쳐 그리지
  // 않음). 어떤 통화가 그려졌는지 aria-label 에 명시해 스크린리더 사용자가 오독하지 않게 한다.
  const drawnCurrencies = [krwPath ? "KRW" : null, usdPath ? "USD" : null].filter(Boolean).join("·");
  return h("svg", {
    className: "summary-sparkline",
    width: SUMMARY_SPARK_WIDTH,
    height: SUMMARY_SPARK_HEIGHT,
    viewBox: `0 0 ${SUMMARY_SPARK_WIDTH} ${SUMMARY_SPARK_HEIGHT}`,
    preserveAspectRatio: "none",
    role: "img",
    "aria-label": `총 손익 추세 ${drawnCurrencies}(참고용, 수치는 위 텍스트 기준)`
  },
    krwPath ? h("path", { className: "trend-krw", d: krwPath }) : null,
    usdPath ? h("path", { className: "trend-usd", d: usdPath }) : null);
}

// 요약 지표는 존재하는 계약이므로 없는 값은 UNKNOWN_TEXT 로 표기해도 된다.
// 리스크 정책 문자열("제한 N건")은 렌더하지 않는다.
function PortfolioSummaryBar({ dashboard, portfolioHistory }) {
  const section = dashboard?.portfolio;
  const portfolio = section?.data;
  const account = portfolio?.account ?? {};
  return h("section", { className: "panel portfolio-summary-bar", "aria-label": "포트폴리오 요약" },
    h("div", { className: "portfolio-summary-grid" },
      h("div", null,
        h("span", { className: "metric-label" }, "총 평가금액"),
        h("strong", { className: "metric-value" }, h(Amounts, { values: account.marketValueAmounts }))),
      h("div", null,
        h("span", { className: "metric-label" }, "오늘 손익"),
        h("strong", { className: "metric-value" }, h(Amounts, { values: account.dailyProfitLossAmounts, signed: true })),
        h(RateChange, { rate: account.dailyProfitLossRate })),
      h("div", null,
        h("span", { className: "metric-label" }, "총 손익"),
        h("strong", { className: "metric-value" }, h(Amounts, { values: account.profitLossAmounts, signed: true })),
        h(RateChange, { rate: account.profitLossRate }),
        h(SummaryTrendSparkline, { portfolioHistory })),
      h("div", null,
        h("span", { className: "metric-label" }, "현금 잔고 상태"),
        h("strong", { className: "metric-value" }, CASH_STATUS_LABELS[account.cashBalanceStatus] ?? UNKNOWN_TEXT)),
      h("div", null,
        h("span", { className: "metric-label" }, "주문 가능 현금"),
        h("strong", { className: "metric-value" }, h(Amounts, {
          values: buyingPowerAmounts(portfolio?.buyingPower)
        }))),
    ),
    h("small", { className: "metric-freshness" },
      `기준 ${formatFreshness(portfolio?.completedAt ?? section?.asOf)}`));
}

function regionWrapper(name, weight, node) {
  if (node == null) return null;
  // weight 0: trend/market 는 접기, 그 외는 미렌더(호출부에서 이미 걸러짐).
  // weight 1 의 trend/market 도 무거운 본문을 펼치지 않고 같은 details 로 접는다(CALM 4564px 회귀 차단).
  if (weight === 0 || (weight <= 1 && CONTEXT_REGIONS.has(name))) {
    return h("details", {
      key: name,
      className: "panel panel--collapsed home-region",
      "data-home-region": name,
      "data-weight": String(weight)
    },
      h("summary", { className: "panel-summary" }, name === "market" ? "시장 맥락" : "추세"),
      h("div", { className: "home-region-body" }, node));
  }
  const modifier = weight === 4 ? " panel--hero" : weight === 1 ? " panel--compact" : "";
  return h("div", {
    key: name,
    className: `home-region${modifier}`,
    "data-home-region": name,
    "data-weight": String(weight)
  }, node);
}

export function HomeDecisionCenter({
  surface,
  actions = [],
  dashboard,
  riskPolicy,
  portfolioHistory,
  historyBusy = false,
  marketContext = null,
  busyOrderId,
  onOrderAction,
  onRefresh
}) {
  // BLOCKED 은 호출부가 온보딩/에러 화면을 그린다. 방어적으로 null.
  if (!surface || surface.state === "BLOCKED") {
    return null;
  }
  const state = surface.state;
  const weights = WEIGHTS[state] ?? WEIGHTS.CALM;

  const lastChecked = dashboard?.portfolio?.data?.completedAt ?? dashboard?.portfolio?.asOf ?? null;

  const nodes = {
    actions: h(ActionQueue, {
      items: actions, state, busyOrderId, onOrderAction, onRefresh, lastChecked
    }),
    risk: h(PortfolioRiskPanel, { dashboard }),
    summary: h(PortfolioSummaryBar, { dashboard, portfolioHistory }),
    positions: h(PortfolioPositionTable, {
      section: dashboard?.portfolio, analysis: dashboard?.analysis, limit: 5, caption: "보유 포지션"
    }),
    trend: h(PortfolioHistoryTrend, { history: portfolioHistory, busy: historyBusy }),
    market: marketContext
  };

  const ordered = REGION_ORDER
    .map((name, index) => ({ name, index, weight: weights[name] }))
    .sort((a, b) => (b.weight - a.weight) || (a.index - b.index));

  return h("main", {
    className: "grid home-decision-shell",
    "data-home-state": state,
    "aria-label": "내 자산 홈"
  },
    h("header", { className: "home-decision-header", "data-home-region": "status" },
      h("p", { className: "eyebrow" }, "Decision Center"),
      h("p", { className: "home-decision-state" }, `${surface.label} · ${surface.actionCount}건`)),
    ...ordered.map(region => regionWrapper(region.name, region.weight, nodes[region.name])));
}
