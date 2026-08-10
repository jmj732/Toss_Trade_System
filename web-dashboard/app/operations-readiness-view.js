"use client";

import { createElement as h } from "react";

function lag(value) {
  const seconds = Math.max(0, Math.floor(Number(value || 0) / 1000));
  if (seconds < 60) return `${seconds}s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}m ${seconds % 60}s`;
}

function statusTone(status) {
  switch (status) {
    case "HEALTHY":
    case "READY":
    case "FRESH":
    case "NOT_REQUIRED":
    case "DISABLED":
      return "ok";
    case "BLOCKED":
    case "SECRET_MISSING":
    case "UNAVAILABLE":
      return "danger";
    case "NOT_CHECKED":
    case "NOT_CONFIGURED":
    case "DEGRADED":
    case "STALE":
      return "warn";
    default:
      return "neutral";
  }
}

function statusCopy(readiness, busy) {
  if (!readiness) return busy ? "초기 상태 확인 중" : "준비 상태가 없습니다";
  let copy;
  switch (readiness.status) {
    case "BLOCKED":
      copy = "운영 준비가 차단되었습니다";
      break;
    case "SECRET_MISSING":
      copy = "자격 증명이 필요합니다";
      break;
    case "NOT_CHECKED":
      copy = "제공자 점검 전입니다";
      break;
    case "NOT_CONFIGURED":
      copy = "제공자가 설정되지 않았습니다";
      break;
    case "UNAVAILABLE":
      copy = `사용할 수 없습니다 (${readiness.unavailableReason ?? "UNAVAILABLE"})`;
      break;
    case "DEGRADED":
    case "STALE":
      copy = "일부 준비 상태가 저하됐습니다";
      break;
    case "UNKNOWN":
      copy = "확인되지 않은 준비 상태가 있습니다";
      break;
    default:
      copy = "제공자 준비 상태를 확인했습니다";
  }
  return busy ? `새로고침 중 · ${copy}` : copy;
}

function readinessReasons(readiness) {
  const alerts = Array.isArray(readiness?.alerts) ? readiness.alerts : [];
  const blockers = Array.isArray(readiness?.canary?.blockers) ? readiness.canary.blockers : [];
  const providerReasons = (readiness?.providers ?? [])
    .filter(provider => provider.status &&
      (statusTone(provider.status) !== "ok" || provider.credentialConfigured === false))
    .map(provider => {
      const missingData = Array.isArray(provider.missingData) ? provider.missingData.filter(Boolean) : [];
      return `${provider.provider}: ${provider.status}${missingData.length ? ` (${missingData.join(", ")})` : ""}`;
    });
  return [...new Set([...alerts, ...blockers, ...providerReasons])].filter(Boolean).slice(0, 3);
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
  const reasons = readinessReasons(readiness);

  return h("section", { className: "operations-readiness panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "운영 관제"),
        h("h2", null, "운영 준비 상태")),
      h("button", { type: "button", className: "secondary", disabled: busy, onClick: onRefresh },
        "새로고침")),
    h("div", { className: "readiness-summary" },
      h("div", { className: "status-card status-card-primary" },
        h("span", { className: "metric-label" }, "제공자 준비 상태"),
        h("strong", null, statusCopy(readiness, busy)),
        h("span", { className: `badge-pill badge-pill--${statusTone(readiness?.status)}` },
          readiness?.status ?? "LOADING")),
      h("div", { className: "status-card" },
        h("span", { className: "metric-label" }, "계정 자격 증명"),
        h("strong", null, readiness?.providers?.length
          ? `${readiness.providers.filter(provider => provider.credentialConfigured).length}/${readiness.providers.length} 설정됨`
          : "등록된 제공자가 없습니다"))),
    reasons.length ? h("p", { className: "disclaimer status-reason" },
      `사유: ${reasons.join(" · ")}`) : null,
    h("p", { className: "disclaimer" },
      "실제 provider 점검은 주문을 생성하지 않습니다. live canary는 기본 비활성이고 실패 시 차단됩니다."),
    h("div", { className: "metrics-grid" },
      metric("전체", readiness?.status),
      metric("라이브 카나리", readiness?.canary?.status),
      metric("킬 스위치", readiness?.killSwitch?.status),
      metric("데이터 신선도", readiness?.dataFreshness?.status),
      metric("최대 지연", readiness ? lag(readiness.dataFreshness?.maxLagMs) : "—")),
    h("form", { className: "readiness-probe-form", onSubmit: probe },
      h("label", null, "제공자 종목",
        h("input", { name: "symbol", defaultValue: "AAPL", maxLength: 16, required: true })),
      h("button", { type: "submit", disabled: busy }, "제공자 점검 실행")),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    readiness && (readiness.providers?.length ?? 0) === 0
      ? h("p", { className: "empty" }, "등록된 제공자가 없습니다") : null,
    (readiness?.providers?.length ?? 0) > 0 ? h("div", { className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "제공자 운영 준비 상태 표" }, h("table", null,
      h("thead", null, h("tr", null,
        ...["제공자", "상태", "자격 증명", "지연", "누락 데이터"].map(label =>
          h("th", { key: label, scope: "col" }, label)))),
      h("tbody", null, ...(readiness?.providers ?? []).map(provider =>
        h("tr", { key: provider.provider },
          h("td", null, provider.provider),
          h("td", null, h("span", { className: `badge-pill badge-pill--${statusTone(provider.status)}` }, provider.status)),
          h("td", null, provider.credentialConfigured ? "설정됨" : "미설정"),
          h("td", null, provider.lagMs == null ? "—" : lag(provider.lagMs)),
          h("td", null, provider.missingData?.join(", ") || "—")))))) : null);
}

function metric(label, value) {
  return h("div", { className: "metric", key: label },
    h("span", { className: "metric-label" }, label),
    h("span", { className: "metric-value" }, value ?? "—"));
}
