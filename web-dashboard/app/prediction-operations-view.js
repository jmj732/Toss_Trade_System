"use client";

import { createElement as h } from "react";

function instant(value) {
  return value ? new Date(value).toISOString() : null;
}

function lag(value) {
  let seconds = Math.floor(Number(value || 0) / 1000);
  const hours = Math.floor(seconds / 3600);
  seconds %= 3600;
  const minutes = Math.floor(seconds / 60);
  seconds %= 60;
  return `${hours}h ${minutes}m ${seconds}s`;
}

function th(label) {
  return h("th", { key: label, scope: "col" }, label);
}

export function PredictionOperationsView({
  operations,
  keys = [],
  issuedKey,
  busy,
  error,
  onIssue,
  onRotate,
  onRevoke,
  onRefresh,
  onDismissKey
}) {
  function issue(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    Promise.resolve(onIssue({
      modelVersion: data.get("modelVersion"),
      contractVersion: data.get("contractVersion"),
      expiresAt: instant(data.get("expiresAt"))
    })).then(() => form.reset()).catch(() => {});
  }

  function rotate(event, id) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    Promise.resolve(onRotate(id, { expiresAt: instant(data.get("expiresAt")) }))
      .catch(() => {});
  }

  return h("section", { className: "prediction-operations panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "PREDICTION INGESTION"),
        h("h2", null, "Prediction operations")),
      h("button", {
        type: "button",
        className: "secondary",
        disabled: busy,
        onClick: () => Promise.resolve(onRefresh()).catch(() => {})
      },
        "Refresh")),
    h("p", { className: "disclaimer" },
      "사용자 소유 API key와 평가 backlog만 표시합니다. 자동 예측이나 주문 실행 기능은 없습니다."),
    h("div", { className: "metrics-grid" },
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "Evaluation"),
        h("span", { className: "metric-value" },
          operations?.evaluationEnabled ? "Evaluation enabled" : "Evaluation disabled")),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "Due backlog"),
        h("span", { className: "metric-value" }, operations?.backlog ?? "—")),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "Maximum lag"),
        h("span", { className: "metric-value" },
          operations ? lag(operations.maxLagMs) : "—")),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "Long ungraded"),
        h("span", { className: "metric-value" }, operations?.longUngradedCount ?? "—")),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "Oldest long-ungraded due"),
        h("span", { className: "metric-value" }, operations?.oldestLongUngradedDueAt ?? "—"))),
    issuedKey ? h("div", { className: "issued-api-key", role: "status" },
      h("strong", null, "This key is shown once"),
      h("code", null, issuedKey.apiKey),
      h("button", { type: "button", className: "secondary", onClick: onDismissKey }, "Dismiss")) : null,
    h("form", { className: "prediction-registry-form", onSubmit: issue },
      h("label", null, "Model version",
        h("input", { name: "modelVersion", maxLength: 50, required: true })),
      h("label", null, "Contract version",
        h("input", { name: "contractVersion", maxLength: 50, required: true })),
      h("label", null, "Expires at (optional)",
        h("input", { type: "datetime-local", name: "expiresAt" })),
      h("button", { type: "submit", disabled: busy }, "Issue API key")),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    keys.length === 0
      ? h("p", { className: "empty" }, "No prediction ingestion API keys")
      : h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...["Prefix", "Model", "Contract", "Status", "Expires", "Last used", "Actions"].map(th))),
        h("tbody", null, ...keys.map(key => h("tr", { key: key.id },
          h("td", null, h("code", null, key.prefix)),
          h("td", null, key.modelVersion),
          h("td", null, key.contractVersion),
          h("td", null, key.status),
          h("td", null, key.expiresAt ?? "Never"),
          h("td", null, key.lastUsedAt ?? "Never"),
          h("td", null,
            h("form", { className: "key-rotation-form", onSubmit: event => rotate(event, key.id) },
              h("input", {
                type: "datetime-local",
                name: "expiresAt",
                "aria-label": `New expiry for ${key.prefix}`,
                title: "Blank keeps the current expiry",
                disabled: busy || key.status !== "ACTIVE"
              }),
              h("button", {
                type: "submit",
                disabled: busy || key.status !== "ACTIVE"
              }, "Rotate")),
            h("button", {
              type: "button",
              disabled: busy || key.status !== "ACTIVE",
              onClick: () => Promise.resolve(onRevoke(key.id)).catch(() => {})
            }, "Revoke"))))))));
}
