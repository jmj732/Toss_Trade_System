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
    h("label", null, "Client ID",
      h("input", {
        name: "clientId",
        type: "password",
        autoComplete: "off",
        required: true
      })),
    h("label", null, "Client secret",
      h("input", {
        name: "clientSecret",
        type: "password",
        autoComplete: "off",
        required: true
      })),
    h("button", { type: "submit", disabled }, title));
}

export function BrokerOnboarding({
  connection,
  connectionId,
  busyAction,
  onCredentials,
  onCommand
}) {
  const busy = Boolean(busyAction);
  const unavailable = busy || !connectionId;
  return h("section", { className: "onboarding panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "BROKER ONBOARDING"),
        h("h2", null, "Toss connection")),
      busyAction ? h("span", { className: "busy" }, `${busyAction}…`) : null),
    connection ? h("dl", { className: "connection-meta" },
      h("div", null, h("dt", null, "Status"), h("dd", null, connection.status)),
      h("div", null, h("dt", null, "Revision"), h("dd", null, connection.credentialRevision)),
      h("div", null, h("dt", null, "Validated"), h("dd", null,
        connection.lastValidatedAt ?? "Not yet"))) : null,
    h("div", { className: "credential-grid" },
      h(CredentialForm, {
        title: "Create Toss connection",
        action: "create",
        disabled: busy,
        onCredentials
      }),
      h(CredentialForm, {
        title: "Replace credentials",
        action: "replace",
        disabled: unavailable,
        onCredentials
      })),
    h("div", { className: "onboarding-actions" },
      [
        ["verify", "Verify"],
        ["sync", "Sync portfolio"],
        ["analysis", "Run analysis"],
        ["delete", "Delete"]
      ].map(([action, label]) => {
        return h("button", {
          key: action,
          type: "button",
          className: action === "delete" ? "danger" : "secondary",
          disabled: unavailable,
          onClick: () => onCommand(action)
        }, label);
      })));
}
