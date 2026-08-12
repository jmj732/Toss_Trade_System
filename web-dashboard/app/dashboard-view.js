import { createElement as h } from "react";

import { MarketCandleChart } from "./market-candle-chart.js";
import {
  formatAmount,
  formatSignedAmount,
  formatQuantity,
  formatRatio,
  formatFreshness,
  formatInstant,
  classifyProposalExpiry,
  proposalExpiryBadge,
  UNKNOWN_TEXT
} from "../lib/format.js";
import { describeError } from "../lib/error-messages.js";

// D-03: OrderIntentStatus 13종 전부를 한국어로 매핑한다. 이 맵은 이 파일이 정본이며
// orders-view.js 가 import 해 재사용한다(리터럴 중복 금지).
export const ORDER_STATUS_LABELS = {
  PROPOSED: "승인 대기",
  APPROVED: "승인됨",
  REVALIDATING: "재검증 중",
  SUBMISSION_PENDING: "전송 대기",
  RECONCILIATION_REQUIRED: "대사 필요",
  MANUAL_REVIEW_REQUIRED: "수동 검토 필요",
  ACTIVE: "체결 진행 중",
  PARTIALLY_COMPLETED: "부분 체결",
  COMPLETED: "체결 완료",
  CANCELED: "취소됨",
  REJECTED: "거부됨",
  EXPIRED: "만료됨",
  BLOCKED: "차단됨"
};

// D-03: 배지 색 상태. badge-pill--<state> 로 렌더한다(ok|warn|danger|info|neutral).
const ORDER_STATUS_BADGE_STATES = {
  PROPOSED: "info",
  APPROVED: "info",
  ACTIVE: "info",
  SUBMISSION_PENDING: "info",
  REVALIDATING: "info",
  PARTIALLY_COMPLETED: "warn",
  COMPLETED: "ok",
  MANUAL_REVIEW_REQUIRED: "warn",
  RECONCILIATION_REQUIRED: "warn",
  BLOCKED: "warn",
  CANCELED: "danger",
  REJECTED: "danger",
  EXPIRED: "danger"
};

// D-03: 미등록 상태도 은폐하지 않고 원문을 노출한다.
export function orderStatusLabel(status) {
  if (!status) {
    return null;
  }
  return ORDER_STATUS_LABELS[status] ?? `알 수 없는 상태: ${status}`;
}

export function orderStatusBadgeState(status) {
  return ORDER_STATUS_BADGE_STATES[status] ?? "neutral";
}

// D-42: 만료 상태 배지의 색 상태.
const EXPIRY_BADGE_STATES = { expired: "danger", expiring: "warn", unknown: "neutral" };

// D-03/D-42: 주문 행에 공통으로 붙는 상태 배지와 만료 배지를 렌더한다.
export function OrderStatusBadge({ status }) {
  const label = orderStatusLabel(status);
  return label
    ? h("span", { className: `badge-pill badge-pill--${orderStatusBadgeState(status)}` }, label)
    : null;
}

export function OrderExpiryBadge({ state }) {
  const label = proposalExpiryBadge(state);
  return label
    ? h("span", { className: `badge-pill badge-pill--${EXPIRY_BADGE_STATES[state] ?? "neutral"}` }, label)
    : null;
}

// D-42: 주문 제안의 생성/만료 시각을 한국어로 렌더한다. createdAt null → UNKNOWN_TEXT,
// expiresAt null → "만료 없음"(만료됨이 아니다).
export function OrderTiming({ order }) {
  const expiryText = order.expiresAt == null ? "만료 없음" : formatInstant(order.expiresAt);
  return h("span", { className: "order-timing" },
    `기준 ${formatFreshness(order.createdAt)} · 만료 ${expiryText}`);
}

// D-30: 미지 값이 조용히 "매도" 로 접히지 않도록 명시적으로만 매핑한다.
const SIDE_LABELS = { BUY: "매수", SELL: "매도" };
// D-20: 현금 잔고 확인 상태 enum 을 한국어로 매핑한다.
const CASH_STATUS_LABELS = { KNOWN: "확인됨", UNKNOWN: "확인 필요" };
// D-05: stale 근거 코드를 한국어 보조 문구로 매핑한다.
const STALE_REASON_LABELS = {
  SYNC_IN_PROGRESS: "최신 동기화 진행 중",
  LATEST_SYNC_FAILED: "최근 동기화 실패",
  SNAPSHOT_TOO_OLD: "기준 시각 만료",
  LIVE_SYNC_FAILED: "실시간 동기화 실패"
};
// D-26/D-06: 백엔드 내부 필드·섹션 경로를 한국어 라벨로 매핑한다. 미등록 키는 노출하지 않는다.
const FIELD_LABELS = {
  "account.cashBalance": "현금 잔고",
  "account.cashBalanceStatus": "현금 잔고 상태",
  BUYING_POWER: "주문 가능 금액",
  BUYING_POWER_KRW: "KRW 주문 가능 금액",
  BUYING_POWER_USD: "USD 주문 가능 금액",
  POSITIONS: "보유 종목",
  ACCOUNT: "계좌"
};

function knownSide(side) {
  return Object.prototype.hasOwnProperty.call(SIDE_LABELS, side);
}

// D-13: busyOrderId 가 스칼라(현행)든 Set/배열(권장)이든 모두 처리한다.
function isOrderBusy(busy, id) {
  if (busy == null) {
    return false;
  }
  if (typeof busy === "string") {
    return busy === id;
  }
  if (busy instanceof Set) {
    return busy.has(id);
  }
  if (Array.isArray(busy)) {
    return busy.includes(id);
  }
  return false;
}

// D-26/D-06: 등록된 키만 한국어로, 미등록 키는 개수로만 요약해 내부 경로 노출을 막는다.
function labelFields(keys) {
  const labels = [];
  let others = 0;
  for (const key of keys) {
    if (FIELD_LABELS[key]) {
      labels.push(FIELD_LABELS[key]);
    } else {
      others += 1;
    }
  }
  if (others > 0) {
    labels.push(`기타 항목 ${others}건`);
  }
  return labels;
}

function sectionData(section) {
  const data = section.data;
  return data && !Array.isArray(data) ? data : null;
}

// D-06: 백엔드가 내려주는 partial 신호를 읽는다. 포트폴리오는 data.partial,
// 분석은 data.result.quality.partial 에 있다.
function isPartial(section) {
  const data = sectionData(section);
  if (!data) {
    return false;
  }
  return data.partial === true || data.result?.quality?.partial === true;
}

function missingSectionKeys(section) {
  const data = sectionData(section);
  return data?.missingSections ?? [];
}

function Quality({ section, region }) {
  const values = [];
  if (section.stale) {
    const reasonCode = sectionData(section)?.staleReason;
    const reason = reasonCode ? STALE_REASON_LABELS[reasonCode] ?? null : null;
    values.push(["stale", reason ? `지연 · ${reason}` : "지연"]);
  }
  if (section.unknown) values.push(["unknown", "확인 필요"]);
  if (section.unavailable) values.push(["unavailable", "불러오기 실패"]);
  if (section.empty) values.push(["empty", "결과 없음"]);
  const partial = isPartial(section);
  if (partial) values.push(["partial", "일부 누락"]);
  // D-06: partial 이면 "최신" 을 단언하지 않는다.
  if (values.length === 0) values.push(["available", "최신"]);

  const fieldLabels = labelFields([
    ...(section.unknownFields ?? []),
    ...missingSectionKeys(section)
  ]);
  return h("div", {
    className: "quality",
    "data-dashboard-region": region
  },
    ...values.map(([className, label]) => h("span", { className, key: className }, label)),
    fieldLabels.length
      ? h("small", null, fieldLabels.join(", "))
      : null);
}

function Section({ title, section, className = "", qualityRegion, children }) {
  return h("section", { className: `panel signal-panel ${className}`.trim() },
    h("header", null, h("h2", null, title), h(Quality, { section, region: qualityRegion })),
    section.unavailable
      ? h("p", { className: "empty" }, describeError(section.unavailableReason) ?? "불러오기 실패")
      : children);
}

// D-21/D-22: 통화별 금액 맵을 항상 포맷터 경유로, 통화 접두로 렌더한다.
function Amounts({ values = {}, signed = false }) {
  const entries = Object.entries(values);
  const format = signed ? formatSignedAmount : formatAmount;
  return entries.length
    ? h("span", null, entries.map(([currency, amount]) =>
      h("span", { className: "amount", key: currency }, format(currency, amount))))
    : h("span", { className: "unknown-text" }, UNKNOWN_TEXT);
}

function Portfolio({ section }) {
  const portfolio = section.data;
  const account = portfolio?.account;
  const positions = portfolio?.positions ?? [];
  return h(Section, {
    title: "포트폴리오", section, className: "portfolio-panel",
    qualityRegion: "portfolio-quality"
  },
    h("div", { className: "portfolio-hero" },
      h("div", null,
        h("div", { "data-dashboard-region": "portfolio-lead" },
        h("span", { className: "metric-label" }, "총 평가금액"),
        h("strong", { className: "metric-value metric-value-large" },
          h(Amounts, { values: account?.marketValueAmounts })),
        // D-05: 총 평가금액이 언제 기준 데이터인지 함께 노출한다.
        h("small", { className: "metric-freshness" },
          `기준 ${formatFreshness(portfolio?.completedAt ?? section.asOf)}`))),
      h("div", { className: "portfolio-hero-secondary" },
        h("span", null, "총 손익"),
        h("strong", null, h(Amounts, { values: account?.profitLossAmounts, signed: true })))),
    h("div", { className: "summary" },
      h("div", null, h("span", null, "계좌"), h("strong", null,
        account?.displayAccountNumber ?? UNKNOWN_TEXT)),
      // D-20: 현금 잔고 확인 상태를 enum 원문 대신 한국어로, 상태 라벨로 노출한다.
      h("div", null, h("span", null, "현금 잔고 상태"),
        h("strong", null,
          CASH_STATUS_LABELS[account?.cashBalanceStatus] ?? UNKNOWN_TEXT))),
    h("h3", null, "주문 가능 금액"),
    h("div", { className: "buying-power" },
      ...["KRW", "USD"].map(currency => h("div", { key: currency },
        h("span", null, currency),
        h("strong", null, formatAmount(
          currency, portfolio?.buyingPower?.[currency]?.cashBuyingPower))))),
    h("h3", null, "보유 종목"),
    positions.length
      ? h("div", { className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "보유 종목 표" }, h("table", null,
        h("thead", null, h("tr", null,
          ...["종목", "종목명", "수량", "평가금액", "P/L"].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...positions.map(position => h("tr", { key: position.symbol },
          h("td", null, position.symbol ?? UNKNOWN_TEXT),
          h("td", null, position.name ?? UNKNOWN_TEXT),
          h("td", null, formatQuantity(position.quantity)),
          h("td", null, formatAmount(position.currency, position.marketValueAmount)),
          h("td", null, formatSignedAmount(position.currency, position.profitLossAmount)))))))
      : h("p", { className: "empty" }, "보유 종목이 없습니다."));
}

// D-05: 분석 결과의 기준 시각(asOf)과 상태(status)를 배지로 노출한다.
const ANALYSIS_STATUS_LABELS = { COMPLETED: "정상", DEGRADED: "품질 저하" };

function Analysis({ section }) {
  const empty = section.unavailableReason === "ANALYSIS_RESULT_NOT_FOUND";
  if (empty) {
    return h(Section, {
      title: "분석", section: { ...section, unavailable: false, empty }, className: "analysis-panel"
    }, h("p", { className: "empty" }, "분석 결과가 아직 없습니다."));
  }
  const result = section.data?.result;
  const totals = result?.currencyTotals ?? [];
  const positions = result?.positions ?? [];
  const statusLabel = result?.status
    ? ANALYSIS_STATUS_LABELS[result.status] ?? `알 수 없는 상태: ${result.status}`
    : null;
  return h(Section, { title: "분석", section, className: "analysis-panel" },
    h("p", { className: "disclaimer" },
      `기준 ${formatFreshness(result?.asOf ?? section.data?.completedAt ?? section.asOf)}`,
      statusLabel ? ` · 상태 ${statusLabel}` : ""),
    h("h3", null, "통화별 평가금액"),
    totals.length
      ? h("div", { className: "summary" }, ...totals.map(total =>
        h("div", { key: total.currency },
          h("span", null, total.currency),
          h("strong", null,
            `${formatAmount(total.currency, total.marketValue)}`
            + ` · P/L ${formatSignedAmount(total.currency, total.profitLoss)}`))))
      : h("p", { className: "empty" }, "통화별 데이터가 없습니다."),
    h("h3", null, "종목 비중"),
    positions.length
      ? h("ul", { className: "list" }, ...positions.map(position =>
        h("li", { key: `${position.currency}-${position.symbol}` },
          h("strong", null, position.symbol ?? UNKNOWN_TEXT),
          h("span", null, `${position.currency ?? UNKNOWN_TEXT} · ${formatRatio(position.weight)}`))))
      : h("p", { className: "empty" }, "종목 분석이 없습니다."));
}

function Events({ section }) {
  const events = section.data ?? [];
  return h(Section, { title: "이벤트", section, className: "event-panel" },
    events.length
      ? h("ul", { className: "list" }, ...events.map(event =>
        h("li", { key: event.id },
          h("strong", null, event.summary ?? UNKNOWN_TEXT),
          h("span", null, `${event.type ?? UNKNOWN_TEXT} · ${
            event.affectedSymbols?.join(", ") || UNKNOWN_TEXT}`))))
      : h("p", { className: "empty" }, "검토할 이벤트가 없습니다."));
}

function Proposals({ section, busyOrderId, onOrderAction }) {
  const orders = section.data ?? [];
  return h(Section, { title: "주문 검토", section, className: "decision-queue" },
    orders.length
      ? h("ul", { className: "list proposals" }, ...orders.map(order => {
        const sideKnown = knownSide(order.side);
        const sideLabel = sideKnown ? SIDE_LABELS[order.side] : (order.side ?? UNKNOWN_TEXT);
        // D-30: 미지 side 는 원문을 노출하되 오발주 방지를 위해 액션을 비활성화한다.
        const busy = isOrderBusy(busyOrderId, order.id);
        const priceText = order.limitPrice == null
          ? ""
          : ` @ ${formatAmount(order.currency, order.limitPrice)}`;
        // D-42: 만료된 제안은 승인을 막는다. 서버 409 는 최후 방어선이지 유일한 방어선이 아니다.
        const expiryState = classifyProposalExpiry(order);
        const expired = expiryState === "expired";
        // D-03: PROPOSED 외 상태는 표시 전용이다. 승인/취소 모두 PROPOSED 에서만 허용한다 —
        // 상태 필터가 넓어지며 화면에 APPROVED/ACTIVE/COMPLETED 등도 도달하게 됐다고 해서
        // 이미 확정·종결된 주문에 승인·취소 버튼이 눌리게 두면 안 된다.
        const actionable = order.status === "PROPOSED";
        return h("li", {
          key: order.id,
          className: `order-row order-row--${sideKnown ? order.side.toLowerCase() : "unknown"}`,
          "data-order-row": order.id
        },
          h("div", { className: "order-row-copy" },
            h("strong", { className: "order-title" }, `${sideLabel} ${order.symbol ?? UNKNOWN_TEXT}`),
            h("span", { className: "order-summary" },
              `${order.type ?? UNKNOWN_TEXT} · ${formatQuantity(order.quantity)}`
              + ` · ${order.currency ?? UNKNOWN_TEXT}${priceText}`),
            // D-03: 상태 배지. 미지 상태도 그대로 드러낸다.
            h(OrderStatusBadge, { status: order.status }),
            // D-42: 만료 배지와 생성/만료 시각.
            h(OrderExpiryBadge, { state: expiryState }),
            h(OrderTiming, { order })),
          h("div", { className: "actions order-row-actions" },
            h("button", {
              type: "button",
              disabled: busy || !sideKnown || !actionable || expired,
              onClick: () => onOrderAction(order.id, "approve")
            }, "승인 검토"),
            h("button", {
              type: "button",
              className: "danger",
              disabled: busy || !sideKnown || !actionable,
              onClick: () => onOrderAction(order.id, "cancel")
            }, "주문 취소")));
      }))
      : h("p", { className: "empty" }, "승인 대기 중인 주문이 없습니다."));
}

// ---------------------------------------------------------------------------
// 실시간 시세 보드 — loadRealtimePrices()
// ---------------------------------------------------------------------------
export function RealtimePriceTicker({ prices }) {
  const envelope = Array.isArray(prices) ? null : prices;
  const data = envelope?.data ?? prices;
  const degraded = envelope?.status === "DEGRADED" || envelope?.stale || envelope?.unknown;
  if (!Array.isArray(data) || (data.length === 0 && !degraded)) return null;
  return h("section", { className: "panel signal-panel realtime-ticker" },
    h("header", null, h("h2", null, "실시간 시세")),
    degraded ? h("p", { className: "disclaimer" }, "일부 시세 필드는 제공되지 않거나 오래됐습니다.") : null,
    h("div", { className: "realtime-ticker-list" },
      ...data.map(p => {
        const changePercent = p.changePercent ?? p.changeRate;
        const positive = (changePercent ?? 0) >= 0;
        return h("div", { key: p.symbol, className: "realtime-ticker-item" },
          h("strong", null, p.symbol ?? UNKNOWN_TEXT),
          h("span", { className: "realtime-ticker-price" },
          (p.price ?? p.lastPrice) != null ? formatAmount(p.currency ?? "USD", p.price ?? p.lastPrice) : UNKNOWN_TEXT),
          h("span", { className: positive ? "change-positive realtime-ticker-change" : "change-negative realtime-ticker-change" },
            changePercent != null ? `${positive ? "+" : ""}${(changePercent * 100).toFixed(2)}%` : UNKNOWN_TEXT));
      })));
}

export function DashboardView({
  dashboard,
  busyOrderId,
  onOrderAction,
  includePortfolio = true,
  includeOrders = true,
  realtimePrices = [],
  homeLayout = false,
  marketOverview = null,
  homeCandles = null,
  homeCandleSymbol = "",
  homeCandleInterval = "1m",
  onHomeCandleIntervalChange
}) {
  const portfolio = h(Portfolio, { key: "portfolio", section: dashboard.portfolio });
  const dashboardSections = [
    h(Analysis, { key: "analysis", section: dashboard.analysis }),
    h(Events, { key: "events", section: dashboard.pendingEvents }),
    includeOrders ? h(Proposals, {
      key: "orders",
      section: dashboard.pendingOrderProposals,
      busyOrderId,
      onOrderAction
    }) : null
  ];
  const homeMain = [
    h(MarketCandleChart, {
      key: "home-candles",
      envelope: homeCandles,
      symbol: homeCandleSymbol,
      interval: homeCandleInterval,
      onIntervalChange: onHomeCandleIntervalChange
    }),
    marketOverview ? h("div", { key: "market-overview", className: "home-market-overview" }, marketOverview) : null,
    h(RealtimePriceTicker, { key: "prices", prices: realtimePrices }),
    ...dashboardSections
  ].filter(Boolean);
  return h("main", { className: "grid dashboard-surface" },
    homeLayout
      ? h("div", { className: "home-dashboard-columns" },
        includePortfolio ? h("aside", { className: "home-dashboard-account", "aria-label": "계좌 포트폴리오" }, portfolio) : null,
        h("div", { className: "home-dashboard-main" }, homeMain))
      : [
        h(RealtimePriceTicker, { key: "prices", prices: realtimePrices }),
        includePortfolio ? portfolio : null,
        ...dashboardSections
      ]);
}
