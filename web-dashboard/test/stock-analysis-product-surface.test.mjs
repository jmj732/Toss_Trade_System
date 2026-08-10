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
    "데이터 출처", "누락 데이터", "부분 저하", "실패", "설명 제공자 오류",
    "Rate decision", "Grounded evidence", "snapshot-1"
  ]) {
    assert.match(html, new RegExp(text));
  }
  assert.doesNotMatch(html, /Approve|Cancel|actOnProposal/);
  // 상태 배지는 한국어로만 노출한다(D-40).
  assert.doesNotMatch(html, /surface-state[^>]*>DEGRADED</);
});

test("uses readable labels for known provider and missing-data codes", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis: {
      result: { status: "DEGRADED", missingData: ["marketRegime:FIELD_MISSING:macro.vix"] }
    },
    forecast: null,
    explanation: { status: "DEGRADED", missingData: ["GEMINI_UPSTREAM_ERROR"] },
    relatedEvents: [],
    history: [],
    status: { analysis: "READY", forecast: "IDLE", explanation: "DEGRADED" },
    orderbook: { status: "UNAVAILABLE", unavailableReason: "PROVIDER_UNSUPPORTED" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /시장 변동성 지표/);
  assert.match(html, /설명 제공자 오류/);
  assert.match(html, /지원되지 않는 제공자 데이터/);
  assert.doesNotMatch(html, /macro\.vix|GEMINI_UPSTREAM_ERROR|PROVIDER_UNSUPPORTED/);
});

test("never leaks raw undefined for missing optional fields", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    // 상단 status/runId/inputSnapshotId 가 없는 이력 항목(partial·stale 재현).
    analysis: null,
    forecast: { result: { confidence: null, forecasts: [] } },
    explanation: null,
    relatedEvents: [{ id: "event-1", summary: "제목만" }],
    history: [{ runId: undefined, inputSnapshotId: undefined, completedAt: undefined }],
    status: { analysis: "DEGRADED", forecast: "DEGRADED", explanation: "IDLE" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.doesNotMatch(html, /undefined/);
  assert.doesNotMatch(html, /NaN|Invalid Date/);
});

test("labels unknown status values instead of pretending they are ready", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis: null,
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [],
    status: { analysis: "QUEUED", forecast: "IDLE", explanation: "IDLE" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /알 수 없음 \(QUEUED\)/);
  assert.doesNotMatch(html, /surface-state ready/);
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

  assert.match(html, /진행 중/);
  assert.match(html, /Still running/);
  assert.match(html, /실패/);
  assert.match(html, /FAILED/);
  assert.match(html, /UPSTREAM/);
});

test("puts stock analysis summary before provider panels", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "VERY-LONG-US-EQUITY-SYMBOL",
    analysis,
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [],
    status: { analysis: "DEGRADED", forecast: "IDLE", explanation: "IDLE" },
    candles: {
      status: "DEGRADED",
      data: {
        candles: [
          { date: "2026-08-01", close: 99, open: 100 },
          { date: "2026-08-02", close: 105, open: 101 }
        ]
      }
    },
    stockWarnings: {
      data: {
        warnings: [{ severity: "CAUTION", message: "단기 변동성 확대" }]
      }
    },
    orderbook: { status: "UNAVAILABLE", unavailableReason: "PROVIDER_UNSUPPORTED" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /현재가/);
  assert.match(html, /USD 105\.00/);
  assert.match(html, /\+6\.06%/);
  assert.match(html, /기준 시각/);
  const summaryHtml = html.slice(0, html.indexOf("데이터 품질"));
  assert.match(summaryHtml, /2026-08-02/);
  assert.doesNotMatch(summaryHtml, /2026-08-02 09:00 KST/);
  assert.match(html, /부분 데이터/);
  assert.match(html, /리스크/);
  assert.ok(html.indexOf("현재가") < html.indexOf("호가 잔량"));
  assert.match(html, /지원되지 않음 \(지원되지 않는 제공자 데이터\)/);
});

test("unauthorized provider envelope takes precedence over unsupported", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis: null,
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [],
    status: { analysis: "IDLE", forecast: "IDLE", explanation: "IDLE" },
    orderbook: { status: "UNAVAILABLE", unavailableReason: "UNAUTHORIZED" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /권한 확인/);
  assert.doesNotMatch(html, /지원되지 않음 \(UNAUTHORIZED\)/);
});
