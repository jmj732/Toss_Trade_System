import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { RiskPolicyPanel } from "../app/risk-policy-view.js";

test("shows the toggle without the panel when closed", () => {
  const html = renderToStaticMarkup(createElement(RiskPolicyPanel, {
    policy: null,
    history: [],
    open: false,
    busy: false,
    onToggle() {},
    onUpdate() {},
    onLoadHistory() {}
  }));

  assert.match(html, /Risk policy/);
  assert.doesNotMatch(html, /risk-policy-panel/);
});

test("renders the default badge and prefilled limits when open and uncustomized", () => {
  const html = renderToStaticMarkup(createElement(RiskPolicyPanel, {
    policy: {
      version: 0, customized: false,
      maxOrderAmountKrw: 10000000, maxOrderAmountUsd: 10000,
      maxQuantity: 100, maxConcentration: 0.25
    },
    history: [],
    open: true,
    busy: false,
    onToggle() {},
    onUpdate() {},
    onLoadHistory() {}
  }));

  assert.match(html, /class="default"[^>]*>DEFAULT/);
  assert.match(html, /value="10000000"/);
  assert.match(html, /value="0\.25"/);
});

test("renders the custom badge once the user has saved a policy", () => {
  const html = renderToStaticMarkup(createElement(RiskPolicyPanel, {
    policy: {
      version: 2, customized: true,
      maxOrderAmountKrw: 500000, maxOrderAmountUsd: 500,
      maxQuantity: 2, maxConcentration: 0.3
    },
    history: [],
    open: true,
    busy: false,
    onToggle() {},
    onUpdate() {},
    onLoadHistory() {}
  }));

  assert.match(html, /class="customized"[^>]*>CUSTOM/);
});

test("lists change history entries", () => {
  const html = renderToStaticMarkup(createElement(RiskPolicyPanel, {
    policy: { version: 1, customized: true, maxOrderAmountKrw: 1, maxOrderAmountUsd: 1, maxQuantity: 1, maxConcentration: 0.1 },
    history: [
      {
        version: 1, maxOrderAmountKrw: 500000, maxOrderAmountUsd: 500,
        maxQuantity: 2, maxConcentration: 0.3, changedBy: "user-1", changedAt: "2026-07-29T00:00:00Z"
      }
    ],
    open: true,
    busy: false,
    onToggle() {},
    onUpdate() {},
    onLoadHistory() {}
  }));

  assert.match(html, /v1/);
  assert.match(html, /user-1/);
  assert.match(html, /500000/);
});

test("disables save and load-history while a mutation is running", () => {
  const html = renderToStaticMarkup(createElement(RiskPolicyPanel, {
    policy: { version: 0, customized: false, maxOrderAmountKrw: 1, maxOrderAmountUsd: 1, maxQuantity: 1, maxConcentration: 0.1 },
    history: [],
    open: true,
    busy: true,
    onToggle() {},
    onUpdate() {},
    onLoadHistory() {}
  }));

  assert.equal((html.match(/disabled=""/g) ?? []).length, 2);
});
