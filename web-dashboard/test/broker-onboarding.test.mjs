import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { BrokerOnboarding } from "../app/broker-onboarding.js";

test("renders secret-free credential and lifecycle controls", () => {
  const html = renderToStaticMarkup(createElement(BrokerOnboarding, {
    connection: {
      id: "connection-1",
      brokerType: "TOSS_INVEST",
      status: "ACTIVE",
      credentialRevision: 2,
      lastValidatedAt: "2026-07-28T00:00:00Z"
    },
    connectionId: "connection-1",
    busyAction: null,
    onCredentials() {},
    onCommand() {}
  }));

  assert.match(html, /Create Toss connection/);
  assert.match(html, /Replace credentials/);
  for (const label of ["Verify", "Sync portfolio", "Run analysis", "Delete"]) {
    assert.match(html, new RegExp(label));
  }
  assert.equal((html.match(/type="password"/g) ?? []).length, 4);
  assert.equal((html.match(/autoComplete="off"/g) ?? []).length, 6);
  assert.doesNotMatch(html, /value="[^"]+"/);
  assert.doesNotMatch(html, /client-secret-canary|client-id-canary/);
});

test("disables every onboarding command during one mutation", () => {
  const html = renderToStaticMarkup(createElement(BrokerOnboarding, {
    connection: null,
    connectionId: "connection-1",
    busyAction: "sync",
    onCredentials() {},
    onCommand() {}
  }));

  assert.equal((html.match(/disabled=""/g) ?? []).length, 6);
  assert.match(html, /sync…/);
});
