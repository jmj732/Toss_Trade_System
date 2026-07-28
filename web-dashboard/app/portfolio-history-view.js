"use client";

import { createElement as h } from "react";

function Quality({ history }) {
  const values = [];
  if (history.stale) values.push("STALE");
  if (history.unknown) values.push("UNKNOWN");
  if (history.unavailable) values.push("UNAVAILABLE");
  if (values.length === 0) values.push("AVAILABLE");
  return h("div", { className: "quality" },
    ...values.map(value => h("span", { className: value.toLowerCase(), key: value }, value)),
    history.unknownFields?.length
      ? h("small", null, history.unknownFields.join(", "))
      : null);
}

// Positions each currency's points by their index in the full (not per-currency-filtered)
// points array, so a currency that's only present on later points (e.g. USD added after a
// broker account starts reporting it) still lines up on the shared time axis instead of
// being stretched to fill the whole chart width on its own.
function currencyPath(points, field, currency, width, height) {
  const present = points
    .map((point, index) => ({ index, value: point[field]?.[currency] }))
    .filter(entry => entry.value != null);
  if (present.length < 2) {
    return "";
  }
  const values = present.map(entry => Number(entry.value));
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const lastIndex = points.length - 1;
  return present.map((entry, i) => {
    const x = lastIndex === 0 ? width / 2 : (entry.index / lastIndex) * width;
    const y = height - ((Number(entry.value) - min) / span) * height;
    return `${i === 0 ? "M" : "L"}${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(" ");
}

function Trend({ title, points, field }) {
  const width = 320;
  const height = 80;
  const krwPath = currencyPath(points, field, "KRW", width, height);
  const usdPath = currencyPath(points, field, "USD", width, height);
  return h("div", { className: "trend" },
    h("h3", null, title),
    krwPath || usdPath
      ? h("svg", {
        className: "sparkline",
        viewBox: `0 0 ${width} ${height}`,
        preserveAspectRatio: "none"
      },
      krwPath ? h("path", { className: "trend-krw", d: krwPath }) : null,
      usdPath ? h("path", { className: "trend-usd", d: usdPath }) : null)
      : h("p", { className: "empty" }, "Not enough points for a trend line"),
    h("div", { className: "trend-legend" },
      h("span", { className: "trend-krw-dot" }, "KRW"),
      h("span", { className: "trend-usd-dot" }, "USD")));
}

function PointsTable({ points }) {
  if (points.length === 0) {
    return h("p", { className: "empty" }, "No points");
  }
  return h("div", { className: "table-wrap" }, h("table", null,
    h("thead", null, h("tr", null,
      ...["Time", "Market value KRW", "Market value USD", "P/L KRW", "P/L USD"].map(label =>
        h("th", { key: label, scope: "col" }, label)))),
    h("tbody", null, ...points.map(point => h("tr", { key: point.syncRunId },
      h("td", null, point.completedAt),
      h("td", null, point.marketValueAmounts?.KRW ?? "—"),
      h("td", null, point.marketValueAmounts?.USD ?? "—"),
      h("td", null, point.profitLossAmounts?.KRW ?? "—"),
      h("td", null, point.profitLossAmounts?.USD ?? "—"))))));
}

export function PortfolioHistoryView({ history, query, busy, onQuery }) {
  function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const from = data.get("from");
    const to = data.get("to");
    onQuery({
      // Date inputs give a bare YYYY-MM-DD; anchor "from" at the start of that UTC day and
      // "to" at its end so the whole selected day is included, not just its first instant.
      from: from ? `${from}T00:00:00.000Z` : "",
      to: to ? `${to}T23:59:59.999Z` : "",
      maxPoints: Number(data.get("maxPoints")) || 90
    });
  }

  const points = history?.data?.points ?? [];
  const fromDate = query.from ? query.from.slice(0, 10) : "";
  const toDate = query.to ? query.to.slice(0, 10) : "";

  return h("section", { className: "portfolio-history panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "PORTFOLIO HISTORY"),
        h("h2", null, "Asset & P/L trend")),
      history ? h(Quality, { history }) : null),
    h("form", { className: "history-filter", onSubmit: submit },
      h("label", null, "From",
        h("input", { type: "date", name: "from", defaultValue: fromDate })),
      h("label", null, "To",
        h("input", { type: "date", name: "to", defaultValue: toDate })),
      h("label", null, "Max points",
        h("input", {
          type: "number", name: "maxPoints", min: 2, max: 500, defaultValue: query.maxPoints
        })),
      h("button", { type: "submit", disabled: busy }, "Apply")),
    !history || history.unavailable
      ? h("p", { className: "empty" }, history?.unavailableReason ?? "No history yet")
      : h("div", null,
        history.data.partial
          ? h("p", { className: "busy" },
            `Showing ${history.data.returnedPoints} of ${history.data.totalMatched} points`)
          : null,
        h(Trend, { title: "Market value", points, field: "marketValueAmounts" }),
        h(Trend, { title: "Profit / loss", points, field: "profitLossAmounts" }),
        h(PointsTable, { points })));
}
