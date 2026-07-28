"use client";

import { createElement as h } from "react";

function HistoryList({ history }) {
  if (history.length === 0) {
    return h("p", { className: "empty" }, "No changes yet");
  }
  return h("ul", { className: "list risk-policy-history" }, ...history.map(entry =>
    h("li", { key: entry.version },
      h("div", null,
        h("strong", null, `v${entry.version}`),
        h("span", null, `${entry.changedBy} · ${entry.changedAt}`)),
      h("span", null,
        `KRW ${entry.maxOrderAmountKrw} · USD ${entry.maxOrderAmountUsd} · `
        + `qty ${entry.maxQuantity} · concentration ${entry.maxConcentration}`))));
}

export function RiskPolicyPanel({
  policy,
  history,
  open,
  busy,
  onToggle,
  onUpdate,
  onLoadHistory
}) {
  function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    onUpdate({
      expectedVersion: policy.version,
      maxOrderAmountKrw: Number(data.get("maxOrderAmountKrw")),
      maxOrderAmountUsd: Number(data.get("maxOrderAmountUsd")),
      maxQuantity: Number(data.get("maxQuantity")),
      maxConcentration: Number(data.get("maxConcentration"))
    });
  }

  return h("div", { className: "risk-policy" },
    h("button", {
      type: "button",
      className: "secondary risk-policy-toggle",
      "aria-expanded": open,
      onClick: onToggle
    }, "Risk policy"),
    open ? h("div", { className: "risk-policy-panel panel" },
      h("header", null,
        h("h2", null, "Order risk limits"),
        policy
          ? h("span", { className: policy.customized ? "customized" : "default" },
            policy.customized ? "CUSTOM" : "DEFAULT")
          : null),
      !policy
        ? h("p", { className: "empty" }, "Loading…")
        // Keyed on version so the form remounts (and its defaultValue inputs refresh) after
        // every successful save, instead of silently keeping the previous values on screen.
        : h("form", {
          key: policy.version, className: "risk-policy-form", onSubmit: submit
        },
        h("label", null, "Max order amount (KRW)",
          h("input", {
            type: "number", name: "maxOrderAmountKrw", step: "any", min: "0",
            defaultValue: policy.maxOrderAmountKrw, required: true
          })),
        h("label", null, "Max order amount (USD)",
          h("input", {
            type: "number", name: "maxOrderAmountUsd", step: "any", min: "0",
            defaultValue: policy.maxOrderAmountUsd, required: true
          })),
        h("label", null, "Max quantity",
          h("input", {
            type: "number", name: "maxQuantity", step: "any", min: "0",
            defaultValue: policy.maxQuantity, required: true
          })),
        h("label", null, "Max symbol concentration (0–1)",
          h("input", {
            type: "number", name: "maxConcentration", step: "any", min: "0", max: "1",
            defaultValue: policy.maxConcentration, required: true
          })),
        h("button", { type: "submit", disabled: busy }, "Save")),
      h("h3", null, "Change history"),
      h("button", {
        type: "button", className: "secondary", disabled: busy, onClick: onLoadHistory
      }, "Load history"),
      h(HistoryList, { history })) : null);
}
