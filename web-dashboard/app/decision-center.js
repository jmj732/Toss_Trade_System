"use client";

import { cloneElement, createElement as h } from "react";

import {
  classifyProposalExpiry,
  formatAmount,
  formatFreshness,
  formatInstant,
  formatQuantity,
  formatRatio,
  formatSignedAmount,
  UNKNOWN_TEXT
} from "../lib/format.js";
import { ORDER_STATUS_LABELS, orderStatusLabel } from "./dashboard-view.js";

function dataOf(section) {
  if (!section || section.unavailable || section.status === "ERROR") return null;
  return section.data ?? section;
}

// D-13: busyOrderId 가 스칼라·Set·배열 중 무엇이든 처리한다(dashboard-view isOrderBusy 와 동일 규약).
function isOrderBusy(busy, id) {
  if (busy == null) return false;
  if (typeof busy === "string") return busy === id;
  if (busy instanceof Set) return busy.has(id);
  if (Array.isArray(busy)) return busy.includes(id);
  return false;
}

const PRIORITY_META = {
  URGENT: { label: "즉시", badge: "badge-pill--danger" },
  HIGH: { label: "높음", badge: "badge-pill--warn" },
  MEDIUM: { label: "보통", badge: "badge-pill--info" },
  LOW: { label: "낮음", badge: "badge-pill--neutral" }
};
const TYPE_LABELS = { ORDER: "주문", EVENT: "이벤트", DATA_QUALITY: "데이터 품질" };
const PRIORITY_ORDER = ["URGENT", "HIGH", "MEDIUM", "LOW"];

// 서버 timestamp − now 만으로 만드는 카운트다운 텍스트(값 계산 아님, 시간 차 표기만).
const ISO_PREFIX = /^\d{4}-\d{2}-\d{2}T/;

function deadlineCountdown(deadline, now = Date.now()) {
  if (!deadline) return null;
  const ms = Date.parse(deadline);
  if (!Number.isFinite(ms)) return null;
  const diff = ms - now;
  if (diff <= 0) return "만료됨";
  const minutes = Math.ceil(diff / 60000);
  if (minutes < 60) return `만료 ${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `만료 ${hours}시간 전`;
  return `만료 ${Math.floor(hours / 24)}일 전`;
}

// fact value 변환: 주문 상태 enum → 한국어, ISO timestamp → 신선도 표기, 그 외 원문.
function renderFactValue(value) {
  if (typeof value === "string") {
    if (ORDER_STATUS_LABELS[value]) return orderStatusLabel(value);
    if (ISO_PREFIX.test(value)) return formatFreshness(value);
  }
  return value;
}

function priorityBadges(items) {
  const counts = new Map();
  for (const item of items) {
    counts.set(item.priority, (counts.get(item.priority) ?? 0) + 1);
  }
  return PRIORITY_ORDER
    .filter(priority => counts.has(priority))
    .map(priority => h("span", {
      key: priority,
      className: `badge-pill ${PRIORITY_META[priority].badge}`
    }, `${PRIORITY_META[priority].label} ${counts.get(priority)}`));
}

function orderButtons(item, { onOrderAction, busy }) {
  const actionable = item.statusCode === "PROPOSED";
  const expired = item.deadline
    ? classifyProposalExpiry({ expiresAt: item.deadline }) === "expired"
    : false;
  // D-03/D-42: 승인 대기(PROPOSED)이면서 만료되지 않은 항목만 처리 가능. 나머지는 사유와 함께 비활성.
  const reason = !actionable
    ? "승인 대기 상태에서만 처리할 수 있습니다"
    : expired
      ? "만료된 제안은 승인할 수 없습니다"
      : null;
  return h("div", { className: "action-item-actions" },
    h("button", {
      type: "button",
      disabled: busy || !actionable || expired,
      onClick: () => onOrderAction?.(item.id, "approve")
    }, "승인 검토"),
    h("button", {
      type: "button",
      className: "danger",
      disabled: busy || !actionable,
      onClick: () => onOrderAction?.(item.id, "cancel")
    }, "주문 취소"),
    item.symbol
      ? h("a", {
        className: "button-link secondary",
        href: `/stocks/${encodeURIComponent(item.symbol)}`
      }, "종목 보기")
      : null,
    reason ? h("p", { className: "action-item-reason" }, reason) : null);
}

function eventButtons(item) {
  return h("div", { className: "action-item-actions" },
    h("a", {
      className: "button-link secondary",
      href: `/events?event=${encodeURIComponent(item.id)}`
    }, "영향 보기"),
    item.symbol
      ? h("a", {
        className: "button-link secondary",
        href: `/stocks/${encodeURIComponent(item.symbol)}`
      }, "종목 보기")
      : null);
}

function qualityButtons(onRefresh) {
  if (!onRefresh) return null;
  return h("div", { className: "action-item-actions" },
    h("button", { type: "button", className: "secondary", onClick: () => onRefresh() }, "다시 동기화"));
}

function ActionQueueItem({ item, onOrderAction, onRefresh, busyOrderId }) {
  const busy = isOrderBusy(busyOrderId, item.id);
  const meta = PRIORITY_META[item.priority] ?? PRIORITY_META.LOW;
  const countdown = deadlineCountdown(item.deadline);
  return h("li", {
    className: "action-item",
    "data-action-priority": item.priority,
    "data-action-type": item.type
  },
    h("div", { className: "action-item-heading" },
      h("span", { className: `badge-pill ${meta.badge}` }, meta.label),
      h("span", { className: "badge-pill badge-pill--neutral" }, TYPE_LABELS[item.type] ?? item.type),
      h("strong", null, item.title),
      item.symbol ? h("span", { className: "metric-label" }, item.symbol) : null),
    Array.isArray(item.facts) && item.facts.length
      ? h("dl", { className: "action-item-facts" },
        ...item.facts.map((fact, index) => h("div", { key: `${fact.label}-${index}` },
          h("dt", null, fact.label),
          h("dd", null, renderFactValue(fact.value)))))
      : null,
    countdown ? h("p", { className: "action-item-deadline" }, countdown) : null,
    item.type === "ORDER"
      ? orderButtons(item, { onOrderAction, busy })
      : item.type === "EVENT"
        ? eventButtons(item)
        : qualityButtons(onRefresh));
}

export function ActionQueue({ items = [], state, onOrderAction, busyOrderId, onRefresh, lastChecked }) {
  const urgent = items.some(item => item.priority === "URGENT");
  return h("section", {
    className: "panel action-queue",
    "data-home-region": "decision-queue",
    "data-surface-state": state,
    "aria-label": "결정 센터"
  },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "Action Queue"), h("h2", null, "결정 센터")),
      h("div", { className: "action-queue-badges" },
        urgent ? h("span", { className: "badge-pill badge-pill--danger" }, "즉시 확인") : null,
        ...priorityBadges(items))),
    items.length
      ? h("ol", { className: "action-list" }, ...items.map(item => h(ActionQueueItem, {
        key: item.key ?? `${item.type}-${item.id}`,
        item,
        onOrderAction,
        onRefresh,
        busyOrderId
      })))
      : h("p", { className: "empty action-queue-empty" },
        lastChecked
          ? `확인할 결정이 없습니다 · 마지막 확인 ${formatInstant(lastChecked)}`
          : "확인할 결정이 없습니다"));
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

// BC-4: riskEvaluation.items 의 scope enum → 사람이 읽는 라벨. 미등록 scope 는 원문 노출.
const RISK_SCOPE_LABELS = {
  POSITION: "종목 집중도",
  CURRENCY: "통화 집중도",
  CASH: "현금 비중"
};

function riskScopeLabel(scope) {
  return RISK_SCOPE_LABELS[scope] ?? scope ?? "";
}

// BC-4: 서버 riskEvaluation 섹션(있으면)만 렌더한다. usageRatio 등 판정값은 프론트에서 만들지 않는다.
export function PortfolioRiskPanel({ dashboard }) {
  const section = dashboard?.riskEvaluation;
  const data = section && !section.unavailable ? section.data : null;
  const items = Array.isArray(data?.items) ? data.items : null;
  const unknownFields = Array.isArray(section?.unknownFields) ? section.unknownFields : [];

  if (!items || items.length === 0) {
    const reason = section?.unavailableReason
      ? ` · ${section.unavailableReason}`
      : "";
    return h("section", {
      className: "panel portfolio-risk-panel",
      "data-portfolio-risk": "unavailable"
    },
      h("header", null, h("div", null,
        h("p", { className: "eyebrow" }, "Risk impact"), h("h2", null, "포트폴리오 위험"))),
      h("p", { className: "empty" }, `서버 위험 평가 없음${reason}`));
  }

  // breached 항목을 앞으로 정렬(서버 값만 사용, 산술 없음).
  const ordered = [...items].sort((a, b) => Number(Boolean(b.breached)) - Number(Boolean(a.breached)));
  const footnote = [
    data.policyVersion != null ? `정책 v${data.policyVersion}` : null,
    data.evaluatedAt ? `기준 ${formatFreshness(data.evaluatedAt)}` : null
  ].filter(Boolean).join(" · ");
  return h("section", {
    className: "panel portfolio-risk-panel",
    "data-portfolio-risk": "available"
  },
    h("header", null, h("div", null,
      h("p", { className: "eyebrow" }, "Risk impact"), h("h2", null, "포트폴리오 위험"))),
    // unknownFields: 값 누락으로 판정하지 못한 항목이 있음을 조용히 빠뜨리지 않고 노출한다.
    unknownFields.length
      ? h("p", { className: "disclaimer", "data-risk-unknown": "true" },
        `일부 항목 확인 불가 · ${unknownFields.join(", ")}`)
      : null,
    h("ul", { className: "list risk-eval-list" }, ...ordered.map(item => h("li", {
      key: item.key ?? item.subject ?? item.scope,
      "data-risk-breached": item.breached ? "true" : "false"
    },
      h("div", { className: "risk-eval-head" },
        h("strong", null, item.subject ?? item.scope ?? UNKNOWN_TEXT),
        h("span", { className: "metric-label" }, riskScopeLabel(item.scope)),
        item.breached
          ? h("span", { className: "badge-pill badge-pill--danger" }, "한도 초과")
          : null),
      h("dl", { className: "decision-metrics risk-eval-metrics" },
        h("div", null, h("dt", null, "현재"), h("dd", null, formatRatio(item.current))),
        h("div", null, h("dt", null, "한도"), h("dd", null, formatRatio(item.limit))),
        h("div", null, h("dt", null, "사용률"), h("dd", null, formatRatio(item.usageRatio))))))),
    footnote ? h("small", { className: "metric-freshness" }, footnote) : null);
}

// BC-7: kill switch engaged === true 일 때만 렌더하는 차단 배너. USER scope("내 계정 기준").
// engaged === null(미설정)이나 null(미확정)은 배너를 띄우지 않는다 — Settings 에서만 구분 노출.
export function KillSwitchBanner({ killSwitch }) {
  if (killSwitch?.engaged !== true) {
    return null;
  }
  return h("div", {
    className: "kill-switch-banner", role: "alert", "data-kill-switch": "engaged"
  },
    h("strong", { className: "kill-switch-banner-title" }, "거래 중지됨 · 내 계정 기준"),
    killSwitch.reason ? h("span", { className: "kill-switch-banner-reason" }, killSwitch.reason) : null,
    killSwitch.changedAt
      ? h("span", { className: "kill-switch-banner-time" }, `변경 ${formatInstant(killSwitch.changedAt)}`)
      : null);
}

// BC-7: Settings 의 kill switch 상태 표시. 3상태를 명확히 구분한다.
// - killSwitch == null            → 미확정(조회 실패/로딩): "상태 확인 필요"
// - engaged === true              → "거래 중지됨"
// - engaged === false             → "정상 (거래 가능)"
// - engaged === null(미설정)      → "상태 미설정" (절대 "정상"으로 접지 않는다)
export function KillSwitchStatus({ killSwitch }) {
  let label;
  let modifier;
  let state;
  if (killSwitch == null) {
    label = "상태 확인 필요";
    modifier = "warn";
    state = "unknown";
  } else if (killSwitch.engaged === true) {
    label = "거래 중지됨";
    modifier = "danger";
    state = "engaged";
  } else if (killSwitch.engaged === false) {
    label = "정상 (거래 가능)";
    modifier = "ok";
    state = "released";
  } else {
    label = "상태 미설정";
    modifier = "neutral";
    state = "unset";
  }
  return h("div", { className: "kill-switch-status", "data-kill-switch-state": state },
    h("span", { className: "metric-label" }, "거래 중지 (내 계정 기준)"),
    h("span", { className: `badge-pill badge-pill--${modifier}` }, label),
    killSwitch?.engaged === true && killSwitch.reason
      ? h("span", { className: "kill-switch-status-reason" }, killSwitch.reason)
      : null);
}

// BC-2: 보유 포지션 판단은 최상위 positionDecisions 섹션으로 내려온다. 행 기준 집합은
// 보유 포지션이며, 분석이 없는 종목도 행이 있고 판단 필드만 비어 있다. 판정 근거(riskLevel/
// decision/confidence)는 프론트에서 계산하지 않고 서버 값만 노출한다(DESIGN 원칙 4).
const RISK_LEVEL_LABELS = { LOW: "낮음", MEDIUM: "보통", HIGH: "높음" };
const DECISION_LABELS = { BUY: "매수", ADD: "추가 매수", HOLD: "보유", REDUCE: "축소", SELL: "매도", WAIT: "대기" };

// riskLevel === null 은 "안전"이 아니라 판정 근거 없음이다. LOW 로 접거나 빈칸으로 뭉개지
// 않고 "확인 불가" 로 명시한다.
function renderRiskCell(entry) {
  const level = entry?.riskLevel ?? null;
  if (level == null) {
    return h("span", { className: "metric-label", "data-risk-level": "unknown" }, "확인 불가");
  }
  return h("span", { className: "badge-pill badge-pill--neutral", "data-risk-level": level },
    RISK_LEVEL_LABELS[level] ?? level);
}

// 두 원인을 같은 문구로 합치지 않는다:
// - decisionRunId == null              → 분석한 적 없음.
// - decisionRunId != null && decision == null → 분석했으나 지표 부족으로 판단 없음.
// 판단이 있으면 확정 표현("매수") 대신 규칙 산출물임을 드러내고(신뢰도·규칙 버전·기준 시각) 노출한다.
function renderDecisionCell(entry) {
  if (!entry || entry.decisionRunId == null) {
    return h("span", { className: "metric-label", "data-decision-state": "none" }, "분석 없음");
  }
  if (entry.decision == null) {
    return h("span", { className: "metric-label", "data-decision-state": "insufficient" },
      "판단 보류 · 지표 부족");
  }
  const label = DECISION_LABELS[entry.decision] ?? entry.decision;
  const confidence = entry.confidence == null ? "" : ` (신뢰도 ${formatRatio(entry.confidence)})`;
  const footnote = [
    entry.decisionRuleVersion || null,
    entry.decisionAsOf ? formatFreshness(entry.decisionAsOf) : null
  ].filter(Boolean).join(" · ");
  return h("div", { className: "position-decision", "data-decision-state": "decided" },
    h("span", null, `규칙 판단: ${label}${confidence}`),
    footnote ? h("small", { className: "metric-freshness" }, footnote) : null);
}

function renderCatalystCell(entry) {
  if (!entry?.nextCatalystAt && !entry?.nextCatalystType) {
    return h("span", { className: "metric-label", "data-catalyst-state": "none" }, "예정 이벤트 없음");
  }
  return h("div", { className: "position-catalyst", "data-catalyst-state": "scheduled" },
    h("span", null, entry.nextCatalystType ?? UNKNOWN_TEXT),
    entry.nextCatalystAt
      ? h("small", { className: "metric-freshness" }, formatInstant(entry.nextCatalystAt))
      : null);
}

// 열 정의를 배열 상수로 두고, Risk·판단 열은 positionDecisions 계약 존재 여부로 켠다.
// Catalyst 도 positionDecisions 서버 계약 값만 노출한다. 날짜나 위험도는 여기서 추정하지 않는다.
const POSITION_COLUMNS = [
  { key: "symbol", label: "종목", always: true,
    cell: (position) => h("th", { scope: "row" },
      h("a", { href: `/stocks/${encodeURIComponent(position.symbol ?? "")}` }, position.symbol ?? UNKNOWN_TEXT)) },
  { key: "lastPrice", label: "현재가", always: true, numeric: true,
    cell: (position) => h("td", { className: "numeric" }, formatAmount(position.currency, position.lastPrice)) },
  { key: "quantity", label: "수량", always: true, numeric: true,
    cell: (position) => h("td", { className: "numeric" }, formatQuantity(position.quantity)) },
  { key: "marketValue", label: "평가금액", always: true, numeric: true,
    cell: (position) => h("td", { className: "numeric" }, formatAmount(position.currency, position.marketValueAmount)) },
  { key: "weight", label: "비중", always: true, numeric: true,
    cell: (position, ctx) => h("td", { className: "numeric" }, formatRatio(ctx.weights.get(position.symbol))) },
  { key: "profitLoss", label: "손익", always: true, numeric: true,
    cell: (position) => h("td", { className: "numeric" }, formatSignedAmount(position.currency, position.profitLossAmount)) },
  // BC-2 계약 필드 — positionDecisions 섹션이 있으면 켜진다.
  { key: "risk", label: "Risk", decisionColumn: true,
    cell: (position, ctx) => h("td", { "data-position-risk": position.symbol },
      renderRiskCell(ctx.decisions.get(position.symbol))) },
  { key: "catalyst", label: "Next Catalyst", enabled: (ctx) => ctx.hasCatalysts,
    cell: (position, ctx) => h("td", { "data-position-catalyst": position.symbol },
      renderCatalystCell(ctx.decisions.get(position.symbol))) },
  { key: "decision", label: "판단", decisionColumn: true,
    cell: (position, ctx) => h("td", { "data-position-decision": position.symbol },
      renderDecisionCell(ctx.decisions.get(position.symbol))) },
  { key: "action", label: "행동", always: true,
    cell: (position) => h("td", null, h("a", {
      className: "button-link secondary",
      href: `/orders?symbol=${encodeURIComponent(position.symbol ?? "")}`
    }, "주문 작성")) }
];

function enabledColumns(ctx) {
  return POSITION_COLUMNS.filter(column =>
    column.always || (column.decisionColumn && ctx.hasDecisions) || column.enabled?.(ctx));
}

export function PortfolioPositionTable({ section, analysis, positionDecisions, limit, caption = "보유 포지션" }) {
  const portfolio = dataOf(section);
  const allPositions = portfolio?.positions ?? [];
  const limited = typeof limit === "number" && limit > 0 && allPositions.length > limit;
  const positions = limited ? allPositions.slice(0, limit) : allPositions;
  const weights = new Map((dataOf(analysis)?.result?.positions ?? []).map(item => [item.symbol, item.weight]));
  // 섹션이 있고 조회 가능할 때만 판단 열을 켠다(unavailable 이면 열 자체를 숨긴다).
  const decisionRows = dataOf(positionDecisions);
  const hasDecisions = Array.isArray(decisionRows);
  const decisions = new Map((decisionRows ?? []).map(item => [item.symbol, item]));
  const hasCatalysts = (decisionRows ?? []).some(item => item.nextCatalystAt || item.nextCatalystType);
  const ctx = { weights, decisions, hasDecisions, hasCatalysts };
  const columns = enabledColumns(ctx);
  return h("section", { className: "panel position-management" },
    h("header", null, h("div", null,
      h("p", { className: "eyebrow" }, "Position management"), h("h2", null, caption))),
    positions.length
      ? h("div", { className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "보유 포지션 표" },
        h("table", null,
          h("thead", null, h("tr", null, ...columns.map(column =>
            h("th", { key: column.key, scope: "col", className: column.numeric ? "numeric" : undefined }, column.label)))),
          h("tbody", null, ...positions.map(position =>
            h("tr", { key: position.symbol }, ...columns.map(column =>
              cloneElement(column.cell(position, ctx), { key: column.key })))))))
      : h("p", { className: "empty" }, "보유 포지션이 없습니다"),
    limited
      ? h("a", { className: "button-link secondary position-see-all", href: "/portfolio" }, "전체 보기")
      : null);
}

// /portfolio 요약 지표. 서버가 준 계좌 금액만 노출하고 비중 등 파생값은 프론트에서 계산하지 않는다.
function summaryAmounts(values, signed = false) {
  const fmt = signed ? formatSignedAmount : formatAmount;
  const currencies = values ? Object.keys(values).filter(currency => values[currency] != null) : [];
  if (currencies.length === 0) {
    return UNKNOWN_TEXT;
  }
  return currencies.map(currency => fmt(currency, values[currency])).join(" · ");
}

export function PortfolioSummary({ dashboard }) {
  const account = dataOf(dashboard?.portfolio)?.account ?? null;
  return h("section", {
    className: "panel portfolio-summary",
    "data-portfolio-summary": account ? "available" : "empty"
  },
    h("header", null, h("div", null,
      h("p", { className: "eyebrow" }, "Summary"), h("h2", null, "자산 요약"))),
    account
      ? h("dl", { className: "decision-metrics portfolio-summary-metrics" },
        h("div", null, h("dt", null, "총 평가금액"), h("dd", null, summaryAmounts(account.marketValueAmounts))),
        h("div", null, h("dt", null, "오늘 손익"), h("dd", null, summaryAmounts(account.dailyProfitLossAmounts, true))),
        h("div", null, h("dt", null, "총 손익"), h("dd", null, summaryAmounts(account.profitLossAmounts, true))))
      : h("p", { className: "empty" }, "계좌 요약을 불러올 수 없습니다"));
}
