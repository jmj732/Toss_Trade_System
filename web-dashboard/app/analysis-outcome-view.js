"use client";

import { createElement as h } from "react";

function percent(value) {
  return value == null ? "—" : `${(Number(value) * 100).toFixed(1)}%`;
}

function th(label) {
  return h("th", { key: label, scope: "col" }, label);
}

function PerformanceTable({ rows }) {
  if (rows.length === 0) {
    return h("p", { className: "empty" }, "No graded outcomes yet");
  }
  return h("div", { className: "table-wrap" }, h("table", null,
    h("thead", null, h("tr", null,
      ...["Model", "Contract", "Horizon", "Samples", "Hit rate", "Avg directional return", "Avg max adverse excursion"]
        .map(th))),
    h("tbody", null, ...rows.map(row => h("tr", { key: `${row.modelVersion}-${row.contractVersion}-${row.horizon}` },
      h("td", null, row.modelVersion),
      h("td", null, row.contractVersion),
      h("td", null, row.horizon),
      h("td", null, row.sampleCount),
      h("td", null, percent(row.hitRate)),
      h("td", null, percent(row.avgDirectionalReturn)),
      h("td", null, percent(row.avgMaxAdverseExcursion)))))));
}

function outcomeCell(outcome) {
  if (!outcome) {
    return h("td", { className: "empty" }, "—");
  }
  return h("td", null, `${percent(outcome.actualReturn)} ${outcome.directionCorrect ? "HIT" : "MISS"}`);
}

function PredictionsTable({ predictions }) {
  if (predictions.length === 0) {
    return h("p", { className: "empty" }, "No predictions recorded yet");
  }
  return h("div", { className: "table-wrap" }, h("table", null,
    h("thead", null, h("tr", null,
      ...["Predicted at", "Symbol", "Direction", "Baseline", "Model", "Contract", "D1", "D5", "D20"].map(th))),
    h("tbody", null, ...predictions.map(prediction => h("tr", { key: prediction.id },
      h("td", null, prediction.predictedAt),
      h("td", null, prediction.symbol),
      h("td", null, prediction.predictedDirection),
      h("td", null, `${prediction.baselinePrice} ${prediction.currency}`),
      h("td", null, prediction.modelVersion),
      h("td", null, prediction.contractVersion),
      outcomeCell(prediction.outcomes?.D1),
      outcomeCell(prediction.outcomes?.D5),
      outcomeCell(prediction.outcomes?.D20))))));
}

function RegistryPanel({ versions, busy, error, onRegister, onDeprecate, onDelete }) {
  function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    Promise.resolve(onRegister({
      modelVersion: data.get("modelVersion"),
      contractVersion: data.get("contractVersion")
    })).then(() => form.reset()).catch(() => {});
  }

  return h("section", { className: "prediction-model-registry", "aria-busy": busy },
    h("h3", null, "Prediction model registry"),
    h("form", { className: "prediction-registry-form", onSubmit: submit },
      h("label", null, "Model version",
        h("input", { name: "modelVersion", maxLength: 50, required: true })),
      h("label", null, "Contract version",
        h("input", { name: "contractVersion", maxLength: 50, required: true })),
      h("button", { type: "submit", disabled: busy }, "Register version")),
    error ? h("p", { className: "error" }, error) : null,
    versions.length === 0
      ? h("p", { className: "empty" }, "No model versions registered")
      : h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null, ...["Model", "Contract", "Status", "Actions"].map(th))),
        h("tbody", null, ...versions.map(version => h("tr", { key: version.id },
          h("td", null, version.modelVersion),
          h("td", null, version.contractVersion),
          h("td", null, version.status),
          h("td", null,
            h("button", {
              type: "button",
              disabled: busy || version.status !== "ACTIVE",
              onClick: () => Promise.resolve(onDeprecate(version.id)).catch(() => {})
            }, "Deprecate"),
            h("button", {
              type: "button",
              disabled: busy,
              onClick: () => Promise.resolve(onDelete(version.id)).catch(() => {})
            }, "Delete"))))))));
}

function CreateForm({ versions, busy, onCreate }) {
  const active = versions.filter(version => version.status === "ACTIVE");

  function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const version = active.find(candidate => candidate.id === data.get("versionId"));
    if (!version) {
      return;
    }
    Promise.resolve(onCreate({
      symbol: data.get("symbol"),
      currency: data.get("currency"),
      predictedDirection: data.get("predictedDirection"),
      modelVersion: version.modelVersion,
      contractVersion: version.contractVersion
    })).then(() => form.reset())
      // Failure is already surfaced via createError — this just avoids an unhandled
      // rejection warning for the promise chain local to the form itself.
      .catch(() => {});
  }

  return h("form", { className: "prediction-create-form", onSubmit: submit },
    h("label", null, "Symbol", h("input", { name: "symbol", required: true })),
    h("label", null, "Currency",
      h("select", { name: "currency" }, h("option", { value: "USD" }, "USD"), h("option", { value: "KRW" }, "KRW"))),
    h("label", null, "Direction",
      h("select", { name: "predictedDirection" },
        h("option", { value: "UP" }, "UP"), h("option", { value: "DOWN" }, "DOWN"))),
    h("label", null, "Model / contract version",
      h("select", { name: "versionId", required: true },
        ...active.map(version => h("option", { key: version.id, value: version.id },
          `${version.modelVersion} / ${version.contractVersion}`)))),
    active.length === 0
      ? h("p", { className: "empty" }, "Register an ACTIVE model version first")
      : null,
    h("button", { type: "submit", disabled: busy || active.length === 0 }, "Record prediction"));
}

export function AnalysisOutcomeView({
  performance,
  versions = [],
  query = {},
  busy,
  onQuery,
  createBusy,
  createError,
  onCreate,
  registryBusy,
  registryError,
  onRegister,
  onDeprecate,
  onDelete
}) {
  function submitFilter(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const from = data.get("from");
    const to = data.get("to");
    onQuery({
      from: from ? `${from}T00:00:00.000Z` : "",
      to: to ? `${to}T23:59:59.999Z` : "",
      modelVersion: data.get("modelVersion") || "",
      contractVersion: data.get("contractVersion") || ""
    });
  }

  const fromDate = query.from ? query.from.slice(0, 10) : "";
  const toDate = query.to ? query.to.slice(0, 10) : "";
  const predictions = performance?.predictions ?? [];
  const byVersion = performance?.byVersion ?? [];

  return h("section", { className: "analysis-outcome panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "ANALYSIS OUTCOME TRACKING"),
        h("h2", null, "Prediction performance"))),
    h("p", { className: "disclaimer" },
      "예측 기록 및 채점 전용 기능입니다 — 주문이나 자동매매와 연동되지 않습니다."),
    h(RegistryPanel, {
      versions,
      busy: registryBusy,
      error: registryError,
      onRegister,
      onDeprecate,
      onDelete
    }),
    h(CreateForm, { versions, busy: createBusy, onCreate }),
    createError ? h("p", { className: "error" }, createError) : null,
    h("form", { className: "history-filter", onSubmit: submitFilter },
      h("label", null, "From",
        h("input", { type: "date", name: "from", defaultValue: fromDate })),
      h("label", null, "To",
        h("input", { type: "date", name: "to", defaultValue: toDate })),
      h("label", null, "Model version", h("input", { name: "modelVersion", defaultValue: query.modelVersion })),
      h("label", null, "Contract version",
        h("input", { name: "contractVersion", defaultValue: query.contractVersion })),
      h("button", { type: "submit", disabled: busy }, "Apply")),
    h(PerformanceTable, { rows: byVersion }),
    h(PredictionsTable, { predictions }));
}
