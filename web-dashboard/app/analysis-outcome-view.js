"use client";

import { createElement as h } from "react";

import { formatAmount, formatInstant } from "../lib/format.js";

function percent(value) {
  return value == null ? "—" : `${(Number(value) * 100).toFixed(1)}%`;
}

function decimal(value) {
  return value == null ? "—" : Number(value).toFixed(4);
}

function statusPills({ value, busy, hasData, partial }) {
  const pills = [];
  if (busy) {
    pills.push(["info", value ? "새로고침 중" : "불러오는 중"]);
  }
  if (value?.status === "UNAUTHORIZED" || value?.unavailableReason === "UNAUTHORIZED") {
    pills.push(["danger", "권한 확인 필요"]);
  } else if (value?.status === "ERROR") {
    pills.push(["danger", "오류"]);
  } else if (value?.status === "UNAVAILABLE") {
    pills.push(["neutral", `지원되지 않음 (${value.unavailableReason ?? "UNAVAILABLE"})`]);
  } else if (value?.status === "DEGRADED") {
    pills.push(["warn", hasData ? "부분 데이터" : "저하"]);
  }
  if (value?.stale) {
    pills.push(["warn", "지연 데이터"]);
  }
  if (partial) {
    pills.push(["warn", "일부 누락"]);
  }
  if (pills.length === 0) {
    pills.push(["neutral", hasData ? "최신" : "데이터 없음"]);
  }
  return pills;
}

function StatusPills(props) {
  return h("div", { className: "prediction-state-pills" },
    ...statusPills(props).map(([modifier, label]) =>
      h("span", { className: `badge-pill badge-pill--${modifier}`, key: label }, label)));
}

function driftLabel(drift) {
  if (!drift) {
    return "—";
  }
  // D-41: 한국어 UI 에 맞춰 축약 영문을 한국어 라벨로 바꾼다. 숫자와 정보량은 유지한다.
  // drift.status 는 서버 계약 enum 이므로 원문을 유지한다(커밋 e6bca8a 방침).
  const deltas = [];
  if (drift.hitRateDelta != null) {
    deltas.push(`적중률 변화 ${percent(drift.hitRateDelta)}`);
  }
  if (drift.meanAbsoluteErrorDelta != null) {
    deltas.push(`평균절대오차 변화 ${percent(drift.meanAbsoluteErrorDelta)}`);
  }
  if (drift.calibrationErrorDelta != null) {
    deltas.push(`보정오차 변화 ${percent(drift.calibrationErrorDelta)}`);
  }
  if (drift.degraded) {
    deltas.push("성능 저하");
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
  return h("div", {
    className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "버전별 예측 성과 표"
  }, h("table", null,
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
  // directionCorrect 가 boolean 으로 확정되기 전에는 채점 대기 상태다.
  // null/undefined 를 MISS 로 단언하면 모델 성과가 실제보다 나쁘게 오인된다.
  const grade = outcome.directionCorrect == null
    ? "채점 대기"
    : outcome.directionCorrect ? "HIT" : "MISS";
  return h("td", null, `${percent(outcome.actualReturn)} ${grade}`);
}

function PredictionsTable({ predictions }) {
  if (predictions.length === 0) {
    return h("p", { className: "empty" }, "기록된 예측이 아직 없습니다");
  }
  return h("div", {
    className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "기록된 예측 표"
  }, h("table", null,
    h("thead", null, h("tr", null,
      ...["예측 시각", "종목", "방향", "기준선", "모델", "계약", "D1", "D5", "D20"].map(th))),
    h("tbody", null, ...predictions.map(prediction => h("tr", { key: prediction.id },
      h("td", null, formatInstant(prediction.predictedAt)),
      h("td", null, prediction.symbol),
      h("td", null, prediction.predictedDirection),
      h("td", null, formatAmount(prediction.currency, prediction.baselinePrice)),
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
  return h("div", {
    className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "예측 품질 모니터링 표"
  },
    h("table", null,
      h("thead", null,
        h("tr", null,
          ["종목", "모델", "계약", "예측 구간", "상태", "표본", "대기",
            "적중률", "오차 / MAE", "캘리브레이션 / Brier", "드리프트"].map(th))),
      h("tbody", null, ...rows.map(qualityRow))));
}

function OutcomeLead({ performance, busy }) {
  const predictions = performance?.predictions ?? [];
  const byVersion = performance?.byVersion ?? [];
  const qualityRows = performance?.forecastQuality?.rows ?? [];
  const hasData = predictions.length > 0 || byVersion.length > 0 || qualityRows.length > 0;
  const partial = performance && !performance.forecastQuality && hasData;
  return h("div", { className: "prediction-lead" },
    h("div", null,
      h("h3", null, "예측 품질"),
      h(StatusPills, { value: performance, busy, hasData, partial }),
      performance?.asOf
        ? h("small", { className: "metric-freshness" }, `기준 ${formatInstant(performance.asOf)}`)
        : null),
    h("div", { className: "metrics-grid" },
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "예측 기록"),
        h("span", { className: "metric-value" }, predictions.length)),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "성과 요약"),
        h("span", { className: "metric-value" }, byVersion.length)),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "품질 행"),
        h("span", { className: "metric-value" }, qualityRows.length))));
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
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    versions.length === 0
      ? h("p", { className: "empty" }, "등록된 모델 버전이 없습니다")
      : h("div", {
        className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "예측 모델 레지스트리 표"
      }, h("table", null,
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
              onClick: () => {
                if (typeof window !== "undefined"
                    && !window.confirm(`모델 버전 ${version.modelVersion} / ${version.contractVersion} 을(를) 삭제할까요? 되돌릴 수 없습니다.`)) {
                  return;
                }
                Promise.resolve(onDelete(version.id)).catch(() => {});
              }
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
    h(OutcomeLead, { performance, busy }),
    h(PerformanceTable, { rows: byVersion }),
    h("h3", null, "예측 품질 모니터링"),
    h("p", { className: "disclaimer" },
      "D1/D5/D20는 기존 outcome 채점과 연결됩니다. 표본 부족 상태에서는 성능과 drift를 결론내리지 않습니다."),
    h(ForecastQualityTable, { quality: performance?.forecastQuality }),
    h(PredictionsTable, { predictions }),
    h("form", { className: "history-filter", onSubmit: submitFilter },
      h("label", null, "시작일 (UTC 자정 기준)",
        h("input", { type: "date", name: "from", defaultValue: fromDate })),
      h("label", null, "종료일 (UTC 자정 기준)",
        h("input", { type: "date", name: "to", defaultValue: toDate })),
      h("label", null, "모델 버전", h("input", { name: "modelVersion", defaultValue: query.modelVersion })),
      h("label", null, "계약 버전",
        h("input", { name: "contractVersion", defaultValue: query.contractVersion })),
      h("label", null, "종목", h("input", { name: "symbol", defaultValue: query.symbol })),
      h("button", { type: "submit", disabled: busy }, "적용")),
    h(CreateForm, { versions, busy: createBusy, onCreate }),
    createError ? h("p", { className: "error", role: "alert" }, createError) : null,
    h(RegistryPanel, {
      versions,
      busy: registryBusy,
      error: registryError,
      onRegister,
      onDeprecate,
      onDelete
    }));
}
