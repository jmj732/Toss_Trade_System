import { createElement as h } from "react";

function Quality({ section }) {
  const values = [];
  if (section.stale) values.push("STALE");
  if (section.unknown) values.push("UNKNOWN");
  if (section.unavailable) values.push("UNAVAILABLE");
  if (values.length === 0) values.push("AVAILABLE");
  return h("div", { className: "quality" },
    ...values.map(value => h("span", { className: value.toLowerCase(), key: value }, value)),
    section.unknownFields?.length
      ? h("small", null, section.unknownFields.join(", "))
      : null);
}

function Section({ title, section, children }) {
  return h("section", { className: "panel" },
    h("header", null, h("h2", null, title), h(Quality, { section })),
    section.unavailable
      ? h("p", { className: "empty" }, section.unavailableReason ?? "UNAVAILABLE")
      : children);
}

function Amounts({ values = {} }) {
  const entries = Object.entries(values);
  return entries.length
    ? h("span", null, entries.map(([currency, amount]) =>
      h("span", { className: "amount", key: currency }, `${currency} ${amount}`)))
    : h("span", { className: "unknown-text" }, "UNKNOWN");
}

function Portfolio({ section }) {
  const portfolio = section.data;
  const account = portfolio?.account;
  const positions = portfolio?.positions ?? [];
  return h(Section, { title: "Portfolio", section },
    h("div", { className: "summary" },
      h("div", null, h("span", null, "Account"), h("strong", null,
        account?.displayAccountNumber ?? "UNAVAILABLE")),
      h("div", null, h("span", null, "Market value"),
        h("strong", null, h(Amounts, { values: account?.marketValueAmounts }))),
      h("div", null, h("span", null, "Profit / loss"),
        h("strong", null, h(Amounts, { values: account?.profitLossAmounts }))),
      h("div", null, h("span", null, "Cash"),
        h("strong", null, account?.cashBalanceStatus ?? "UNAVAILABLE"))),
    h("h3", null, "Buying power"),
    h("div", { className: "buying-power" },
      ...["KRW", "USD"].map(currency => h("div", { key: currency },
        h("span", null, currency),
        h("strong", null, portfolio?.buyingPower?.[currency]?.cashBuyingPower
          ?? "UNAVAILABLE")))),
    h("h3", null, "Positions"),
    positions.length
      ? h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...["Symbol", "Name", "Quantity", "Value", "P/L"].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...positions.map(position => h("tr", { key: position.symbol },
          h("td", null, position.symbol),
          h("td", null, position.name),
          h("td", null, position.quantity),
          h("td", null, `${position.currency} ${position.marketValueAmount}`),
          h("td", null, `${position.currency} ${position.profitLossAmount}`))))))
      : h("p", { className: "empty" }, "No positions"));
}

function Analysis({ section }) {
  const result = section.data?.result;
  const totals = result?.currencyTotals ?? [];
  const positions = result?.positions ?? [];
  return h(Section, { title: "Analysis", section },
    h("h3", null, "Currency totals"),
    totals.length
      ? h("div", { className: "summary" }, ...totals.map(total =>
        h("div", { key: total.currency },
          h("span", null, total.currency),
          h("strong", null, `${total.marketValue} · P/L ${total.profitLoss}`))))
      : h("p", { className: "empty" }, "No currency totals"),
    h("h3", null, "Position weights"),
    positions.length
      ? h("ul", { className: "list" }, ...positions.map(position =>
        h("li", { key: `${position.currency}-${position.symbol}` },
          h("strong", null, position.symbol),
          h("span", null, `${position.currency} · ${(Number(position.weight) * 100).toFixed(1)}%`))))
      : h("p", { className: "empty" }, "No position analysis"));
}

function Events({ section }) {
  const events = section.data ?? [];
  return h(Section, { title: "Events", section },
    events.length
      ? h("ul", { className: "list" }, ...events.map(event =>
        h("li", { key: event.id },
          h("strong", null, event.summary),
          h("span", null, `${event.type} · ${event.affectedSymbols.join(", ")}`))))
      : h("p", { className: "empty" }, "No pending events"));
}

function Proposals({ section, busyOrderId, onOrderAction }) {
  const orders = section.data ?? [];
  return h(Section, { title: "Order proposals", section },
    orders.length
      ? h("ul", { className: "list proposals" }, ...orders.map(order =>
        h("li", { key: order.id },
          h("div", null,
            h("strong", null, `${order.side} ${order.symbol}`),
            h("span", null,
              `${order.type} · ${order.quantity} · ${order.currency}`
              + (order.limitPrice == null ? "" : ` @ ${order.limitPrice}`))),
          h("div", { className: "actions" },
            h("button", {
              type: "button",
              disabled: busyOrderId === order.id,
              onClick: () => onOrderAction(order.id, "approve")
            }, "Approve"),
            h("button", {
              type: "button",
              className: "secondary",
              disabled: busyOrderId === order.id,
              onClick: () => onOrderAction(order.id, "cancel")
            }, "Cancel")))))
      : h("p", { className: "empty" }, "No pending proposals"));
}

export function DashboardView({ dashboard, busyOrderId, onOrderAction }) {
  return h("main", { className: "grid" },
    h(Portfolio, { section: dashboard.portfolio }),
    h(Analysis, { section: dashboard.analysis }),
    h(Events, { section: dashboard.pendingEvents }),
    h(Proposals, {
      section: dashboard.pendingOrderProposals,
      busyOrderId,
      onOrderAction
    }));
}
