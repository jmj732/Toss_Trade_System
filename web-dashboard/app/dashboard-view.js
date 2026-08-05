import { createElement as h } from "react";

function Quality({ section }) {
  const values = [];
  if (section.stale) values.push(["stale", "지연"]);
  if (section.unknown) values.push(["unknown", "확인 필요"]);
  if (section.unavailable) values.push(["unavailable", "불러오기 실패"]);
  if (values.length === 0) values.push(["available", "최신"]);
  return h("div", { className: "quality" },
    ...values.map(([className, label]) => h("span", { className, key: className }, label)),
    section.unknownFields?.length
      ? h("small", null, section.unknownFields.join(", "))
      : null);
}

function Section({ title, section, className = "", children }) {
  return h("section", { className: `panel signal-panel ${className}`.trim() },
    h("header", null, h("h2", null, title), h(Quality, { section })),
    section.unavailable
      ? h("p", { className: "empty" }, section.unavailableReason ?? "불러오기 실패")
      : children);
}

function Amounts({ values = {} }) {
  const entries = Object.entries(values);
  return entries.length
    ? h("span", null, entries.map(([currency, amount]) =>
      h("span", { className: "amount", key: currency }, `${currency} ${amount}`)))
    : h("span", { className: "unknown-text" }, "확인 필요");
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
          h(Amounts, { values: account?.marketValueAmounts }))),
      h("div", { className: "portfolio-hero-secondary" },
        h("span", null, "총 손익"),
        h("strong", null, h(Amounts, { values: account?.profitLossAmounts })))),
    h("div", { className: "summary" },
      h("div", null, h("span", null, "계좌"), h("strong", null,
        account?.displayAccountNumber ?? "확인 필요")),
      h("div", null, h("span", null, "현금"),
        h("strong", null, account?.cashBalanceStatus ?? "확인 필요"))),
    h("h3", null, "주문 가능 금액"),
    h("div", { className: "buying-power" },
      ...["KRW", "USD"].map(currency => h("div", { key: currency },
        h("span", null, currency),
        h("strong", null, portfolio?.buyingPower?.[currency]?.cashBuyingPower
          ?? "확인 필요")))),
    h("h3", null, "보유 종목"),
    positions.length
      ? h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...["종목", "종목명", "수량", "평가금액", "P/L"].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...positions.map(position => h("tr", { key: position.symbol },
          h("td", null, position.symbol),
          h("td", null, position.name),
          h("td", null, position.quantity),
          h("td", null, `${position.currency} ${position.marketValueAmount}`),
          h("td", null, `${position.currency} ${position.profitLossAmount}`))))))
      : h("p", { className: "empty" }, "보유 종목이 없습니다."));
}

function Analysis({ section }) {
  const result = section.data?.result;
  const totals = result?.currencyTotals ?? [];
  const positions = result?.positions ?? [];
  return h(Section, { title: "분석", section, className: "analysis-panel" },
    h("h3", null, "통화별 평가금액"),
    totals.length
      ? h("div", { className: "summary" }, ...totals.map(total =>
        h("div", { key: total.currency },
          h("span", null, total.currency),
          h("strong", null, `${total.marketValue} · P/L ${total.profitLoss}`))))
      : h("p", { className: "empty" }, "통화별 데이터가 없습니다."),
    h("h3", null, "종목 비중"),
    positions.length
      ? h("ul", { className: "list" }, ...positions.map(position =>
        h("li", { key: `${position.currency}-${position.symbol}` },
          h("strong", null, position.symbol),
          h("span", null, `${position.currency} · ${(Number(position.weight) * 100).toFixed(1)}%`))))
      : h("p", { className: "empty" }, "종목 분석이 없습니다."));
}

function Events({ section }) {
  const events = section.data ?? [];
  return h(Section, { title: "이벤트", section, className: "event-panel" },
    events.length
      ? h("ul", { className: "list" }, ...events.map(event =>
        h("li", { key: event.id },
          h("strong", null, event.summary),
          h("span", null, `${event.type} · ${event.affectedSymbols.join(", ")}`))))
      : h("p", { className: "empty" }, "검토할 이벤트가 없습니다."));
}

function Proposals({ section, busyOrderId, onOrderAction }) {
  const orders = section.data ?? [];
  return h(Section, { title: "주문 검토", section, className: "decision-queue" },
    orders.length
      ? h("ul", { className: "list proposals" }, ...orders.map(order =>
        h("li", { key: order.id },
          h("div", null,
            h("strong", null, `${order.side === "BUY" ? "매수" : "매도"} ${order.symbol}`),
            h("span", null,
              `${order.type} · ${order.quantity} · ${order.currency}`
              + (order.limitPrice == null ? "" : ` @ ${order.limitPrice}`))),
          h("div", { className: "actions" },
            h("button", {
              type: "button",
              disabled: busyOrderId === order.id,
              onClick: () => onOrderAction(order.id, "approve")
            }, "승인"),
            h("button", {
              type: "button",
              className: "secondary",
              disabled: busyOrderId === order.id,
              onClick: () => onOrderAction(order.id, "cancel")
            }, "취소")))))
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
