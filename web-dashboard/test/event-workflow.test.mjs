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
    "수동 이벤트", "출처 이벤트 ID", "Rate decision", "HELD · v2",
    "재분석", "확인", "보류", "무시", "이전", "이후", "변화",
    "NVDA", "AAPL", "통화별 합계", "평가금액", "손익",
    "비중", "집중도"
  ]) {
    assert.match(html, new RegExp(text));
  }
  assert.equal((html.match(/type="checkbox"/g) ?? []).length, 2);
  assert.doesNotMatch(html, /News collection|LLM|Automatic order/);
});

test("leads event review with status, symbols, time, and next action", () => {
  const html = renderToStaticMarkup(createElement(EventWorkflow, {
    positions: [{ symbol: "NVDA" }],
    events: [{
      id: "event-1",
      summary: "Rate decision",
      type: "MACRO",
      source: "FED",
      affectedSymbols: ["NVDA", "AAPL"],
      reviewStatus: "MANUAL_REVIEW",
      reviewVersion: 3,
      comparisonAvailable: false,
      occurredAt: "2026-07-28T00:00:00.000Z"
    }],
    selectedEvent: {
      id: "event-1",
      summary: "Rate decision",
      type: "MACRO",
      source: "FED",
      affectedSymbols: ["NVDA", "AAPL"],
      reviewStatus: "MANUAL_REVIEW",
      reviewVersion: 3,
      comparisonAvailable: false,
      occurredAt: "2026-07-28T00:00:00.000Z"
    },
    connectionId: "connection-1",
    busyAction: null,
    onCreate() {},
    onSelect() {},
    onReanalyze() {},
    onReview() {}
  }));

  assert.match(html, /MANUAL_REVIEW · v3/);
  assert.match(html, /영향 NVDA, AAPL/);
  assert.match(html, /2026.*07.*28/);
  assert.match(html, /다음 작업: 검토 결정/);
  assert.match(html, /비교 대기/);
  assert.match(html, /<button[^>]*class="event-select"[^>]*>[\s\S]*<span class="event-signal event-signal--compact"/);
  assert.doesNotMatch(html, /지원하지 않음/);
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
  assert.match(html, /등록 중…/);
  assert.match(html, /title="event-create…"/);
  assert.match(html, /이벤트를 선택하면 상태와 영향 범위를 확인합니다/);
  assert.doesNotMatch(html, /UNKNOWN · v확인 필요/);
});

test("scopes affected-symbol state to the current connection", () => {
  const source = readFileSync(
    new URL("../app/route-workspace.js", import.meta.url),
    "utf8");

  assert.match(
    source,
    /h\(EventWorkflow, \{\s*key: connectionId\.trim\(\),/);
});

test("clears the create form only after onCreate resolves", () => {
  const source = readFileSync(
    new URL("../app/event-workflow.js", import.meta.url),
    "utf8");

  // 실패 시 입력값이 보존되도록 reset 은 반드시 onCreate 성공 콜백 안에서만 호출한다.
  assert.match(source, /Promise\.resolve\(onCreate\(/);
  assert.match(source, /\}\)\)\.then\(\(\) => \{\s*form\.reset\(\);/);
});
