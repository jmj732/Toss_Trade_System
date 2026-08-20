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

test("renders missing latest analysis as an explicit empty state", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis: null,
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [],
    status: { analysis: "READY", forecast: "READY", explanation: "READY" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  const analysisPanel = html.slice(html.indexOf("<h2>분석</h2>"), html.indexOf("<h2>예측</h2>"));
  assert.match(analysisPanel, /분석 결과가 아직 없습니다/);
  assert.doesNotMatch(analysisPanel, /불러오는 중|진행 중|오류|STOCK_ANALYSIS_RESULT_NOT_FOUND/);
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
    realtimePrices: {
      status: "AVAILABLE",
      data: [{ symbol: "VERY-LONG-US-EQUITY-SYMBOL", lastPrice: "106", currency: "USD", brokerTimestamp: "2026-08-01T00:00:00Z" }]
    },
    candles: {
      status: "DEGRADED",
      data: {
        candles: [
          { date: "2026-08-02", close: 105, open: 101, high: 106, low: 100 },
          { date: "2026-08-01", close: 99, open: 100, high: 102, low: 98 }
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
  assert.match(html, /USD 106\.00/);
  assert.match(html, /확인 필요/);
  assert.match(html, /기준 시각/);
  const summaryHtml = html.slice(0, html.indexOf("데이터 품질"));
  assert.match(summaryHtml, /2026-08-01/);
  assert.doesNotMatch(summaryHtml, /2026-08-02 09:00 KST/);
  assert.match(html, /부분 데이터/);
  assert.match(html, /리스크/);
  assert.ok(html.indexOf("현재가") < html.indexOf("호가 잔량"));
  const candleHtml = html.slice(html.indexOf("Toss OpenAPI 캔들"), html.indexOf("Toss OpenAPI 호가"));
  assert.match(candleHtml, /class="market-candle-svg"/);
  assert.doesNotMatch(candleHtml, /<table/);
  assert.match(html, /지원되지 않음 \(지원되지 않는 제공자 데이터\)/);
});

test("renders stock decision and position plan from the backend contract shape", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis: {
      ...analysis,
      result: {
        ...analysis.result,
        decision: {
          action: "BUY",
          confidence: "0.72",
          ruleVersion: "decision-rule-v1",
          basis: [],
          missingData: []
        },
        positionPlan: {
          entry: "201.50",
          add: "196.25",
          stop: "188.00",
          target1: "220.00",
          target2: "236.00",
          riskReward: "2.4",
          maxLossPerShare: "13.50",
          invalidation: "Close below 188",
          ruleVersion: "position-plan-v1",
          currency: "USD",
          basisPrice: "202.00",
          missingData: ["positionPlan:FIELD_MISSING:quote.volume"]
        }
      }
    },
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [],
    status: { analysis: "READY", forecast: "IDLE", explanation: "IDLE" },
    position: { currency: "USD", marketValueAmount: "1000", profitLossAmount: "12", weight: "0.08" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /매수/);
  assert.doesNotMatch(html, /<dd>BUY<\/dd>/);
  assert.match(html, /72\.0%/);
  assert.match(html, /decision-rule-v1/);
  assert.match(html, /USD 201\.50/);
  assert.match(html, /USD 196\.25/);
  assert.match(html, /USD 188\.00/);
  assert.match(html, /USD 220\.00/);
  assert.match(html, /USD 236\.00/);
  assert.match(html, /2\.4/);
  assert.match(html, /USD 13\.50/);
  assert.match(html, /Close below 188/);
  assert.match(html, /position-plan-v1/);
  assert.match(html, /기준가/);
  assert.match(html, /positionPlan:FIELD_MISSING:quote\.volume/);
  assert.doesNotMatch(html, /\[object Object\]|entryPrice|stopPrice|targetPrice|maxLoss/);
});

test("hides missing stock decision and position plan instead of inventing defaults", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis,
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [],
    status: { analysis: "READY", forecast: "IDLE", explanation: "IDLE" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  const summaryHtml = html.slice(html.indexOf("종목 분석"), html.indexOf("포지션 계획"));
  assert.doesNotMatch(summaryHtml, /HOLD|LOW|0(?:\.0+)?/);
  assert.match(html, /포지션 계획 없음/);
});

test("labels the backend WAIT stock decision", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis: { ...analysis, result: { ...analysis.result, decision: { action: "WAIT" } } },
    relatedEvents: [],
    history: [],
    status: { analysis: "READY", forecast: "IDLE", explanation: "IDLE" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /<dt>판단<\/dt><dd>대기<\/dd>/);
  assert.doesNotMatch(html, /<dd>WAIT<\/dd>/);
});

test("uses the direct provider quote instead of deriving current price from candles", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis: null,
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [],
    status: { analysis: "READY", forecast: "IDLE", explanation: "IDLE" },
    realtimePrices: {
      status: "AVAILABLE",
      data: [{ symbol: "AAPL", lastPrice: "211.25", currency: "USD", brokerTimestamp: "2026-08-09T00:00:00Z" }]
    },
    candles: {
      status: "DEGRADED",
      unknownFields: ["candles[0].volume"],
      data: { candles: [{ date: "2026-08-02", close: 105, open: 101, volume: null }] }
    },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /USD 211\.25/);
  assert.doesNotMatch(html, /USD 105\.00/);
  assert.match(html, /확인 필요/);
  assert.match(html, /누락 필드: candles\[0\]\.volume/);
});

test("distinguishes provider failure from unsupported data", () => {
  const html = renderToStaticMarkup(createElement(StockAnalysisProductSurface, {
    symbol: "AAPL",
    analysis: null,
    forecast: null,
    explanation: null,
    relatedEvents: [],
    history: [],
    status: { analysis: "READY", forecast: "IDLE", explanation: "IDLE" },
    orderbook: { status: "UNAVAILABLE", unavailableReason: "PROVIDER_TIMEOUT" },
    candles: { status: "UNAVAILABLE", unavailableReason: "PROVIDER_UNSUPPORTED" },
    onCreateAnalysis() {},
    onCreateForecast() {},
    onCreateExplanation() {},
    onSelectSnapshot() {}
  }));

  assert.match(html, /조회 실패 \(제공자 시간 초과\)/);
  assert.match(html, /차트 데이터를 불러오지 못했습니다 \(PROVIDER_UNSUPPORTED\)/);
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
