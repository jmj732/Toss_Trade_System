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

test("promotes the comparison to the top of the detail and demotes the manual form to a bottom <details>", () => {
  const selectedEvent = {
    id: "event-1",
    summary: "Rate decision",
    reviewStatus: "HELD",
    reviewVersion: 2,
    affectedSymbols: ["NVDA"],
    // BC-5 판단 변화 필드가 있어도 렌더하지 않는다(계약 미구현).
    portfolioImpact: "SHOULD_NOT_RENDER",
    thesisChange: "SHOULD_NOT_RENDER",
    decision: "SHOULD_NOT_RENDER",
    action: "SHOULD_NOT_RENDER",
    analysisComparison: {
      comparison: {
        baselineAvailable: true,
        positions: [{
          symbol: "NVDA", currency: "USD",
          beforeMarketValue: 100, afterMarketValue: 120, marketValueChange: 20,
          beforeProfitLoss: 10, afterProfitLoss: 20, profitLossChange: 10,
          beforeWeight: 0.5, afterWeight: 0.6, weightChange: 0.1
        }],
        currencyTotals: []
      }
    }
  };
  const html = renderToStaticMarkup(createElement(EventWorkflow, {
    positions: [{ symbol: "NVDA" }],
    events: [{ id: "event-1", summary: "Rate decision", type: "MACRO", affectedSymbols: ["NVDA"], reviewStatus: "HELD", reviewVersion: 2 }],
    selectedEvent,
    connectionId: "connection-1",
    busyAction: null,
    onCreate() {}, onSelect() {}, onReanalyze() {}, onReview() {}
  }));

  // 비교표(최대 자산)가 Detail 본문 최상단으로 승격됐다: 검토 액션보다 먼저 나온다.
  const impact = html.indexOf("영향받은 포지션");
  const actions = html.indexOf("event-review-actions");
  const manual = html.indexOf("manual-event-secondary");
  assert.ok(impact > -1);
  assert.ok(impact < actions, "비교표가 검토 액션보다 위");
  // 수동 등록 폼은 최하단 <details> 로 강등됐다.
  assert.match(html, /<details class="manual-event-secondary"/);
  assert.ok(actions < manual, "등록 폼이 검토 액션보다 아래");
  assert.match(html, /포트폴리오 영향 \(재분석 기준\)/);

  // BC-5(이전→신규 Decision)는 계약이 없어 만들지 않는다.
  assert.doesNotMatch(html, /SHOULD_NOT_RENDER/);
  assert.doesNotMatch(html, /Thesis 변화|새 판단|event-impact-facts/);
});

test("feed filters only expose server-field-backed categories and mark held events", () => {
  const html = renderToStaticMarkup(createElement(EventWorkflow, {
    positions: [{ symbol: "NVDA" }],
    events: [
      { id: "e1", summary: "SEC filing", type: "SEC_10-K", source: "SEC", affectedSymbols: ["NVDA"] },
      { id: "e2", summary: "CPI", type: "FRED_OBSERVATION", source: "FRED", affectedSymbols: [], macroScope: [{ provider: "FRED" }] }
    ],
    selectedEvent: null,
    connectionId: "connection-1",
    busyAction: null,
    onCreate() {}, onSelect() {}, onReanalyze() {}, onReview() {}
  }));

  // 서버 필드(source·macroScope)와 보유 교집합에 대응하는 필터만 노출한다.
  for (const label of ["전체", "보유종목", "공시", "거시"]) {
    assert.match(html, new RegExp(`<option value="[^"]*"[^>]*>${label}</option>`));
  }
  // "실적"·"뉴스"는 대응 서버 필드가 없어 필터를 만들지 않는다.
  assert.doesNotMatch(html, /<option[^>]*>실적<\/option>/);
  assert.doesNotMatch(html, /<option[^>]*>뉴스<\/option>/);
  // 보유 종목과 교집합이 있는 이벤트에만 "보유" 표식이 붙는다.
  assert.match(html, /data-event-held="true"/);
  // 이벤트 종류 표식은 event.source 값을 그대로 노출한다(SEC/FRED는 자동 수집 공급자 enum).
  assert.match(html, /badge-pill badge-pill--ok" data-event-source="SEC">SEC</);
  assert.match(html, /badge-pill badge-pill--ok" data-event-source="FRED">FRED</);
});

test("marks manually-registered events with a distinct source tone from provider-fed events", () => {
  const html = renderToStaticMarkup(createElement(EventWorkflow, {
    positions: [{ symbol: "NVDA" }],
    events: [
      { id: "e1", summary: "Manual note", type: "NOTE", source: "MANUAL", affectedSymbols: ["NVDA"] }
    ],
    selectedEvent: null,
    connectionId: "connection-1",
    busyAction: null,
    onCreate() {}, onSelect() {}, onReanalyze() {}, onReview() {}
  }));

  assert.match(html, /badge-pill badge-pill--neutral" data-event-source="MANUAL">MANUAL</);
  // review-status 배지(warn)와는 다른 축이라 neutral/ok 톤을 재사용하고 warn 은 겹치지 않는다.
  assert.doesNotMatch(html, /badge-pill--warn" data-event-source/);
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
