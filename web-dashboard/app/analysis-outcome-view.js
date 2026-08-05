"use client";

import { createElement as h } from "react";

function percent(value) {
  return value == null ? "—" : `${(Number(value) * 100).toFixed(1)}%`;
}

function decimal(value) {
  return value == null ? "—" : Number(value).toFixed(4);
}

function driftLabel(drift) {
  if (!drift) {
    return "—";
  }
  const deltas = [];
  if (drift.hitRateDelta != null) {
    deltas.push(`hit ${percent(drift.hitRateDelta)}`);
  }
  if (drift.meanAbsoluteErrorDelta != null) {
    deltas.push(`MAE ${percent(drift.meanAbsoluteErrorDelta)}`);
  }
  if (drift.calibrationErrorDelta != null) {
    deltas.push(`cal ${percent(drift.calibrationErrorDelta)}`);
  }
  if (drift.degraded) {
    deltas.push("DEGRADED");
  }
  return [drift.status, ...deltas].join(" / ");
}

function qualityRow(row) {
  return h("tr", {
    key: `${row.symbol}-${row.modelVersion}-${row.contractVersion}-${row.horizon}`
  },
    h("td", null, row.symbol),
    h("td", null, row.modelVersion),
    h("td", null, row.contractVersion),
    h("td", null, row.horizon),
    h("td", null, row.status),
    h("td", null, `${row.sampleCount}/${row.minimumSampleCount}`),
    h("td", null, row.pendingCount),
    h("td", null, percent(row.hitRate)),
    h("td", null, row.horizon === "D1"
      ? "—"
      : `${percent(row.meanError)} / ${percent(row.meanAbsoluteError)}`),
    h("td", null, row.horizon === "D1"
      ? `${percent(row.calibrationError)} / ${decimal(row.brierScore)}`
      : "—"),
    h("td", null, driftLabel(row.drift)));
}

function th(label) {
  return h("th", { key: label, scope: "col" }, label);
}

function PerformanceTable({ rows }) {
  if (rows.length === 0) {
    return h("p", { className: "empty" }, "채점된 결과가 아직 없습니다");
  }
  return h("div", { className: "table-wrap" }, h("table", null,
    h("thead", null, h("tr", null,
      ...["모델", "계약", "예측 구간", "표본", "적중률", "평균 방향 수익률", "평균 최대 역행 폭"]
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
    return h("p", { className: "empty" }, "기록된 예측이 아직 없습니다");
  }
  return h("div", { className: "table-wrap" }, h("table", null,
    h("thead", null, h("tr", null,
      ...["예측 시각", "종목", "방향", "기준선", "모델", "계약", "D1", "D5", "D20"].map(th))),
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

function ForecastQualityTable({ quality }) {
  const rows = quality?.rows ?? [];
  if (rows.length === 0) {
    return h("p", { className: "empty" }, "예측 품질 데이터가 없습니다");
  }
  return h("div", { className: "table-wrap" },
    h("table", null,
      h("thead", null,
        h("tr", null,
          ["종목", "모델", "계약", "예측 구간", "상태", "표본", "대기",
            "적중률", "오차 / MAE", "캘리브레이션 / Brier", "드리프트"].map(th))),
      h("tbody", null, ...rows.map(qualityRow))));
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
    h("h3", null, "예측 모델 레지스트리"),
    h("form", { className: "prediction-registry-form", onSubmit: submit },
      h("label", null, "모델 버전",
        h("input", { name: "modelVersion", maxLength: 50, required: true })),
      h("label", null, "계약 버전",
        h("input", { name: "contractVersion", maxLength: 50, required: true })),
      h("button", { type: "submit", disabled: busy }, "버전 등록")),
    error ? h("p", { className: "error" }, error) : null,
    versions.length === 0
      ? h("p", { className: "empty" }, "등록된 모델 버전이 없습니다")
      : h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null, ...["모델", "계약", "상태", "작업"].map(th))),
        h("tbody", null, ...versions.map(version => h("tr", { key: version.id },
          h("td", null, version.modelVersion),
          h("td", null, version.contractVersion),
          h("td", null, version.status),
          h("td", null,
            h("button", {
              type: "button",
              disabled: busy || version.status !== "ACTIVE",
              onClick: () => Promise.resolve(onDeprecate(version.id)).catch(() => {})
            }, "지원 중단"),
            h("button", {
              type: "button",
              disabled: busy,
              onClick: () => Promise.resolve(onDelete(version.id)).catch(() => {})
            }, "삭제"))))))));
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
    h("label", null, "종목", h("input", { name: "symbol", required: true })),
    h("label", null, "통화",
      h("select", { name: "currency" }, h("option", { value: "USD" }, "USD"), h("option", { value: "KRW" }, "KRW"))),
    h("label", null, "방향",
      h("select", { name: "predictedDirection" },
        h("option", { value: "UP" }, "UP"), h("option", { value: "DOWN" }, "DOWN"))),
    h("label", null, "모델 / 계약 버전",
      h("select", { name: "versionId", required: true },
        ...active.map(version => h("option", { key: version.id, value: version.id },
          `${version.modelVersion} / ${version.contractVersion}`)))),
    active.length === 0
      ? h("p", { className: "empty" }, "먼저 ACTIVE 모델 버전을 등록하세요")
      : null,
    h("button", { type: "submit", disabled: busy || active.length === 0 }, "예측 기록"));
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
      contractVersion: data.get("contractVersion") || "",
      symbol: data.get("symbol") || ""
    });
  }

  const fromDate = query.from ? query.from.slice(0, 10) : "";
  const toDate = query.to ? query.to.slice(0, 10) : "";
  const predictions = performance?.predictions ?? [];
  const byVersion = performance?.byVersion ?? [];

  return h("section", { className: "analysis-outcome panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "분석 결과 추적"),
        h("h2", null, "예측 성과"))),
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
      h("label", null, "시작일",
        h("input", { type: "date", name: "from", defaultValue: fromDate })),
      h("label", null, "종료일",
        h("input", { type: "date", name: "to", defaultValue: toDate })),
      h("label", null, "모델 버전", h("input", { name: "modelVersion", defaultValue: query.modelVersion })),
      h("label", null, "계약 버전",
        h("input", { name: "contractVersion", defaultValue: query.contractVersion })),
      h("label", null, "종목", h("input", { name: "symbol", defaultValue: query.symbol })),
      h("button", { type: "submit", disabled: busy }, "적용")),
    h(PerformanceTable, { rows: byVersion }),
    h("h3", null, "예측 품질 모니터링"),
    h("p", { className: "disclaimer" },
      "D1/D5/D20는 기존 outcome 채점과 연결됩니다. 표본 부족 상태에서는 성능과 drift를 결론내리지 않습니다."),
    h(ForecastQualityTable, { quality: performance?.forecastQuality }),
    h(PredictionsTable, { predictions }));
}
