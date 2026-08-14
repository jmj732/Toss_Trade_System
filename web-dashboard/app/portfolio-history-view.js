"use client";

import { createElement as h } from "react";
import { describeError } from "../lib/error-messages.js";
import { formatAmount, formatInstant, UNKNOWN_TEXT } from "../lib/format.js";

// V-37: DashboardView 와 동일한 한국어 어휘로 통일한다(지연/확인 필요/불러오기 실패/최신).
// V-36: 상태별 색은 공통 .badge-pill modifier 로만 표현한다.
const QUALITY_BADGES = {
  stale: ["warn", "지연"],
  unknown: ["warn", "확인 필요"],
  unavailable: ["danger", "불러오기 실패"],
  available: ["neutral", "최신"]
};

const FIELD_LABELS = { "account.cashBalance": "현금 잔고" };

function unknownFieldSummary(fields) {
  const labels = fields.filter(field => FIELD_LABELS[field]).map(field => FIELD_LABELS[field]);
  const otherCount = fields.length - labels.length;
  return [...labels, ...(otherCount ? [`기타 항목 ${otherCount}건`] : [])].join(", ");
}

function Quality({ history }) {
  const keys = [];
  if (history.stale) keys.push("stale");
  if (history.unknown) keys.push("unknown");
  if (history.unavailable) keys.push("unavailable");
  if (keys.length === 0) keys.push("available");
  return h("div", { className: "quality" },
    ...keys.map(key => {
      const [modifier, label] = QUALITY_BADGES[key];
      return h("span", { className: `badge-pill badge-pill--${modifier}`, key }, label);
    }),
    history.unknownFields?.length
      ? h("small", null, unknownFieldSummary(history.unknownFields))
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

export function Trend({ title, points, field }) {
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
      : h("p", { className: "empty" }, "추세선을 그리기에 데이터가 부족합니다"),
    h("div", { className: "trend-legend" },
      h("span", { className: "trend-krw-dot" }, "KRW"),
      h("span", { className: "trend-usd-dot" }, "USD")));
}

export function PointsTable({ points }) {
  if (points.length === 0) {
    return h("p", { className: "empty" }, "데이터가 없습니다");
  }
  const pointRow = point => h("tr", { key: point.syncRunId },
    h("td", null, point.completedAt ? formatInstant(point.completedAt) : UNKNOWN_TEXT),
    h("td", null, point.marketValueAmounts?.KRW == null
      ? "—" : formatAmount("KRW", point.marketValueAmounts.KRW)),
    h("td", null, point.marketValueAmounts?.USD == null
      ? "—" : formatAmount("USD", point.marketValueAmounts.USD)),
    h("td", null, point.profitLossAmounts?.KRW == null
      ? "—" : formatAmount("KRW", point.profitLossAmounts.KRW)),
    h("td", null, point.profitLossAmounts?.USD == null
      ? "—" : formatAmount("USD", point.profitLossAmounts.USD)));
  return h("div", {
    className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "포트폴리오 평가금액 추이 표"
  }, h("table", null,
    h("thead", null, h("tr", null,
      ...["시각", "평가금액 KRW", "평가금액 USD", "P/L KRW", "P/L USD"].map(label =>
        h("th", { key: label, scope: "col" }, label)))),
    h("tbody", null, ...points.map(pointRow))));
}

export function PortfolioHistoryTrend({ history, busy = false }) {
  const points = history?.data?.points ?? [];
  return h("section", {
    className: "portfolio-history panel portfolio-history-compact",
    "aria-busy": busy
  },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "포트폴리오 이력"),
        h("h2", null, "자산 · P/L 추세")),
      history ? h(Quality, { history }) : null),
    !history || history.unavailable
      ? h("p", { className: busy ? "busy" : "empty" },
        busy ? "이력을 불러오는 중…" : describeError(history?.unavailableReason) ?? "이력이 아직 없습니다")
      : h("div", null,
        busy ? h("p", { className: "busy" }, "이력을 새로고침 중…") : null,
        history.data.partial
          ? h("p", { className: "busy" },
            `${history.data.totalMatched}개 중 ${history.data.returnedPoints}개 표시`)
          : null,
        h(Trend, { title: "평가금액", points, field: "marketValueAmounts" }),
        h(Trend, { title: "손익", points, field: "profitLossAmounts" }),
        h(PointsTable, { points })));
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
        h("p", { className: "eyebrow" }, "포트폴리오 이력"),
        h("h2", null, "자산 · P/L 추세")),
      history ? h(Quality, { history }) : null),
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
    !history || history.unavailable
      ? h("p", { className: busy ? "busy" : "empty" },
        busy ? "이력을 불러오는 중…" : describeError(history?.unavailableReason) ?? "이력이 아직 없습니다")
      : h("div", null,
        busy ? h("p", { className: "busy" }, "이력을 새로고침 중…") : null,
        history.data.partial
          ? h("p", { className: "busy" },
            `${history.data.totalMatched}개 중 ${history.data.returnedPoints}개 표시`)
          : null,
        h(Trend, { title: "평가금액", points, field: "marketValueAmounts" }),
        h(Trend, { title: "손익", points, field: "profitLossAmounts" }),
        h(PointsTable, { points })));
}
