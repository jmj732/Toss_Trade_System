"use client";

import { createElement as h } from "react";

import { formatAmount, formatFreshness, formatQuantity, formatRatio, formatSignedAmount, UNKNOWN_TEXT } from "../lib/format.js";

function dataOf(section) {
  if (!section || section.unavailable || section.status === "ERROR") return null;
  return section.data ?? section;
}

function sideLabel(side) {
  return side === "BUY" ? "매수" : side === "SELL" ? "매도" : side ?? UNKNOWN_TEXT;
}

export function buildActionItems({ dashboard, events = [] } = {}) {
  const proposals = dataOf(dashboard?.pendingOrderProposals);
  const dashboardEvents = dataOf(dashboard?.pendingEvents);
  const orderItems = Array.isArray(proposals) ? proposals.map(order => ({
    id: order.id,
    kind: "order",
    type: "ORDER",
    symbol: order.symbol,
    title: `${sideLabel(order.side)} ${order.symbol ?? UNKNOWN_TEXT}`,
    cause: "주문 제안",
    currentSituation: `${order.type ?? UNKNOWN_TEXT} · ${formatQuantity(order.quantity)}`,
    portfolioImpact: order.portfolioImpact ?? UNKNOWN_TEXT,
    decision: order.decision ?? UNKNOWN_TEXT,
    reason: order.reason ?? UNKNOWN_TEXT,
    deadline: order.expiresAt,
    source: order.source ?? UNKNOWN_TEXT,
    order
  })) : [];
  const eventSource = Array.isArray(events) && events.length ? events : dashboardEvents;
  const eventItems = (Array.isArray(eventSource) ? eventSource : []).map(event => ({
    id: event.id,
    kind: "event",
    type: event.type ?? "EVENT",
    symbol: event.affectedSymbols?.[0],
    title: event.title ?? event.summary ?? UNKNOWN_TEXT,
    cause: event.type ?? UNKNOWN_TEXT,
    currentSituation: event.summary ?? UNKNOWN_TEXT,
    portfolioImpact: event.portfolioImpact ?? UNKNOWN_TEXT,
    decision: event.decision ?? UNKNOWN_TEXT,
    reason: event.reason ?? UNKNOWN_TEXT,
    deadline: event.deadline,
    source: event.source ?? UNKNOWN_TEXT,
    event
  }));
  return [...orderItems, ...eventItems];
}

function ActionItem({ item, onOrder }) {
  return h("li", { className: "action-item", "data-action-kind": item.kind },
    h("div", { className: "action-item-heading" },
      h("span", { className: "badge-pill badge-pill--neutral" }, item.type),
      h("strong", null, item.title),
      item.symbol ? h("span", { className: "metric-label" }, item.symbol) : null),
    h("dl", { className: "action-item-facts" },
      h("div", null, h("dt", null, "원인"), h("dd", null, item.cause)),
      h("div", null, h("dt", null, "현재 상황"), h("dd", null, item.currentSituation)),
      h("div", null, h("dt", null, "포트폴리오 영향"), h("dd", null, item.portfolioImpact)),
      h("div", null, h("dt", null, "판단"), h("dd", null, item.decision)),
      h("div", null, h("dt", null, "근거"), h("dd", null, item.reason)),
      h("div", null, h("dt", null, "기한"), h("dd", null, formatFreshness(item.deadline))),
      h("div", null, h("dt", null, "출처"), h("dd", null, item.source))),
    h("div", { className: "action-item-actions" },
      item.kind === "order"
        ? h("button", { type: "button", onClick: () => onOrder?.(item.order.id, "approve") }, "주문 검토")
        : h("a", { className: "button-link secondary", href: `/events?event=${encodeURIComponent(item.id)}` }, "이벤트 보기"),
      item.symbol ? h("a", { className: "button-link secondary", href: `/stocks/${encodeURIComponent(item.symbol)}` }, "종목 보기") : null));
}

export function ActionQueue({ items = [], onOrder }) {
  return h("section", { className: "panel action-queue", "data-home-region": "decision-queue", "aria-label": "결정 센터" },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "Action Queue"), h("h2", null, "결정 센터")),
      h("span", { className: "badge-pill badge-pill--neutral" }, `${items.length}건`)),
    items.length
      ? h("ol", { className: "action-list" }, ...items.map(item => h(ActionItem, { key: `${item.kind}-${item.id}`, item, onOrder })))
      : h("p", { className: "empty" }, "지금 확인할 결정이 없습니다"));
}

export function DataFreshnessIndicator({ section }) {
  const state = !section ? "데이터 확인 중" : section.unavailable || section.status === "ERROR"
    ? "데이터 사용 불가" : section.stale ? "STALE · 지연 데이터" : section.unknown ? "데이터 확인 필요" : "LIVE";
  const modifier = state === "LIVE" ? "ok" : state.includes("불가") ? "danger" : "warn";
  return h("span", { className: `badge-pill badge-pill--${modifier}`, "data-freshness": state }, state);
}

export function GlobalStockSearch({ onSearch }) {
  return h("form", { className: "global-stock-search", role: "search", onSubmit: event => {
    event.preventDefault();
    const symbol = event.currentTarget.elements.symbol.value.trim().toUpperCase();
    if (symbol) onSearch?.(symbol);
  } },
  h("label", { htmlFor: "global-stock-search-symbol" }, "종목 검색"),
  h("input", { id: "global-stock-search-symbol", name: "symbol", placeholder: "심볼 검색 (예: AAPL)", inputMode: "text" }),
  h("button", { type: "submit", className: "secondary" }, "열기"));
}

function marketPayload(value) {
  return value?.data?.payload ?? value?.data ?? value?.payload ?? value;
}

export function MarketStatusIndicator({ calendar }) {
  const payload = marketPayload(calendar);
  const today = payload?.today;
  let label = "시장 상태 확인 필요";
  if (today?.regularMarket?.open && today?.regularMarket?.close) {
    const now = Date.now();
    const open = Date.parse(today.regularMarket.open);
    const close = Date.parse(today.regularMarket.close);
    label = Number.isFinite(open) && Number.isFinite(close)
      ? (now >= open && now < close ? "시장 OPEN" : "시장 CLOSED") : label;
  }
  return h("span", { className: "market-status", "data-market-status": label }, label);
}

export function PortfolioRiskPanel({ dashboard }) {
  const result = dataOf(dashboard?.analysis)?.result;
  const risk = result?.portfolioRisk ?? result?.risk;
  return h("section", { className: "panel portfolio-risk-panel", "data-portfolio-risk": risk ? "available" : "unavailable" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Risk impact"), h("h2", null, "포트폴리오 위험"))),
    risk
      ? h("dl", { className: "decision-metrics" },
        ...Object.entries(risk).map(([key, value]) => h("div", { key }, h("dt", null, key), h("dd", null, typeof value === "number" ? formatRatio(value) : value ?? UNKNOWN_TEXT))))
      : h("p", { className: "empty" }, "서버 위험 데이터 없음 · 정책 판정은 표시하지 않습니다"));
}

export function PortfolioPositionTable({ section, analysis }) {
  const portfolio = dataOf(section);
  const positions = portfolio?.positions ?? [];
  const weights = new Map((dataOf(analysis)?.result?.positions ?? []).map(item => [item.symbol, item.weight]));
  const account = portfolio?.account ?? {};
  const amounts = [
    ["총 평가금액", account.marketValueAmounts],
    ["총 손익", account.profitLossAmounts],
    ["현금 잔고", account.cashBalanceAmounts]
  ].filter(([, value]) => value && Object.keys(value).length);
  return h("section", { className: "panel position-management" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "Position management"), h("h2", null, "보유 포지션"))),
    amounts.length ? h("dl", { className: "decision-metrics position-summary" },
      ...amounts.map(([label, values]) => h("div", { key: label }, h("dt", null, label), h("dd", null,
        Object.entries(values).map(([currency, value]) => h("span", { className: "amount", key: currency }, formatAmount(currency, value))))))
    ) : null,
    positions.length
      ? h("div", { className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "보유 포지션 표" },
        h("table", null,
          h("thead", null, h("tr", null, ...["종목", "현재가", "수량", "평가금액", "비중", "손익", "Risk", "Next Catalyst", "판단", "행동"].map(label => h("th", { key: label, scope: "col" }, label)))),
          h("tbody", null, ...positions.map(position => h("tr", { key: position.symbol },
            h("th", { scope: "row" }, h("a", { href: `/stocks/${encodeURIComponent(position.symbol)}` }, position.symbol ?? UNKNOWN_TEXT)),
            h("td", null, formatAmount(position.currency, position.lastPrice)),
            h("td", null, formatQuantity(position.quantity)),
            h("td", null, formatAmount(position.currency, position.marketValueAmount)),
            h("td", null, formatRatio(weights.get(position.symbol))),
            h("td", null, formatSignedAmount(position.currency, position.profitLossAmount)),
            h("td", null, position.risk ?? position.riskLevel ?? UNKNOWN_TEXT),
            h("td", null, position.nextCatalyst ?? position.catalyst ?? UNKNOWN_TEXT),
            h("td", null, position.decision ?? UNKNOWN_TEXT),
            h("td", null, h("a", { className: "button-link secondary", href: `/orders?symbol=${encodeURIComponent(position.symbol ?? "")}` }, "주문 작성")))))))
      : h("p", { className: "empty" }, "보유 포지션이 없습니다"));
}

export function DecisionCenter({ dashboard, events, onOrder, children }) {
  return h("div", { className: "decision-center" },
    h(ActionQueue, { items: buildActionItems({ dashboard, events }), onOrder }),
    h(PortfolioRiskPanel, { dashboard }),
    children);
}
