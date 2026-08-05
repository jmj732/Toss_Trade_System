"use client";

import { createElement as h } from "react";

function lag(value) {
  const seconds = Math.max(0, Math.floor(Number(value || 0) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${seconds % 60}s`;
}

export function OperationsReadinessView({
  readiness,
  busy = false,
  error = "",
  onRefresh,
  onProbe
}) {
  function probe(event) {
    event.preventDefault();
    const symbol = new FormData(event.currentTarget).get("symbol");
    Promise.resolve(onProbe(symbol)).catch(() => {});
  }

  return h("section", { className: "operations-readiness panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "운영 관제"),
        h("h2", null, "운영 준비 상태")),
      h("button", { type: "button", className: "secondary", disabled: busy, onClick: onRefresh },
        "새로고침")),
    h("p", { className: "disclaimer" },
      "실제 provider 점검은 주문을 생성하지 않습니다. live canary는 기본 비활성이고 실패 시 차단됩니다."),
    h("div", { className: "metrics-grid" },
      metric("전체", readiness?.status),
      metric("라이브 카나리", readiness?.canary?.status),
      metric("킬 스위치", readiness?.killSwitch?.status),
      metric("데이터 신선도", readiness?.dataFreshness?.status),
      metric("최대 지연", readiness ? lag(readiness.dataFreshness?.maxLagMs) : "—")),
    h("form", { className: "readiness-probe-form", onSubmit: probe },
      h("label", null, "제공자 종목",
        h("input", { name: "symbol", defaultValue: "AAPL", maxLength: 16, required: true })),
      h("button", { type: "submit", disabled: busy }, "제공자 점검 실행")),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    readiness?.alerts?.length
      ? h("ul", { className: "readiness-alerts" }, ...readiness.alerts.map(alert =>
        h("li", { key: alert }, alert))) : null,
    h("div", { className: "table-wrap" }, h("table", null,
      h("thead", null, h("tr", null,
        ...["제공자", "상태", "자격 증명", "지연", "누락 데이터"].map(label =>
          h("th", { key: label, scope: "col" }, label)))),
      h("tbody", null, ...(readiness?.providers ?? []).map(provider =>
        h("tr", { key: provider.provider },
          h("td", null, provider.provider),
          h("td", null, provider.status),
          h("td", null, provider.credentialConfigured ? "설정됨" : "미설정"),
          h("td", null, provider.lagMs == null ? "—" : lag(provider.lagMs)),
          h("td", null, provider.missingData?.join(", ") || "—")))))));
}

function metric(label, value) {
  return h("div", { className: "metric", key: label },
    h("span", { className: "metric-label" }, label),
    h("span", { className: "metric-value" }, value ?? "—"));
}
