import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { EventWorkflow } from "../app/event-workflow.js";

test("renders manual ingestion, affected symbols, review, and comparison", () => {
  const html = renderToStaticMarkup(createElement(EventWorkflow, {
    positions: [{ symbol: "NVDA" }, { symbol: "AAPL" }],
    events: [{
      id: "event-1",
      summary: "Rate decision",
      type: "MACRO",
      affectedSymbols: ["NVDA"],
      reviewStatus: "HELD",
      reviewVersion: 2,
      comparisonAvailable: true
    }],
    selectedEvent: {
      id: "event-1",
      summary: "Rate decision",
      reviewStatus: "HELD",
      reviewVersion: 2,
      analysisComparison: {
        comparison: {
          baselineAvailable: true,
          positions: [{
            symbol: "NVDA",
            currency: "USD",
            beforeMarketValue: 100,
            afterMarketValue: 120,
            marketValueChange: 20,
            beforeProfitLoss: 10,
            afterProfitLoss: 20,
            profitLossChange: 10,
            beforeWeight: 0.5,
            afterWeight: 0.6,
            weightChange: 0.1
          }],
          currencyTotals: [{
            currency: "USD",
            beforeMarketValue: 100,
            afterMarketValue: 120,
            marketValueChange: 20,
            beforeProfitLoss: 10,
            afterProfitLoss: 20,
            profitLossChange: 10,
            beforeConcentration: 0.5,
            afterConcentration: 0.6,
            concentrationChange: 0.1
          }]
        }
      }
    },
    connectionId: "connection-1",
    busyAction: null,
    onCreate() {},
    onSelect() {},
    onReanalyze() {},
    onReview() {}
  }));

  for (const text of [
    "Manual event", "Source event ID", "Rate decision", "HELD · v2",
    "Reanalyze", "Confirm", "Hold", "Ignore", "Before", "After", "Change",
    "NVDA", "AAPL", "Currency totals", "Market value", "Profit / loss",
    "Weight", "Concentration"
  ]) {
    assert.match(html, new RegExp(text));
  }
  assert.equal((html.match(/type="checkbox"/g) ?? []).length, 2);
  assert.doesNotMatch(html, /News collection|LLM|Automatic order/);
});

test("disables all event mutations while one is running", () => {
  const html = renderToStaticMarkup(createElement(EventWorkflow, {
    positions: [{ symbol: "NVDA" }],
    events: [],
    selectedEvent: null,
    connectionId: "connection-1",
    busyAction: "event-create",
    onCreate() {},
    onSelect() {},
    onReanalyze() {},
    onReview() {}
  }));

  assert.equal((html.match(/disabled=""/g) ?? []).length, 5);
  assert.match(html, /event-create…/);
});

test("scopes affected-symbol state to the current connection", () => {
  const source = readFileSync(
    new URL("../app/page.js", import.meta.url),
    "utf8");

  assert.match(
    source,
    /h\(EventWorkflow, \{\s*key: connectionId\.trim\(\),/);
});
