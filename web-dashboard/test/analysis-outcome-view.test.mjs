import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { AnalysisOutcomeView } from "../app/analysis-outcome-view.js";

const QUERY = { from: "", to: "", modelVersion: "", contractVersion: "" };

test("renders predictions table, per-horizon grades, and version performance summary", () => {
  const html = renderToStaticMarkup(createElement(AnalysisOutcomeView, {
    performance: {
      predictions: [
        {
          id: "pred-1",
          predictedAt: "2026-01-01T00:00:00Z",
          symbol: "AAPL",
          currency: "USD",
          predictedDirection: "UP",
          modelVersion: "v1",
          contractVersion: "1",
          baselinePrice: 100,
          outcomes: {
            D1: { price: 110, actualReturn: 0.1, directionCorrect: true, observedAt: "2026-01-02T00:00:00Z" }
          }
        }
      ],
      byVersion: [
        {
          modelVersion: "v1", contractVersion: "1", horizon: "D1",
          sampleCount: 1, hitRate: 1, avgDirectionalReturn: 0.1, avgMaxAdverseExcursion: 0
        }
      ]
    },
    query: QUERY,
    busy: false,
    createBusy: false,
    createError: "",
    onQuery() {},
    onCreate() {}
  }));

  assert.match(html, /Prediction performance/);
  assert.match(html, /주문이나 자동매매와 연동되지 않습니다/);
  assert.match(html, /AAPL/);
  assert.match(html, /10\.0%\s*HIT/);
});

test("shows an em dash for horizons that have not matured yet and no performance summary until one has", () => {
  const html = renderToStaticMarkup(createElement(AnalysisOutcomeView, {
    performance: {
      predictions: [
        {
          id: "pred-1",
          predictedAt: "2026-01-01T00:00:00Z",
          symbol: "AAPL",
          currency: "USD",
          predictedDirection: "UP",
          modelVersion: "v1",
          contractVersion: "1",
          baselinePrice: 100,
          outcomes: {}
        }
      ],
      byVersion: []
    },
    query: QUERY,
    busy: false,
    createBusy: false,
    createError: "",
    onQuery() {},
    onCreate() {}
  }));

  assert.match(html, /No graded outcomes yet/);
  assert.match(html, /AAPL/);
});

test("shows an empty state before any connection has ever been opened", () => {
  const html = renderToStaticMarkup(createElement(AnalysisOutcomeView, {
    performance: null,
    query: QUERY,
    busy: false,
    createBusy: false,
    createError: "",
    onQuery() {},
    onCreate() {}
  }));

  assert.match(html, /No predictions recorded yet/);
  assert.match(html, /No graded outcomes yet/);
});

test("surfaces a create error without blanking the rest of the panel", () => {
  const html = renderToStaticMarkup(createElement(AnalysisOutcomeView, {
    performance: { predictions: [], byVersion: [] },
    query: QUERY,
    busy: false,
    createBusy: false,
    createError: "ANALYSIS_PREDICTION_QUOTE_UNAVAILABLE",
    onQuery() {},
    onCreate() {}
  }));

  assert.match(html, /ANALYSIS_PREDICTION_QUOTE_UNAVAILABLE/);
  assert.match(html, /Record prediction/);
});

test("disables record and apply while a mutation is in flight", () => {
  const html = renderToStaticMarkup(createElement(AnalysisOutcomeView, {
    performance: { predictions: [], byVersion: [] },
    query: QUERY,
    busy: true,
    createBusy: true,
    createError: "",
    onQuery() {},
    onCreate() {}
  }));

  assert.equal((html.match(/disabled=""/g) ?? []).length, 2);
});
