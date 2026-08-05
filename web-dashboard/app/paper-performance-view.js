"use client";

import { createElement as h } from "react";

function equityPath(points, width, height) {
  if (points.length < 2) {
    return "";
  }
  const values = points.map(point => Number(point.cumulativeRealizedPnl));
  const min = Math.min(...values, 0);
  const max = Math.max(...values, 0);
  const span = max - min || 1;
  const lastIndex = points.length - 1;
  return points.map((point, i) => {
    const x = lastIndex === 0 ? width / 2 : (i / lastIndex) * width;
    const y = height - ((Number(point.cumulativeRealizedPnl) - min) / span) * height;
    return `${i === 0 ? "M" : "L"}${x.toFixed(2)},${y.toFixed(2)}`;
  }).join(" ");
}

function Metric({ label, value }) {
  return h("div", { className: "metric" },
    h("span", { className: "metric-label" }, label),
    h("span", { className: "metric-value" }, value ?? "—"));
}

function percent(value) {
  return value == null ? null : `${(Number(value) * 100).toFixed(1)}%`;
}

function CurrencyCard({ currency, breakdown }) {
  const width = 320;
  const height = 80;
  const path = equityPath(breakdown.equityCurve, width, height);
  return h("div", { className: "paper-performance-currency" },
    h("h3", null, currency),
    h("div", { className: "metrics-grid" },
      h(Metric, { label: "실현 P/L", value: breakdown.realizedPnl }),
      h(Metric, { label: "미실현 P/L", value: breakdown.unrealizedPnl }),
      h(Metric, { label: "수수료", value: breakdown.totalFees }),
      h(Metric, { label: "세금", value: breakdown.totalTax }),
      h(Metric, { label: "거래대금", value: breakdown.turnover }),
      h(Metric, { label: "청산 거래", value: breakdown.closedTradeCount }),
      h(Metric, { label: "승률", value: percent(breakdown.winRate) }),
      h(Metric, { label: "최대 낙폭", value: breakdown.maxDrawdown })),
    breakdown.partial
      ? h("p", { className: "busy" },
        `청산 거래 ${breakdown.totalMatched}개 중 ${breakdown.returnedPoints}개 표시`)
      : null,
    path
      ? h("svg", {
        className: "sparkline",
        viewBox: `0 0 ${width} ${height}`,
        preserveAspectRatio: "none"
      }, h("path", { className: "trend-pnl", d: path }))
      : h("p", { className: "empty" }, "자산 곡선을 그리기에 청산 거래가 부족합니다"));
}

export function PaperPerformanceView({ performance, query, busy, onQuery }) {
  function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const from = data.get("from");
    const to = data.get("to");
    onQuery({
      from: from ? `${from}T00:00:00.000Z` : "",
      to: to ? `${to}T23:59:59.999Z` : "",
      maxPoints: Number(data.get("maxPoints")) || 90
    });
  }

  const fromDate = query.from ? query.from.slice(0, 10) : "";
  const toDate = query.to ? query.to.slice(0, 10) : "";
  const byCurrency = performance?.data?.byCurrency ?? {};
  const currencies = Object.keys(byCurrency);

  return h("section", { className: "paper-performance panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "모의 성과"),
        h("h2", null, "모의 매매 성과"))),
    h("p", { className: "disclaimer" },
      "모의(Paper) 매매 시뮬레이션 결과이며 "
      + "실제 주문 체결이나 수익을 보장하지 않습니다."),
    h("form", { className: "history-filter", onSubmit: submit },
      h("label", null, "시작일",
        h("input", { type: "date", name: "from", defaultValue: fromDate })),
      h("label", null, "종료일",
        h("input", { type: "date", name: "to", defaultValue: toDate })),
      h("label", null, "최대 포인트 수",
        h("input", {
          type: "number", name: "maxPoints", min: 2, max: 500, defaultValue: query.maxPoints
        })),
      h("button", { type: "submit", disabled: busy }, "적용")),
    !performance || performance.unavailable
      ? h("p", { className: "empty" }, performance?.unavailableReason ?? "모의 거래가 아직 없습니다")
      : currencies.length === 0
        ? h("p", { className: "empty" }, "이 기간에 모의 거래가 없습니다")
        : h("div", null, ...currencies.map(currency =>
          h(CurrencyCard, { key: currency, currency, breakdown: byCurrency[currency] }))));
}
