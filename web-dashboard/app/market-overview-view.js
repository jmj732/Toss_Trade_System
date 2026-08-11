"use client";

import { createElement as h, useState } from "react";

import { formatAmount, formatInstant, UNKNOWN_TEXT } from "../lib/format.js";

function payload(value) {
  return value?.data ?? value;
}

function provenance(value) {
  const item = value?.provenance?.[0];
  if (!item) return null;
  const asOf = item.asOf ? ` · 기준 ${formatInstant(item.asOf)}` : "";
  return `출처 ${item.provider}${asOf}`;
}

function unavailable(value) {
  if (!value) return "loading";
  if (value.status === "UNAVAILABLE" || value.unavailable) {
    return `지원되지 않음 (${value.unavailableReason ?? "PROVIDER_UNSUPPORTED"})`;
  }
  if (value.status === "DEGRADED") return "부분 데이터만 확인할 수 있습니다";
  if (value.status === "ERROR") return `조회 실패 (${value.unavailableReason ?? "ERROR"})`;
  return null;
}

// ---------------------------------------------------------------------------
// 환율 위젯 — loadExchangeRate()
// ---------------------------------------------------------------------------
function ExchangeRateWidget({ exchangeRate }) {
  const message = unavailable(exchangeRate);
  if (message) {
    return h("section", { className: "panel market-widget" },
      h("header", null,
        h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 환율"), h("h2", null, "USD/KRW 환율"))),
      h("p", { className: "empty" }, message === "loading" ? "환율 정보를 불러오는 중…" : message));
  }
  exchangeRate = payload(exchangeRate);
  const rate = exchangeRate.rate ?? exchangeRate.basePrice;
  const basisPoint = exchangeRate.basisPoint;
  const direction = exchangeRate.rateChangeType;
  const positive = direction === "UP" || Number(basisPoint ?? 0) >= 0;
  return h("section", { className: "panel market-widget" },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 환율"), h("h2", null, "USD/KRW 환율"))),
    h("div", { className: "exchange-rate-hero" },
      h("strong", { className: "metric-value metric-value-large" },
        rate != null ? `₩${Number(rate).toLocaleString("ko-KR", { minimumFractionDigits: 2 })}` : UNKNOWN_TEXT),
      basisPoint != null
        ? h("span", { className: positive ? "change-positive" : "change-negative" },
          `${positive ? "▲" : "▼"} ${Math.abs(Number(basisPoint)).toFixed(2)}bp`)
      : null),
    exchangeRate.validFrom
      ? h("small", { className: "metric-freshness" }, `기준 ${formatInstant(exchangeRate.validFrom)}`)
      : null,
    provenance(exchangeRate) ? h("small", { className: "metric-freshness" }, provenance(exchangeRate)) : null);
}

// ---------------------------------------------------------------------------
// 시장 캘린더 위젯 — loadMarketCalendar()
// ---------------------------------------------------------------------------
function MarketCalendarWidget({ calendar }) {
  const message = unavailable(calendar);
  if (message) {
    return h("section", { className: "panel market-widget" },
      h("header", null,
        h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 캘린더"), h("h2", null, "시장 일정"))),
      h("p", { className: "empty" }, message === "loading" ? "시장 일정을 불러오는 중…" : message));
  }
  const view = payload(calendar);
  const raw = view?.payload ?? view;
  const today = raw?.today ?? {};
  const sessions = ["dayMarket", "preMarket", "regularMarket", "afterMarket"]
    .map(key => today?.[key] ?? today?.integrated?.[key]).filter(Boolean);
  const status = raw?.marketStatus ?? raw?.status ?? (sessions.length ? "OPEN" : "CLOSED");
  const holidays = raw?.holidays ?? raw?.closedDays ?? [];
  const statusColor = status === "OPEN" ? "ok" : status === "CLOSED" ? "danger" : "warn";
  return h("section", { className: "panel market-widget" },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 캘린더"), h("h2", null, "시장 일정")),
      h("span", { className: `badge-pill badge-pill--${statusColor}` },
        status === "OPEN" ? "장 운영 중" : status === "CLOSED" ? "휴장" : status ?? "확인 중")),
    holidays.length
      ? h("ul", { className: "list" }, ...holidays.slice(0, 5).map((day, i) =>
        h("li", { key: `${day.date}-${i}` },
          h("strong", null, day.date ?? UNKNOWN_TEXT),
          h("span", null, day.name ?? day.reason ?? "휴장일"))))
      : h("p", { className: "empty" }, "등록된 휴장일이 없습니다"),
    provenance(calendar) ? h("small", { className: "metric-freshness" }, provenance(calendar)) : null);
}

// ---------------------------------------------------------------------------
// 랭킹 위젯 — loadRankings()
// ---------------------------------------------------------------------------
const RANKING_CATEGORIES = [
  { key: "VOLUME", label: "거래량" },
  { key: "MARKET_CAP", label: "시가총액" },
  { key: "GAINERS", label: "상승률" },
  { key: "LOSERS", label: "하락률" }
];

function RankingsWidget({ rankings, onCategoryChange }) {
  const [category, setCategory] = useState("VOLUME");
  const message = unavailable(rankings);
  const data = payload(rankings);
  const items = data?.items ?? data?.rankings ?? data?.stocks ?? [];
  return h("section", { className: "panel market-widget rankings-widget" },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 랭킹"), h("h2", null, "종목 랭킹"))),
    h("div", { className: "ranking-tabs" },
      ...RANKING_CATEGORIES.map(cat =>
        h("button", {
          key: cat.key,
          type: "button",
          className: category === cat.key ? "primary" : "secondary",
          onClick: () => {
            setCategory(cat.key);
            if (onCategoryChange) onCategoryChange(cat.key);
          }
        }, cat.label))),
    items.length
      ? h("div", { className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "종목 랭킹 표" },
        h("table", null,
          h("thead", null, h("tr", null,
            ...["순위", "종목", "현재가", "등락률"].map(label =>
              h("th", { key: label, scope: "col" }, label)))),
          h("tbody", null, ...items.slice(0, 10).map((item, i) => {
            const changePercent = item.changePercent ?? item.changeRate;
            const positive = (changePercent ?? 0) >= 0;
            return h("tr", { key: `${item.symbol}-${i}` },
              h("td", null, item.rank ?? i + 1),
              h("td", null, h("strong", null, item.symbol ?? UNKNOWN_TEXT),
                item.name ? h("small", { className: "ranking-name" }, item.name) : null),
              h("td", null, (item.price ?? item.lastPrice) != null
                ? formatAmount(item.currency ?? "USD", item.price ?? item.lastPrice) : UNKNOWN_TEXT),
              h("td", { className: positive ? "change-positive" : "change-negative" },
                changePercent != null ? `${positive ? "+" : ""}${(changePercent * 100).toFixed(2)}%` : UNKNOWN_TEXT));
          }))))
      : h("p", { className: "empty" }, message && message !== "loading" ? message : "랭킹 데이터를 불러오는 중…"),
    provenance(rankings) ? h("small", { className: "metric-freshness" }, provenance(rankings)) : null);
}

// ---------------------------------------------------------------------------
// 공개 export
// ---------------------------------------------------------------------------
export function MarketOverviewView({ exchangeRate, calendar, rankings, onRankingCategory }) {
  return h("div", { className: "market-overview-grid" },
    h(ExchangeRateWidget, { exchangeRate }),
    h(MarketCalendarWidget, { calendar }),
    h(RankingsWidget, { rankings, onCategoryChange: onRankingCategory }));
}
