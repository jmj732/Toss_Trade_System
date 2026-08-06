"use client";

import { createElement as h } from "react";

import { formatInstant, localInputToInstant } from "../lib/format.js";

function displayInstant(value) {
  return value ? formatInstant(value) : "없음";
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
      expiresAt: localInputToInstant(data.get("expiresAt"))
    })).then(() => form.reset()).catch(() => {});
  }

  function rotate(event, id) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    Promise.resolve(onRotate(id, { expiresAt: localInputToInstant(data.get("expiresAt")) }))
      .catch(() => {});
  }

  return h("section", { className: "prediction-operations panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "예측 수집"),
        h("h2", null, "예측 운영")),
      h("button", {
        type: "button",
        className: "secondary",
        disabled: busy,
        onClick: () => Promise.resolve(onRefresh()).catch(() => {})
      },
        "새로고침")),
    h("p", { className: "disclaimer" },
      "사용자 소유 API key와 평가 backlog만 표시합니다. 자동 예측이나 주문 실행 기능은 없습니다."),
    h("div", { className: "metrics-grid" },
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "평가"),
        h("span", { className: "metric-value" },
          operations?.evaluationEnabled ? "평가 활성" : "평가 비활성")),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "대기 백로그"),
        h("span", { className: "metric-value" }, operations?.backlog ?? "—")),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "최대 지연"),
        h("span", { className: "metric-value" },
          operations ? lag(operations.maxLagMs) : "—")),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "장기 미채점"),
        h("span", { className: "metric-value" }, operations?.longUngradedCount ?? "—")),
      h("div", { className: "metric" },
        h("span", { className: "metric-label" }, "가장 오래된 장기 미채점 예정"),
        h("span", { className: "metric-value" },
          operations?.oldestLongUngradedDueAt ? formatInstant(operations.oldestLongUngradedDueAt) : "—"))),
    issuedKey ? h("div", { className: "issued-api-key", role: "status" },
      h("strong", null, "이 키는 한 번만 표시됩니다"),
      h("code", null, issuedKey.apiKey),
      h("button", { type: "button", className: "secondary", onClick: onDismissKey }, "닫기")) : null,
    h("form", { className: "prediction-registry-form", onSubmit: issue },
      h("label", null, "모델 버전",
        h("input", { name: "modelVersion", maxLength: 50, required: true })),
      h("label", null, "계약 버전",
        h("input", { name: "contractVersion", maxLength: 50, required: true })),
      h("label", null, "만료 시각 (선택)",
        h("input", { type: "datetime-local", name: "expiresAt" })),
      h("button", { type: "submit", disabled: busy }, "API 키 발급")),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    keys.length === 0
      ? h("p", { className: "empty" }, "예측 수집 API 키가 없습니다")
      : h("div", {
        className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "예측 수집 API 키 표"
      }, h("table", null,
        h("thead", null, h("tr", null,
          ...["접두사", "모델", "계약", "상태", "만료", "마지막 사용", "작업"].map(th))),
        h("tbody", null, ...keys.map(key => h("tr", { key: key.id },
          h("td", null, h("code", null, key.prefix)),
          h("td", null, key.modelVersion),
          h("td", null, key.contractVersion),
          h("td", null, key.status),
          h("td", null, displayInstant(key.expiresAt)),
          h("td", null, displayInstant(key.lastUsedAt)),
          h("td", null,
            h("form", { className: "key-rotation-form", onSubmit: event => rotate(event, key.id) },
              h("input", {
                type: "datetime-local",
                name: "expiresAt",
                "aria-label": `${key.prefix} 키의 새 만료 시각`,
                title: "비워두면 현재 만료가 유지됩니다",
                disabled: busy || key.status !== "ACTIVE"
              }),
              h("button", {
                type: "submit",
                disabled: busy || key.status !== "ACTIVE"
              }, "교체")),
            h("button", {
              type: "button",
              disabled: busy || key.status !== "ACTIVE",
              onClick: () => Promise.resolve(onRevoke(key.id)).catch(() => {})
            }, "해지"))))))));
}
