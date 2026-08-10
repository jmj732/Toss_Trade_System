"use client";

import { createElement as h } from "react";

function CredentialForm({ title, action, disabled, onCredentials }) {
  function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    const credentials = {
      clientId: data.get("clientId"),
      clientSecret: data.get("clientSecret")
    };
    form.reset();
    onCredentials(action, credentials);
  }

  return h("form", { className: "credential-form", autoComplete: "off", onSubmit: submit },
    h("h3", null, title),
    h("label", null, "앱 키 (Client ID)",
      h("input", {
        name: "clientId",
        type: "password",
        autoComplete: "off",
        required: true
      })),
    h("label", null, "시크릿 키 (Client secret)",
      h("input", {
        name: "clientSecret",
        type: "password",
        autoComplete: "off",
        required: true
      })),
    h("button", { type: "submit", disabled }, action === "create" ? "계좌 연결" : "연결 정보 변경"));
}

export function BrokerOnboarding({
  connection,
  connectionId,
  busyAction,
  onCredentials,
  onCommand
}) {
  const busy = Boolean(busyAction);
  const hasConnection = Boolean(connectionId);
  const active = connection?.status === "ACTIVE";
  const unavailable = busy || !hasConnection;
  const accountStatus = hasConnection ? (connection?.status ?? "UNKNOWN") : "NOT_CONNECTED";
  return h("section", {
    className: `onboarding panel${hasConnection ? "" : " onboarding-first-run"}`,
    "aria-busy": busy
  },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "ACCOUNT CONNECTION"),
        h("h2", null, hasConnection ? "계좌 연결됨" : "토스증권 계좌 연결")),
      busyAction ? h("span", { className: "busy" }, `${busyAction}…`) : null),
    !hasConnection ? h("p", { className: "onboarding-lead" },
      "계좌를 연결하고 시작하세요. 키는 암호화되어 저장되며 화면에 다시 표시되지 않습니다.") : null,
    h("section", { className: "account-readiness" },
      h("h3", null, "계좌 준비 상태"),
      h("dl", { className: "connection-meta" },
        h("div", null, h("dt", null, "상태"), h("dd", null, accountStatus)),
        hasConnection ? h("div", null, h("dt", null, "버전"), h("dd", null,
          connection?.credentialRevision ?? "—")) : null,
        hasConnection ? h("div", null, h("dt", null, "최근 확인"), h("dd", null,
          connection?.lastValidatedAt ?? "아직 확인 전")) : null)),
    hasConnection ? h("section", { className: "onboarding-command-section" },
      h("h3", null, "계좌 작업"),
      h("div", { className: "onboarding-actions" },
        [
          ["verify", "연결 확인"],
          ["sync", "포트폴리오 동기화"],
          ["analysis", "분석 실행"]
        ].map(([action, label]) => h("button", {
          key: action,
          type: "button",
          className: "secondary",
          disabled: unavailable || ((action === "sync" || action === "analysis") && !active),
          onClick: () => onCommand(action)
        }, label)))) : null,
    h("section", { className: "credential-section" },
      h("h3", null, "보안 자격 증명"),
      h("div", { className: "credential-grid" },
        h(CredentialForm, {
          title: hasConnection ? "연결 정보 변경" : "앱 키 입력",
          action: hasConnection ? "replace" : "create",
          disabled: hasConnection ? unavailable : busy,
          onCredentials
        }))),
    hasConnection ? h("section", { className: "onboarding-command-section" },
      h("h3", null, "위험 작업"),
      h("div", { className: "onboarding-actions" },
        h("button", {
          type: "button",
          className: "danger",
          disabled: unavailable,
          onClick: () => onCommand("delete")
        }, "연결 삭제"))) : null);
}
