import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { AnalysisOutcomeView } from "../app/analysis-outcome-view.js";

const QUERY = { from: "", to: "", modelVersion: "", contractVersion: "", symbol: "" };

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
      ],
      forecastQuality: {
        minimumSampleCount: 10,
        rows: [{
          symbol: "AAPL", modelVersion: "v1", contractVersion: "1", horizon: "D1",
          status: "DATA_SHORTAGE", sampleCount: 1, minimumSampleCount: 10,
          pendingCount: 0, hitRate: null, calibrationError: null, brierScore: null,
          drift: { status: "DATA_SHORTAGE", degraded: false }
        }, {
          symbol: "AAPL", modelVersion: "v1", contractVersion: "1", horizon: "D5",
          status: "SUFFICIENT", sampleCount: 10, minimumSampleCount: 10,
          pendingCount: 0, hitRate: 0.4, meanError: -0.1, meanAbsoluteError: 0.2,
          drift: { status: "DRIFT", degraded: true, hitRateDelta: -0.2 }
        }]
      }
    },
    query: QUERY,
    busy: false,
    createBusy: false,
    createError: "",
    onQuery() {},
    onCreate() {}
  }));

  assert.match(html, /예측 성과/);
  assert.match(html, /주문이나 자동매매와 연동되지 않습니다/);
  assert.match(html, /AAPL/);
  assert.match(html, /10\.0%\s*HIT/);
  assert.match(html, /예측 품질 모니터링/);
  assert.match(html, /DATA_SHORTAGE/);
  assert.match(html, /DRIFT \/ hit -20\.0% \/ DEGRADED/);
});

test("treats an ungraded outcome as pending rather than a MISS", () => {
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
            D1: { actualReturn: 0.02, directionCorrect: null }
          }
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

  assert.match(html, /채점 대기/);
  assert.doesNotMatch(html, /MISS/);
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

  assert.match(html, /채점된 결과가 아직 없습니다/);
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

  assert.match(html, /기록된 예측이 아직 없습니다/);
  assert.match(html, /채점된 결과가 아직 없습니다/);
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
  assert.match(html, /예측 기록/);
});

test("renders the model registry and offers only ACTIVE versions for prediction creation", () => {
  const html = renderToStaticMarkup(createElement(AnalysisOutcomeView, {
    performance: { predictions: [], byVersion: [] },
    versions: [
      {
        id: "active-1", modelVersion: "model-v1", contractVersion: "contract-v1",
        status: "ACTIVE", createdAt: "2026-01-01T00:00:00Z", deprecatedAt: null
      },
      {
        id: "deprecated-1", modelVersion: "old", contractVersion: "v1",
        status: "DEPRECATED", createdAt: "2025-01-01T00:00:00Z",
        deprecatedAt: "2026-01-01T00:00:00Z"
      }
    ],
    query: QUERY,
    busy: false,
    createBusy: false,
    createError: "",
    registryBusy: false,
    registryError: "",
    onQuery() {},
    onCreate() {},
    onRegister() {},
    onDeprecate() {},
    onDelete() {}
  }));

  assert.match(html, /예측 모델 레지스트리/);
  assert.match(html, /value="active-1">model-v1 \/ contract-v1/);
  assert.doesNotMatch(html, /value="deprecated-1">old \/ v1/);
  assert.match(html, /DEPRECATED/);
  assert.match(html, /지원 중단/);
  assert.match(html, /삭제/);
});

test("disables prediction creation when no ACTIVE version exists", () => {
  const html = renderToStaticMarkup(createElement(AnalysisOutcomeView, {
    performance: { predictions: [], byVersion: [] },
    versions: [],
    query: QUERY,
    busy: false,
    createBusy: false,
    createError: "",
    registryBusy: false,
    registryError: "",
    onQuery() {},
    onCreate() {},
    onRegister() {},
    onDeprecate() {},
    onDelete() {}
  }));

  assert.match(html, /먼저 ACTIVE 모델 버전을 등록하세요/);
  assert.match(html, /disabled="">예측 기록/);
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
