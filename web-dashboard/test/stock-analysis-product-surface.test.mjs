import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { StockAnalysisProductSurface } from "../app/stock-analysis-product-surface.js";

const analysis = {
  runId: "run-1",
  inputSnapshotId: "snapshot-1",
  symbol: "AAPL",
  completedAt: "2026-08-03T00:00:00Z",
  result: {
    status: "DEGRADED",
    asOf: "2026-08-02T00:00:00Z",
    missingData: ["marketRegime:FIELD_MISSING:macro.vix"],
    observations: [{
      provider: "FMP", field: "quote.price", asOf: "2026-08-02T00:00:00Z",
      collectedAt: "2026-08-03T00:00:00Z", value: "200", unit: "USD"
    }],
    analyzers: [{
      analyzer: "valuation", confidence: "0.5", missingData: [], metrics: [{
        name: "valuation.pe", value: "20", unit: "multiple",
        asOf: "2026-08-02T00:00:00Z", missingData: [], provenance: [{
          provider: "FMP", field: "fundamental.eps", asOf: "2026-08-02T00:00:00Z",
          collectedAt: "2026-08-03T00:00:00Z"
        }]
      }]
    }]
  }
};

test("integrates analysis, forecast, explanation, events, provenance, and missing data", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis,
    forecast: { result: { status: "COMPLETED", confidence: "0.8", forecasts: [] } },
    explanation: {
      status: "DEGRADED",
      missingData: ["GEMINI_UPSTREAM_ERROR"],
      citations: [{ id: "citation-1", provider: "FMP", field: "quote.price" }],
      explanation: { evidence: [{ text: "Grounded evidence", citationIds: ["citation-1"] }] }
    },
    relatedEvents: [{ id: "event-1", summary: "Rate decision", source: "FED" }],
    history: [analysis],
    status: { analysis: "READY", forecast: "DEGRADED", explanation: "FAILED" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  for (const text of [
    "AAPL", "분석", "예측", "Gemini 설명", "관련 이벤트",
    "데이터 출처", "누락 데이터", "DEGRADED", "FAILED", "GEMINI_UPSTREAM_ERROR",
    "Rate decision", "Grounded evidence", "snapshot-1"
  ]) {
    assert.match(html, new RegExp(text));
  }
  assert.doesNotMatch(html, /Approve|Cancel|actOnProposal/);
});

test("distinguishes progress and failed analysis states", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "MSFT",
    analysis: null,
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [{ runId: "run-2", symbol: "MSFT", status: "FAILED", errorCode: "UPSTREAM" }],
    status: { analysis: "PROGRESS", forecast: "FAILED", explanation: "FAILED" },
    errors: { analysis: "Still running", forecast: "No forecast", explanation: "No explanation" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /PROGRESS/);
  assert.match(html, /Still running/);
  assert.match(html, /FAILED/);
  assert.match(html, /UPSTREAM/);
});
