"use client";

import { createElement as h, useEffect, useId } from "react";

function HistoryList({ history }) {
  if (history.length === 0) {
    return h("p", { className: "empty" }, "변경 이력이 아직 없습니다");
  }
  return h("ul", { className: "list risk-policy-history" }, ...history.map(entry =>
    h("li", { key: entry.version },
      h("div", null,
        h("strong", null, `v${entry.version}`),
        h("span", null, `${entry.changedBy} · ${entry.changedAt}`)),
      h("span", null,
        `KRW ${entry.maxOrderAmountKrw} · USD ${entry.maxOrderAmountUsd} · `
        + `수량 ${entry.maxQuantity} · 집중도 ${entry.maxConcentration}`))));
}

function PolicyStatus({ policy }) {
  return h("div", { className: "risk-policy-status" },
    h("span", { className: "metric-label" }, "현재 정책"),
    policy
      ? [
        h("span", {
          key: "badge",
          className: `badge-pill ${policy.customized ? "badge-pill--info" : "badge-pill--neutral"}`
        }, policy.customized ? "CUSTOM" : "DEFAULT"),
        h("strong", { key: "version" }, `v${policy.version}`),
        h("span", { key: "limits" },
          `KRW ${policy.maxOrderAmountKrw} · USD ${policy.maxOrderAmountUsd} · `
          + `수량 ${policy.maxQuantity}`)
      ]
      : h("strong", null, "불러오는 중"));
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
  const panelId = useId();
  // V-29: 열려 있을 때만 Escape 로 닫는다. open 상태는 부모 소유이므로 onToggle 로 위임한다.
  useEffect(() => {
    if (!open) {
      return undefined;
    }
    function onKeyDown(event) {
      if (event.key === "Escape") {
        onToggle();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [open, onToggle]);

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
    h(PolicyStatus, { policy }),
    h("button", {
      type: "button",
      className: "secondary risk-policy-toggle",
      "aria-expanded": open,
      "aria-controls": panelId,
      onClick: onToggle
    }, "리스크 정책"),
    open ? h("div", { id: panelId, className: "risk-policy-panel panel" },
      h("header", null,
        h("h2", null, "주문 리스크 한도"),
        policy
          ? h("span", {
            className: `badge-pill ${policy.customized ? "badge-pill--info" : "badge-pill--neutral"}`
          }, policy.customized ? "CUSTOM" : "DEFAULT")
          : null),
      !policy
        ? h("p", { className: "empty" }, "불러오는 중…")
        // Keyed on version so the form remounts (and its defaultValue inputs refresh) after
        // every successful save, instead of silently keeping the previous values on screen.
        : h("form", {
          key: policy.version, className: "risk-policy-form", onSubmit: submit
        },
        h("label", null, "최대 주문 금액 (KRW)",
          h("input", {
            type: "number", name: "maxOrderAmountKrw", step: "any", min: "0",
            defaultValue: policy.maxOrderAmountKrw, required: true
          })),
        h("label", null, "최대 주문 금액 (USD)",
          h("input", {
            type: "number", name: "maxOrderAmountUsd", step: "any", min: "0",
            defaultValue: policy.maxOrderAmountUsd, required: true
          })),
        h("label", null, "최대 수량",
          h("input", {
            type: "number", name: "maxQuantity", step: "any", min: "0",
            defaultValue: policy.maxQuantity, required: true
          })),
        h("label", null, "최대 종목 집중도 (0–1)",
          h("input", {
            type: "number", name: "maxConcentration", step: "any", min: "0", max: "1",
            defaultValue: policy.maxConcentration, required: true
          })),
        h("button", { type: "submit", disabled: busy }, "저장")),
      h("h3", null, "변경 이력"),
      h("button", {
        type: "button", className: "secondary", disabled: busy, onClick: onLoadHistory
      }, "이력 불러오기"),
      h(HistoryList, { history })) : null);
}
