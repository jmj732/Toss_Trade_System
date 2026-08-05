"use client";

import { createElement as h } from "react";

function State({ value }) {
  return h("span", { className: `surface-state ${String(value).toLowerCase()}` }, value);
}

function Panel({ title, state, error, action, children }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "종목 화면"), h("h2", null, title)),
      h("div", { className: "panel-actions" }, h(State, { value: state }), action)),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    children);
}

function MissingData({ values = [] }) {
  return h("div", { className: "missing-data" },
    h("h3", null, "누락 데이터"),
    values.length
      ? h("ul", { className: "list" }, ...values.map(value => h("li", { key: value }, value)))
      : h("p", { className: "empty" }, "보고된 항목 없음"));
}

function Provenance({ values = [] }) {
  return h("div", { className: "provenance" },
    h("h3", null, "데이터 출처"),
    values.length
      ? h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...["제공자", "필드", "기준 시각", "수집 시각"].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...values.map((item, index) => h("tr", { key: `${item.provider}-${item.field}-${index}` },
          h("td", null, item.provider), h("td", null, item.field),
          h("td", null, item.asOf ?? "—"), h("td", null, item.collectedAt ?? "—"))))))
      : h("p", { className: "empty" }, "보고된 출처가 없습니다"));
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
    title: "분석",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS", onClick: onCreate
    }, analysis ? "분석 재실행" : "분석 생성")
  },
  result ? h("div", null,
    h("p", { className: "disclaimer" },
      `스냅샷 ${analysis.inputSnapshotId ?? "—"} · 기준 ${result.asOf ?? "—"}`),
    h("div", { className: "analysis-metrics" },
      metrics.length
        ? metrics.map(metric => h("div", { className: "metric", key: metric.name },
          h("span", { className: "metric-label" }, metric.name),
          h("span", { className: "metric-value" }, metric.value ?? "—"),
          h("small", null, metric.unit ?? "")))
        : h("p", { className: "empty" }, "분석 지표가 없습니다")),
    h(MissingData, { values: result.missingData }),
    h(Provenance, { values: provenance }))
    : h("p", { className: "empty" }, state === "PROGRESS" ? "분석 진행 중…" : "분석 결과가 아직 없습니다"));
}

function ForecastPanel({ forecast, state, error, onCreate }) {
  const result = forecast?.result ?? forecast;
  const metrics = result?.forecasts ?? [];
  const provenance = metrics.flatMap(metric => metric.provenance ?? []);
  return h(Panel, {
    title: "예측",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS", onClick: onCreate
    }, forecast ? "예측 재실행" : "예측 생성")
  },
  result ? h("div", null,
    h("p", { className: "disclaimer" },
      `스냅샷 ${forecast.inputSnapshotId ?? result.inputSnapshotId ?? "—"} · 신뢰도 ${result.confidence ?? "—"}`),
    metrics.length
      ? h("ul", { className: "list" }, ...metrics.map(metric => h("li", { key: metric.name },
        h("strong", null, metric.name), h("span", null, metric.value ?? "—"))))
      : h("p", { className: "empty" }, "예측 지표가 없습니다"),
    h(MissingData, { values: result.missingData }),
    h(Provenance, { values: provenance }))
    : h("p", { className: "empty" }, state === "PROGRESS" ? "예측 진행 중…" : "예측 결과가 아직 없습니다"));
}

function ExplanationPanel({ explanation, state, error, onCreate }) {
  const claims = explanation?.explanation ?? {};
  const citations = explanation?.citations ?? [];
  const evidence = [...(claims.evidence ?? []), ...(claims.counterArguments ?? [])];
  return h(Panel, {
    title: "Gemini 설명",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS", onClick: onCreate
    }, explanation ? "설명 재생성" : "설명 생성")
  },
  explanation ? h("div", null,
    h("p", { className: "disclaimer" }, `스냅샷 ${explanation.inputSnapshotId ?? "—"}`),
    evidence.length
      ? h("ul", { className: "list" }, ...evidence.map((claim, index) => h("li", { key: `${claim.text}-${index}` },
        h("strong", null, claim.text), h("span", null, claim.citationIds?.join(", ") ?? "출처 없음"))))
      : h("p", { className: "empty" }, "설명 근거가 없습니다"),
    h("h3", null, "출처"),
    citations.length
      ? h("ul", { className: "list" }, ...citations.map(citation => h("li", { key: citation.id },
        `${citation.id} · ${citation.provider} · ${citation.field}`)))
      : h("p", { className: "empty" }, "출처가 없습니다"),
    h(MissingData, { values: [...(explanation.missingData ?? []), ...(claims.missingData ?? [])] }))
    : h("p", { className: "empty" }, state === "PROGRESS" ? "설명 진행 중…" : "설명 결과가 아직 없습니다"));
}

function RelatedEvents({ events = [] }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "이벤트 레이더"), h("h2", null, "관련 이벤트")),
      h(State, { value: "READY" })),
    events.length
      ? h("ul", { className: "list" }, ...events.map(event => h("li", { key: event.id },
        h("strong", null, event.summary), h("span", null,
          `${event.source ?? "—"} · ${event.occurredAt ?? "—"} · ${event.reviewStatus ?? "PENDING"}`))))
      : h("p", { className: "empty" }, "관련 이벤트가 없습니다"));
}

function SnapshotHistory({ history = [], onSelectSnapshot }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "불변 입력"), h("h2", null, "스냅샷 이력")),
      h(State, { value: "READY" })),
    history.length
      ? h("ul", { className: "list" }, ...history.map(item => h("li", { key: item.runId },
        h("div", null,
          h("strong", null, `${item.status} · ${item.completedAt ?? item.startedAt ?? "—"}`),
          h("span", null, `실행 ${item.runId} · 스냅샷 ${item.inputSnapshotId ?? "—"}`),
          item.errorCode ? h("small", null, item.errorCode) : null),
        h("button", { type: "button", className: "secondary", onClick: () => onSelectSnapshot(item.runId) },
          "스냅샷 보기"))))
      : h("p", { className: "empty" }, "스냅샷이 아직 없습니다"));
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
      h("p", { className: "eyebrow" }, "종목 분석"), h("h1", null, symbol)),
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
