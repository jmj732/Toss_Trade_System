"use client";

import { createElement as h } from "react";

import { formatInstant, UNKNOWN_TEXT } from "../lib/format.js";

// 상태 코드는 화이트리스트로만 한국어 라벨에 매핑한다. 미등록 값은 원문을 함께 노출한다.
const STATE_LABELS = {
  IDLE: "조회 전",
  READY: "완료",
  PROGRESS: "진행 중",
  DEGRADED: "부분 저하",
  FAILED: "실패",
  UNKNOWN: "알 수 없음"
};

function stateLabel(value) {
  const key = String(value ?? "").toUpperCase();
  if (STATE_LABELS[key]) {
    return STATE_LABELS[key];
  }
  return value ? `알 수 없음 (${value})` : STATE_LABELS.UNKNOWN;
}

// V-36: 상태 배지는 공통 .badge-pill modifier 로만 색을 표현한다. 라벨 텍스트가
// progress("진행 중")·degraded("부분 저하")를 구분하므로 색에만 의존하지 않는다.
const STATE_MODIFIER = {
  IDLE: "neutral",
  READY: "ok",
  PROGRESS: "warn",
  DEGRADED: "warn",
  FAILED: "danger",
  UNKNOWN: "neutral"
};

function State({ value }) {
  const key = String(value ?? "").toUpperCase();
  const modifier = STATE_MODIFIER[key] ?? "neutral";
  return h("span", { className: `badge-pill badge-pill--${modifier}` }, stateLabel(value));
}

function panelEmptyCopy(state, kind) {
  if (state === "IDLE") {
    return `${kind} 조회 전입니다`;
  }
  if (state === "PROGRESS") {
    return `${kind} 진행 중…`;
  }
  return `${kind} 결과가 아직 없습니다`;
}

function instantCell(value) {
  return value ? formatInstant(value) : UNKNOWN_TEXT;
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
      ? h("div", {
        className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "데이터 출처 표"
      }, h("table", null,
        h("thead", null, h("tr", null,
          ...["제공자", "필드", "기준 시각", "수집 시각"].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...values.map((item, index) => h("tr", { key: `${item.provider}-${item.field}-${index}` },
          h("td", null, item.provider ?? UNKNOWN_TEXT), h("td", null, item.field ?? UNKNOWN_TEXT),
          h("td", null, instantCell(item.asOf)), h("td", null, instantCell(item.collectedAt)))))))
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

function Panel({ title, state, error, action, children }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null,
      h("div", null, h("p", { className: "eyebrow" }, "종목 화면"), h("h2", null, title)),
      h("div", { className: "panel-actions" }, h(State, { value: state }), action)),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    children);
}

function AnalysisPanel({ analysis, state, error, busy, onCreate }) {
  const { result, metrics, provenance } = analysisParts(analysis);
  return h(Panel, {
    title: "분석",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS" || busy, onClick: onCreate
    // 결과 없는 빈 envelope({result:null})는 "분석 없음"으로 보고 생성 버튼을 노출한다.
    }, result ? "분석 재실행" : "분석 생성")
  },
  result ? h("div", null,
    h("p", { className: "disclaimer" },
      `스냅샷 ${analysis.inputSnapshotId ?? UNKNOWN_TEXT} · 기준 ${instantCell(result.asOf)}`),
    h("div", { className: "analysis-metrics" },
      metrics.length
        ? metrics.map(metric => h("div", { className: "metric", key: metric.name },
          h("span", { className: "metric-label" }, metric.name ?? UNKNOWN_TEXT),
          h("span", { className: "metric-value" }, metric.value ?? UNKNOWN_TEXT),
          h("small", null, metric.unit ?? "")))
        : h("p", { className: "empty" }, "분석 지표가 없습니다")),
    h(MissingData, { values: result.missingData }),
    h(Provenance, { values: provenance }))
    : h("p", { className: "empty" }, panelEmptyCopy(state, "분석")));
}

function ForecastPanel({ forecast, state, error, busy, onCreate }) {
  const result = forecast?.result ?? forecast;
  const metrics = result?.forecasts ?? [];
  const provenance = metrics.flatMap(metric => metric.provenance ?? []);
  return h(Panel, {
    title: "예측",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS" || busy, onClick: onCreate
    }, forecast ? "예측 재실행" : "예측 생성")
  },
  result ? h("div", null,
    h("p", { className: "disclaimer" },
      `스냅샷 ${forecast.inputSnapshotId ?? result.inputSnapshotId ?? UNKNOWN_TEXT} · 신뢰도 ${result.confidence ?? UNKNOWN_TEXT}`),
    metrics.length
      ? h("ul", { className: "list" }, ...metrics.map(metric => h("li", { key: metric.name },
        h("strong", null, metric.name ?? UNKNOWN_TEXT), h("span", null, metric.value ?? UNKNOWN_TEXT))))
      : h("p", { className: "empty" }, "예측 지표가 없습니다"),
    h(MissingData, { values: result.missingData }),
    h(Provenance, { values: provenance }))
    : h("p", { className: "empty" }, panelEmptyCopy(state, "예측")));
}

function ExplanationPanel({ explanation, state, error, busy, onCreate }) {
  const claims = explanation?.explanation ?? {};
  const citations = explanation?.citations ?? [];
  const evidence = [...(claims.evidence ?? []), ...(claims.counterArguments ?? [])];
  return h(Panel, {
    title: "Gemini 설명",
    state,
    error,
    action: h("button", {
      type: "button", disabled: state === "PROGRESS" || busy, onClick: onCreate
    }, explanation ? "설명 재생성" : "설명 생성")
  },
  explanation ? h("div", null,
    h("p", { className: "disclaimer" }, `스냅샷 ${explanation.inputSnapshotId ?? UNKNOWN_TEXT}`),
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
    : h("p", { className: "empty" }, panelEmptyCopy(state, "설명")));
}

function RelatedEvents({ events = [] }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "이벤트 레이더"), h("h2", null, "관련 이벤트"))),
    events.length
      ? h("ul", { className: "list" }, ...events.map(event => h("li", { key: event.id },
        h("strong", null, event.summary), h("span", null,
          `${event.source ?? UNKNOWN_TEXT} · ${instantCell(event.occurredAt)} · ${event.reviewStatus ?? "PENDING"}`))))
      : h("p", { className: "empty" }, "관련 이벤트가 없습니다"));
}

function SnapshotHistory({ history = [], onSelectSnapshot }) {
  return h("section", { className: "panel stock-surface-panel" },
    h("header", null, h("div", null, h("p", { className: "eyebrow" }, "불변 입력"), h("h2", null, "스냅샷 이력"))),
    history.length
      ? h("ul", { className: "list" }, ...history.map(item => h("li", { key: item.runId },
        h("div", null,
          h("strong", null, `${item.status ?? UNKNOWN_TEXT} · ${instantCell(item.completedAt ?? item.startedAt)}`),
          h("span", null, `실행 ${item.runId ?? UNKNOWN_TEXT} · 스냅샷 ${item.inputSnapshotId ?? UNKNOWN_TEXT}`),
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
  busy = false,
  onCreateAnalysis,
  onCreateForecast,
  onCreateExplanation,
  onSelectSnapshot
}) {
  return h("main", { className: "stock-surface" },
    h("header", { className: "stock-surface-heading" },
      h("p", { className: "eyebrow" }, "종목 분석"), h("h2", null, symbol)),
    h("div", { className: "stock-surface-grid" },
      h(AnalysisPanel, {
        analysis, state: status.analysis ?? "IDLE", error: errors.analysis, busy, onCreate: onCreateAnalysis
      }),
      h(ForecastPanel, {
        forecast, state: status.forecast ?? "IDLE", error: errors.forecast, busy, onCreate: onCreateForecast
      }),
      h(ExplanationPanel, {
        explanation, state: status.explanation ?? "IDLE", error: errors.explanation, busy, onCreate: onCreateExplanation
      }),
      h(RelatedEvents, { events: relatedEvents }),
      h(SnapshotHistory, { history, onSelectSnapshot })));
}
