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
        h("p", { className: "eyebrow" }, "PRODUCTION CONTROL"),
        h("h2", null, "Operational readiness")),
      h("button", { type: "button", className: "secondary", disabled: busy, onClick: onRefresh },
        "Refresh")),
    h("p", { className: "disclaimer" },
      "실제 provider 점검은 주문을 생성하지 않습니다. live canary는 기본 비활성이고 실패 시 차단됩니다."),
    h("div", { className: "metrics-grid" },
      metric("Overall", readiness?.status),
      metric("Live canary", readiness?.canary?.status),
      metric("Kill switch", readiness?.killSwitch?.status),
      metric("Data freshness", readiness?.dataFreshness?.status),
      metric("Maximum lag", readiness ? lag(readiness.dataFreshness?.maxLagMs) : "—")),
    h("form", { className: "readiness-probe-form", onSubmit: probe },
      h("label", null, "Provider symbol",
        h("input", { name: "symbol", defaultValue: "AAPL", maxLength: 16, required: true })),
      h("button", { type: "submit", disabled: busy }, "Run provider check")),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    readiness?.alerts?.length
      ? h("ul", { className: "readiness-alerts" }, ...readiness.alerts.map(alert =>
        h("li", { key: alert }, alert))) : null,
    h("div", { className: "table-wrap" }, h("table", null,
      h("thead", null, h("tr", null,
        ...["Provider", "Status", "Credential", "Lag", "Missing data"].map(label =>
          h("th", { key: label, scope: "col" }, label)))),
      h("tbody", null, ...(readiness?.providers ?? []).map(provider =>
        h("tr", { key: provider.provider },
          h("td", null, provider.provider),
          h("td", null, provider.status),
          h("td", null, provider.credentialConfigured ? "configured" : "missing"),
          h("td", null, provider.lagMs == null ? "—" : lag(provider.lagMs)),
          h("td", null, provider.missingData?.join(", ") || "—")))))));
}

function metric(label, value) {
  return h("div", { className: "metric", key: label },
    h("span", { className: "metric-label" }, label),
    h("span", { className: "metric-value" }, value ?? "—"));
}
