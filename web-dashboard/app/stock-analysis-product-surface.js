"use client";

import { createElement as h } from "react";

function State({ value }) {
  return h("span", { className: `surface-state ${String(value).toLowerCase()}` }, value);
}

function Panel({ title, state, error, action, children }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "STOCK SURFACE"), h("h2", null, title)),
      h("div", { className: "panel-actions" }, h(State, { value: state }), action)),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    children);
}

function MissingData({ values = [] }) {
  return h("div", { className: "missing-data" },
    h("h3", null, "Missing data"),
    values.length
      ? h("ul", { className: "list" }, ...values.map(value => h("li", { key: value }, value)))
      : h("p", { className: "empty" }, "None reported"));
}

function Provenance({ values = [] }) {
  return h("div", { className: "provenance" },
    h("h3", null, "Provenance"),
    values.length
      ? h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...["Provider", "Field", "As of", "Collected at"].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...values.map((item, index) => h("tr", { key: `${item.provider}-${item.field}-${index}` },
          h("td", null, item.provider), h("td", null, item.field),
          h("td", null, item.asOf ?? "—"), h("td", null, item.collectedAt ?? "—"))))))
      : h("p", { className: "empty" }, "No provenance reported"));
}

function analysisParts(analysis) {
  const result = analysis?.result;
  const analyzers = result?.analyzers ?? [];
  const metrics = analyzers.flatMap(analyzer => analyzer.metrics ?? []);
  const provenance = [
    ...(result?.observations ?? []),
    ...metrics.flatMap(metric => metric.provenance ?? [])
  ];
  return { result, metrics, provenance };
}

function AnalysisPanel({ analysis, state, error, onCreate }) {
  const { result, metrics, provenance } = analysisParts(analysis);
  return h(Panel, {
    title: "Analysis",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS", onClick: onCreate
    }, analysis ? "Re-run analysis" : "Create analysis")
  },
  result ? h("div", null,
    h("p", { className: "disclaimer" },
      `Snapshot ${analysis.inputSnapshotId ?? "—"} · as of ${result.asOf ?? "—"}`),
    h("div", { className: "analysis-metrics" },
      metrics.length
        ? metrics.map(metric => h("div", { className: "metric", key: metric.name },
          h("span", { className: "metric-label" }, metric.name),
          h("span", { className: "metric-value" }, metric.value ?? "—"),
          h("small", null, metric.unit ?? "")))
        : h("p", { className: "empty" }, "No analyzer metrics")),
    h(MissingData, { values: result.missingData }),
    h(Provenance, { values: provenance }))
    : h("p", { className: "empty" }, state === "PROGRESS" ? "Analysis in progress…" : "No analysis yet"));
}

function ForecastPanel({ forecast, state, error, onCreate }) {
  const result = forecast?.result ?? forecast;
  const metrics = result?.forecasts ?? [];
  const provenance = metrics.flatMap(metric => metric.provenance ?? []);
  return h(Panel, {
    title: "Forecast",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS", onClick: onCreate
    }, forecast ? "Re-run forecast" : "Create forecast")
  },
  result ? h("div", null,
    h("p", { className: "disclaimer" },
      `Snapshot ${forecast.inputSnapshotId ?? result.inputSnapshotId ?? "—"} · Confidence ${result.confidence ?? "—"}`),
    metrics.length
      ? h("ul", { className: "list" }, ...metrics.map(metric => h("li", { key: metric.name },
        h("strong", null, metric.name), h("span", null, metric.value ?? "—"))))
      : h("p", { className: "empty" }, "No forecast metrics"),
    h(MissingData, { values: result.missingData }),
    h(Provenance, { values: provenance }))
    : h("p", { className: "empty" }, state === "PROGRESS" ? "Forecast in progress…" : "No forecast yet"));
}

function ExplanationPanel({ explanation, state, error, onCreate }) {
  const claims = explanation?.explanation ?? {};
  const citations = explanation?.citations ?? [];
  const evidence = [...(claims.evidence ?? []), ...(claims.counterArguments ?? [])];
  return h(Panel, {
    title: "Gemini explain",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS", onClick: onCreate
    }, explanation ? "Regenerate explain" : "Generate explain")
  },
  explanation ? h("div", null,
    h("p", { className: "disclaimer" }, `Snapshot ${explanation.inputSnapshotId ?? "—"}`),
    evidence.length
      ? h("ul", { className: "list" }, ...evidence.map((claim, index) => h("li", { key: `${claim.text}-${index}` },
        h("strong", null, claim.text), h("span", null, claim.citationIds?.join(", ") ?? "No citation"))))
      : h("p", { className: "empty" }, "No explanation claims"),
    h("h3", null, "Citations"),
    citations.length
      ? h("ul", { className: "list" }, ...citations.map(citation => h("li", { key: citation.id },
        `${citation.id} · ${citation.provider} · ${citation.field}`)))
      : h("p", { className: "empty" }, "No citations"),
    h(MissingData, { values: [...(explanation.missingData ?? []), ...(claims.missingData ?? [])] }))
    : h("p", { className: "empty" }, state === "PROGRESS" ? "Explanation in progress…" : "No explanation yet"));
}

function RelatedEvents({ events = [] }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "EVENT RADAR"), h("h2", null, "Related events")),
      h(State, { value: "READY" })),
    events.length
      ? h("ul", { className: "list" }, ...events.map(event => h("li", { key: event.id },
        h("strong", null, event.summary), h("span", null,
          `${event.source ?? "—"} · ${event.occurredAt ?? "—"} · ${event.reviewStatus ?? "PENDING"}`))))
      : h("p", { className: "empty" }, "No related events"));
}

function SnapshotHistory({ history = [], onSelectSnapshot }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "IMMUTABLE INPUTS"), h("h2", null, "Snapshot history")),
      h(State, { value: "READY" })),
    history.length
      ? h("ul", { className: "list" }, ...history.map(item => h("li", { key: item.runId },
        h("div", null,
          h("strong", null, `${item.status} · ${item.completedAt ?? item.startedAt ?? "—"}`),
          h("span", null, `run ${item.runId} · snapshot ${item.inputSnapshotId ?? "—"}`),
          item.errorCode ? h("small", null, item.errorCode) : null),
        h("button", { type: "button", className: "secondary", onClick: () => onSelectSnapshot(item.runId) },
          "View snapshot"))))
      : h("p", { className: "empty" }, "No snapshots yet"));
}

export function StockAnalysisProductSurface({
  symbol,
  analysis,
  forecast,
  explanation,
  relatedEvents,
  history,
  status = {},
  errors = {},
  onCreateAnalysis,
  onCreateForecast,
  onCreateExplanation,
  onSelectSnapshot
}) {
  return h("main", { className: "stock-surface" },
    h("header", { className: "stock-surface-heading" },
      h("p", { className: "eyebrow" }, "STOCK ANALYSIS"), h("h1", null, symbol)),
    h("div", { className: "stock-surface-grid" },
      h(AnalysisPanel, {
        analysis, state: status.analysis ?? "READY", error: errors.analysis, onCreate: onCreateAnalysis
      }),
      h(ForecastPanel, {
        forecast, state: status.forecast ?? "READY", error: errors.forecast, onCreate: onCreateForecast
      }),
      h(ExplanationPanel, {
        explanation, state: status.explanation ?? "READY", error: errors.explanation, onCreate: onCreateExplanation
      }),
      h(RelatedEvents, { events: relatedEvents }),
      h(SnapshotHistory, { history, onSelectSnapshot })));
}
