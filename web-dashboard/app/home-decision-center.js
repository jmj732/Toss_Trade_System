"use client";

import { createElement as h } from "react";

import {
  buyingPowerAmounts,
  directionOf,
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
// 비율"처럼 오독될 수 있어 금액 줄과 분리한 자체 줄로 렌더한다. ▲/▼/– 방향 기호는 쓰지 않고
// directionOf() 로 색 클래스와 접근성 낱말(상승/하락/보합)만 남긴다 — 0·null 은 무색이다.
function RateChange({ rate }) {
  const text = formatRatio(rate);
  const { className, word } = directionOf(rate);
  return h("span", { className: "rate-change", "aria-label": word ? `${word} ${text}` : text },
    h("span", { className }, text));
}

// SummaryTrendSparkline / summarySparkPath 는 삭제했다. 72×22 안에 축·범례 없이 두 통화를 서로
// 다른 스케일로 정규화해 겹쳐 그린 장식용 그래프였고, 파일 자체 주석이 "서로 비교 가능한 값이
// 아니다"라고 인정했다 — 오독 위험만 있고 정보 가치가 없어 제거한다.

// 요약 지표는 존재하는 계약이므로 없는 값은 UNKNOWN_TEXT 로 표기해도 된다.
// 리스크 정책 문자열("제한 N건")은 렌더하지 않는다.
function PortfolioSummaryBar({ dashboard }) {
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
        h(RateChange, { rate: account.profitLossRate })),
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
    summary: h(PortfolioSummaryBar, { dashboard }),
    // positions 는 detail 을 넘기지 않는다 — positionDecisions 미전달로 compact 밀도로 파생된다.
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
