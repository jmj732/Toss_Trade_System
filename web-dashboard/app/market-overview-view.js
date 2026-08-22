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
  const observedAt = item.observedAt ? ` · 수집 ${formatInstant(item.observedAt)}` : "";
  return `출처 ${item.provider}${asOf}${observedAt}`;
}

const FAILURE_LABELS = {
  PROVIDER_TIMEOUT: "제공자 시간 초과",
  PROVIDER_RATE_LIMITED: "제공자 호출 제한",
  PROVIDER_MALFORMED: "제공자 응답 형식 오류",
  PROVIDER_STALE: "제공자 데이터 만료"
};

function unavailable(value) {
  if (!value) return "loading";
  if (value.status === "UNAVAILABLE" || value.unavailable) {
    const reason = value.unavailableReason ?? "PROVIDER_UNSUPPORTED";
    return reason.startsWith("PROVIDER_UNSUPPORTED")
      ? `지원되지 않음 (${reason})`
      : `조회 실패 (${FAILURE_LABELS[reason] ?? reason})`;
  }
  if (value.status === "DEGRADED") {
    const fields = value.unknownFields ?? [];
    return fields.length
      ? `부분 데이터만 확인할 수 있습니다 · 누락 필드: ${fields.join(", ")}`
      : "부분 데이터만 확인할 수 있습니다";
  }
  if (value.status === "ERROR") return `조회 실패 (${value.unavailableReason ?? "ERROR"})`;
  return null;
}

// 데이터 품질 배지 — portfolio-history-view.js Quality()·dashboard-view.js Quality()가 같은
// 서버 필드(status/stale/unavailable)에 이미 쓰는 한국어 어휘를 그대로 재사용한다. 정상 상태는
// 배지를 붙이지 않아 카드가 가볍게 유지되고, DEGRADED/UNAVAILABLE/stale 일 때만 나타난다.
function qualityBadges(value) {
  if (!value) return [];
  const badges = [];
  if (value.status === "UNAVAILABLE" || value.unavailable || value.status === "ERROR") {
    badges.push(["danger", "불러오기 실패"]);
  } else if (value.status === "DEGRADED") {
    badges.push(["warn", "일부 누락"]);
  }
  if (value.stale) badges.push(["warn", "지연"]);
  return badges;
}

function attentionTone(badges) {
  if (badges.some(([tone]) => tone === "danger")) return "danger";
  return badges.length ? "warn" : null;
}

function widgetClassName(base, tone) {
  return tone ? `${base} market-widget--${tone}` : base;
}

function QualityBadges({ value, extra = [] }) {
  const badges = [...extra, ...qualityBadges(value)];
  if (!badges.length) return null;
  return h("div", { className: "quality" },
    ...badges.map(([tone, label]) => h("span", { className: `badge-pill badge-pill--${tone}`, key: label }, label)));
}

// ---------------------------------------------------------------------------
// 환율 위젯 — loadExchangeRate()
// ---------------------------------------------------------------------------
function ExchangeRateWidget({ exchangeRate }) {
  const message = unavailable(exchangeRate);
  const data = payload(exchangeRate);
  const sectionClass = widgetClassName("panel market-widget", attentionTone(qualityBadges(exchangeRate)));
  if (message && (message === "loading" || exchangeRate?.status !== "DEGRADED" || !data || data === exchangeRate)) {
    return h("section", { className: sectionClass },
      h("header", null,
        h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 환율"), h("h2", null, "USD/KRW 환율")),
        h(QualityBadges, { value: exchangeRate })),
      h("p", { className: "empty" }, message === "loading" ? "환율 정보를 불러오는 중…" : message));
  }
  const rate = data.rate ?? data.basePrice;
  const basisPoint = data.basisPoint;
  const direction = data.rateChangeType;
  const positive = direction === "UP" || Number(basisPoint ?? 0) >= 0;
  return h("section", { className: sectionClass },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 환율"), h("h2", null, "USD/KRW 환율")),
      h(QualityBadges, { value: exchangeRate })),
    h("div", { className: "exchange-rate-hero" },
      h("strong", { className: "metric-value metric-value-large" },
        rate != null ? `₩${Number(rate).toLocaleString("ko-KR", { minimumFractionDigits: 2 })}` : UNKNOWN_TEXT),
      basisPoint != null
        ? h("span", { className: positive ? "change-positive" : "change-negative" },
          `${positive ? "▲" : "▼"} ${Math.abs(Number(basisPoint)).toFixed(2)}bp`)
      : null),
    data.validFrom
      ? h("small", { className: "metric-freshness" }, `기준 ${formatInstant(data.validFrom)}`)
      : null,
    message ? h("small", { className: "metric-freshness" }, message) : null,
    provenance(exchangeRate) ? h("small", { className: "metric-freshness" }, provenance(exchangeRate)) : null);
}

// ---------------------------------------------------------------------------
// 시장 캘린더 위젯 — loadMarketCalendar()
// ---------------------------------------------------------------------------
function MarketCalendarWidget({ calendar }) {
  const message = unavailable(calendar);
  const data = payload(calendar);
  const sectionClass = widgetClassName("panel market-widget", attentionTone(qualityBadges(calendar)));
  if (message && (message === "loading" || calendar?.status !== "DEGRADED" || !data || data === calendar)) {
    return h("section", { className: sectionClass },
      h("header", null,
        h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 캘린더"), h("h2", null, "시장 일정")),
        h(QualityBadges, { value: calendar })),
      h("p", { className: "empty" }, message === "loading" ? "시장 일정을 불러오는 중…" : message));
  }
  const view = data;
  const raw = view?.payload ?? view;
  const today = raw?.today ?? {};
  const sessions = ["dayMarket", "preMarket", "regularMarket", "afterMarket"]
    .map(key => today?.[key] ?? today?.integrated?.[key]).filter(Boolean);
  const status = raw?.marketStatus ?? raw?.status ?? (sessions.length ? "OPEN" : "CLOSED");
  const holidays = raw?.holidays ?? raw?.closedDays ?? [];
  const statusColor = status === "OPEN" ? "ok" : status === "CLOSED" ? "danger" : "warn";
  const statusLabel = status === "OPEN" ? "장 운영 중" : status === "CLOSED" ? "휴장" : status ?? "확인 중";
  return h("section", { className: sectionClass },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 캘린더"), h("h2", null, "시장 일정")),
      h(QualityBadges, { value: calendar, extra: [[statusColor, statusLabel]] })),
    holidays.length
      ? h("ul", { className: "list" }, ...holidays.slice(0, 5).map((day, i) =>
        h("li", { key: `${day.date}-${i}` },
          h("strong", null, day.date ?? UNKNOWN_TEXT),
          h("span", null, day.name ?? day.reason ?? "휴장일"))))
      : h("p", { className: "empty" }, "등록된 휴장일이 없습니다"),
    message ? h("small", { className: "metric-freshness" }, message) : null,
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
  return h("section", { className: widgetClassName("panel market-widget rankings-widget", attentionTone(qualityBadges(rankings))) },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "Toss OpenAPI 랭킹"), h("h2", null, "종목 랭킹")),
      h(QualityBadges, { value: rankings })),
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
