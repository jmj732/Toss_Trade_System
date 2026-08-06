import { createElement as h } from "react";

import {
  formatAmount,
  formatSignedAmount,
  formatQuantity,
  formatRatio,
  formatFreshness,
  UNKNOWN_TEXT
} from "../lib/format.js";

// D-30: 미지 값이 조용히 "매도" 로 접히지 않도록 명시적으로만 매핑한다.
const SIDE_LABELS = { BUY: "매수", SELL: "매도" };
// D-20: 현금 잔고 확인 상태 enum 을 한국어로 매핑한다.
const CASH_STATUS_LABELS = { KNOWN: "확인됨", UNKNOWN: "확인 필요" };
// D-05: stale 근거 코드를 한국어 보조 문구로 매핑한다.
const STALE_REASON_LABELS = {
  SYNC_IN_PROGRESS: "최신 동기화 진행 중",
  LATEST_SYNC_FAILED: "최근 동기화 실패"
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

function Quality({ section }) {
  const values = [];
  if (section.stale) {
    const reasonCode = sectionData(section)?.staleReason;
    const reason = reasonCode ? STALE_REASON_LABELS[reasonCode] ?? null : null;
    values.push(["stale", reason ? `지연 · ${reason}` : "지연"]);
  }
  if (section.unknown) values.push(["unknown", "확인 필요"]);
  if (section.unavailable) values.push(["unavailable", "불러오기 실패"]);
  const partial = isPartial(section);
  if (partial) values.push(["partial", "일부 누락"]);
  // D-06: partial 이면 "최신" 을 단언하지 않는다.
  if (values.length === 0) values.push(["available", "최신"]);

  const fieldLabels = labelFields([
    ...(section.unknownFields ?? []),
    ...missingSectionKeys(section)
  ]);
  return h("div", { className: "quality" },
    ...values.map(([className, label]) => h("span", { className, key: className }, label)),
    fieldLabels.length
      ? h("small", null, fieldLabels.join(", "))
      : null);
}

function Section({ title, section, className = "", children }) {
  return h("section", { className: `panel signal-panel ${className}`.trim() },
    h("header", null, h("h2", null, title), h(Quality, { section })),
    section.unavailable
      ? h("p", { className: "empty" }, section.unavailableReason ?? "불러오기 실패")
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
  return h(Section, { title: "포트폴리오", section, className: "portfolio-panel" },
    h("div", { className: "portfolio-hero" },
      h("div", null,
        h("span", { className: "metric-label" }, "총 평가금액"),
        h("strong", { className: "metric-value metric-value-large" },
          h(Amounts, { values: account?.marketValueAmounts })),
        // D-05: 총 평가금액이 언제 기준 데이터인지 함께 노출한다.
        h("small", { className: "metric-freshness" },
          `기준 ${formatFreshness(portfolio?.completedAt)}`)),
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
  const result = section.data?.result;
  const totals = result?.currencyTotals ?? [];
  const positions = result?.positions ?? [];
  const statusLabel = result?.status
    ? ANALYSIS_STATUS_LABELS[result.status] ?? `알 수 없는 상태: ${result.status}`
    : null;
  return h(Section, { title: "분석", section, className: "analysis-panel" },
    h("p", { className: "disclaimer" },
      `기준 ${formatFreshness(result?.asOf ?? section.data?.completedAt)}`,
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
        return h("li", { key: order.id },
          h("div", null,
            h("strong", null, `${sideLabel} ${order.symbol ?? UNKNOWN_TEXT}`),
            h("span", null,
              `${order.type ?? UNKNOWN_TEXT} · ${formatQuantity(order.quantity)}`
              + ` · ${order.currency ?? UNKNOWN_TEXT}${priceText}`),
            order.status
              ? h("span", { className: "status-badge" },
                order.status === "PROPOSED" ? "승인 대기" : `상태: ${order.status}`)
              : null),
          h("div", { className: "actions" },
            h("button", {
              type: "button",
              disabled: busy || !sideKnown,
              onClick: () => onOrderAction(order.id, "approve")
            }, "승인"),
            h("button", {
              type: "button",
              className: "secondary",
              disabled: busy || !sideKnown,
              onClick: () => onOrderAction(order.id, "cancel")
            }, "취소")));
      }))
      : h("p", { className: "empty" }, "승인 대기 중인 주문이 없습니다."));
}

export function DashboardView({ dashboard, busyOrderId, onOrderAction, includeOrders = true }) {
  return h("main", { className: "grid dashboard-surface" },
    h(Portfolio, { section: dashboard.portfolio }),
    h(Analysis, { section: dashboard.analysis }),
    h(Events, { section: dashboard.pendingEvents }),
    includeOrders ? h(Proposals, {
      section: dashboard.pendingOrderProposals,
      busyOrderId,
      onOrderAction
    }) : null);
}
