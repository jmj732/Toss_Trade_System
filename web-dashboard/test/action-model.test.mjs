import test from "node:test";
import assert from "node:assert/strict";
import {
  ACTION_PRIORITIES,
  ACTION_TYPES,
  buildActions
} from "../lib/action-model.js";

const NOW = Date.parse("2026-08-18T00:00:00Z");

function section(overrides = {}) {
  return { stale: false, unknown: false, unavailable: false, data: null, ...overrides };
}

function dashboard(overrides = {}) {
  return {
    portfolio: section({ data: { positions: [], account: {} } }),
    analysis: section(),
    pendingEvents: section({ data: [] }),
    pendingOrderProposals: section({ data: [] }),
    ...overrides
  };
}

function order(overrides = {}) {
  return {
    id: 1,
    side: "BUY",
    type: "LIMIT",
    symbol: "AAPL",
    quantity: 10,
    status: "PROPOSED",
    createdAt: "2026-08-17T23:00:00Z",
    expiresAt: null,
    ...overrides
  };
}

test("상수를 노출한다", () => {
  assert.deepEqual(ACTION_PRIORITIES, ["URGENT", "HIGH", "MEDIUM", "LOW"]);
  assert.deepEqual(ACTION_TYPES, ["ORDER", "EVENT", "DATA_QUALITY"]);
});

// --- ORDER ---

test("주문 상태별 priority 매핑", () => {
  const cases = [
    ["MANUAL_REVIEW_REQUIRED", "URGENT"],
    ["RECONCILIATION_REQUIRED", "URGENT"],
    ["BLOCKED", "URGENT"],
    ["PROPOSED", "HIGH"]
  ];
  for (const [status, priority] of cases) {
    const actions = buildActions({
      dashboard: dashboard({ pendingOrderProposals: section({ data: [order({ status })] }) }),
      now: NOW
    });
    assert.equal(actions.length, 1, status);
    assert.equal(actions[0].priority, priority, status);
    assert.equal(actions[0].type, "ORDER");
    assert.equal(actions[0].statusCode, status);
  }
});

test("PROPOSED 만료 경계: 정확히 900000ms는 URGENT, 900001ms는 HIGH, 이미 지난 것도 URGENT", () => {
  const boundary = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: section({
        data: [order({ expiresAt: new Date(NOW + 900000).toISOString() })]
      })
    }),
    now: NOW
  });
  assert.equal(boundary[0].priority, "URGENT");

  const justOver = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: section({
        data: [order({ expiresAt: new Date(NOW + 900001).toISOString() })]
      })
    }),
    now: NOW
  });
  assert.equal(justOver[0].priority, "HIGH");

  const expired = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: section({
        data: [order({ expiresAt: new Date(NOW - 60000).toISOString() })]
      })
    }),
    now: NOW
  });
  assert.equal(expired[0].priority, "URGENT");
});

test("Action 대상이 아닌 주문 상태는 제외한다", () => {
  const actions = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: section({
        data: [
          order({ id: 1, status: "COMPLETED" }),
          order({ id: 2, status: "APPROVED" }),
          order({ id: 3, status: "CANCELED" }),
          order({ id: 4, status: "PROPOSED" })
        ]
      })
    }),
    now: NOW
  });
  assert.equal(actions.length, 1);
  assert.equal(actions[0].id, 4);
});

test("주문 title은 side 라벨을 매핑하고 미지 side는 원문을 유지한다", () => {
  const buy = buildActions({
    dashboard: dashboard({ pendingOrderProposals: section({ data: [order({ side: "BUY" })] }) }),
    now: NOW
  });
  assert.equal(buy[0].title, "매수 AAPL");

  const sell = buildActions({
    dashboard: dashboard({ pendingOrderProposals: section({ data: [order({ side: "SELL" })] }) }),
    now: NOW
  });
  assert.equal(sell[0].title, "매도 AAPL");

  const unknown = buildActions({
    dashboard: dashboard({ pendingOrderProposals: section({ data: [order({ side: "SHORT" })] }) }),
    now: NOW
  });
  assert.equal(unknown[0].title, "SHORT AAPL");
});

test("주문 facts는 서버 enum 원문을 담고 statusCode를 별도 필드로 넘긴다", () => {
  const actions = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: section({
        data: [order({ status: "PROPOSED", quantity: 10, type: "LIMIT", expiresAt: "2026-08-18T00:10:00Z" })]
      })
    }),
    now: NOW
  });
  const facts = actions[0].facts;
  assert.deepEqual(facts.find(f => f.label === "상태"), { label: "상태", value: "PROPOSED" });
  assert.equal(actions[0].statusCode, "PROPOSED");
  assert.equal(actions[0].deadline, "2026-08-18T00:10:00Z");
  assert.equal(actions[0].sourceType, "ORDER");
  assert.equal(actions[0].sourceId, "1");
  assert.equal(actions[0].order.id, 1);
  assert.equal(actions[0].event, null);
});

// --- EVENT ---

function event(overrides = {}) {
  return {
    id: 100,
    source: "NEWS",
    type: "EARNINGS",
    summary: "실적 발표",
    affectedSymbols: ["AAPL"],
    occurredAt: "2026-08-17T12:00:00Z",
    comparisonAvailable: false,
    ...overrides
  };
}

test("보유 종목과 교집합이 있으면 HIGH, 없으면 MEDIUM", () => {
  const dash = dashboard({ portfolio: section({ data: { positions: [{ symbol: "AAPL" }], account: {} } }) });

  const held = buildActions({
    dashboard: dash,
    events: [event({ affectedSymbols: ["AAPL"] })],
    now: NOW
  });
  assert.equal(held[0].priority, "HIGH");
  assert.equal(held[0].holdingImpact, true);

  const notHeld = buildActions({
    dashboard: dash,
    events: [event({ affectedSymbols: ["TSLA"] })],
    now: NOW
  });
  assert.equal(notHeld[0].priority, "MEDIUM");
  assert.equal(notHeld[0].holdingImpact, false);
});

test("보유 종목 매칭은 대소문자를 무시한다", () => {
  const actions = buildActions({
    dashboard: dashboard({ portfolio: section({ data: { positions: [{ symbol: "aapl" }], account: {} } }) }),
    events: [event({ affectedSymbols: ["AAPL"] })],
    now: NOW
  });
  assert.equal(actions[0].priority, "HIGH");
  assert.equal(actions[0].symbol, "AAPL");
});

test("reviewStatus가 CONFIRMED/HELD/IGNORED면 제외, PENDING·null은 포함", () => {
  const actions = buildActions({
    dashboard: dashboard(),
    events: [
      event({ id: 1, reviewStatus: "CONFIRMED" }),
      event({ id: 2, reviewStatus: "HELD" }),
      event({ id: 3, reviewStatus: "IGNORED" }),
      event({ id: 4, reviewStatus: "PENDING" }),
      event({ id: 5, reviewStatus: null }),
      event({ id: 6 })
    ],
    now: NOW
  });
  const ids = actions.map(a => a.id).sort();
  assert.deepEqual(ids, [4, 5, 6]);
});

test("events 미주입 시 dashboard.pendingEvents.data를 쓴다", () => {
  const actions = buildActions({
    dashboard: dashboard({ pendingEvents: section({ data: [event({ id: 7 })] }) }),
    now: NOW
  });
  assert.equal(actions.length, 1);
  assert.equal(actions[0].id, 7);
  assert.equal(actions[0].type, "EVENT");
  assert.equal(actions[0].sourceType, "EVENT");
});

test("event title 폴백: summary→type→기본값", () => {
  const dash = dashboard();
  const withType = buildActions({
    dashboard: dash, events: [event({ summary: null })], now: NOW
  });
  assert.equal(withType[0].title, "EARNINGS");

  const fallback = buildActions({
    dashboard: dash, events: [event({ summary: null, type: null })], now: NOW
  });
  assert.equal(fallback[0].title, "이벤트");
});

// --- DATA_QUALITY ---

test("품질 항목 4종을 각각 생성하고 analysis DEGRADED만 MEDIUM", () => {
  const stale = buildActions({
    dashboard: dashboard({ portfolio: section({ stale: true, data: { positions: [], account: {}, staleReason: "SYNC_LAG" } }) }),
    now: NOW
  });
  const staleItem = stale.find(a => a.key === "quality:PORTFOLIO_STALE");
  assert.ok(staleItem);
  assert.equal(staleItem.priority, "LOW");
  assert.equal(staleItem.type, "DATA_QUALITY");
  assert.ok(staleItem.facts.some(f => f.value === "SYNC_LAG"));

  const partial = buildActions({
    dashboard: dashboard({ portfolio: section({ data: { positions: [], account: {}, partial: true } }) }),
    now: NOW
  });
  assert.ok(partial.find(a => a.key === "quality:PORTFOLIO_PARTIAL" && a.priority === "LOW"));

  const cash = buildActions({
    dashboard: dashboard({ portfolio: section({ data: { positions: [], account: { cashBalanceStatus: "UNKNOWN" } } }) }),
    now: NOW
  });
  assert.ok(cash.find(a => a.key === "quality:CASH_UNKNOWN" && a.priority === "LOW"));

  const degradedStatus = buildActions({
    dashboard: dashboard({ analysis: section({ data: { result: { status: "DEGRADED" } } }) }),
    now: NOW
  });
  const degradedItem = degradedStatus.find(a => a.key === "quality:ANALYSIS_DEGRADED");
  assert.ok(degradedItem);
  assert.equal(degradedItem.priority, "MEDIUM");

  const degradedPartial = buildActions({
    dashboard: dashboard({ analysis: section({ data: { result: { quality: { partial: true } } } }) }),
    now: NOW
  });
  assert.ok(degradedPartial.find(a => a.key === "quality:ANALYSIS_DEGRADED" && a.priority === "MEDIUM"));
});

test("품질 항목은 symbol=null, sourceType=PORTFOLIO", () => {
  const actions = buildActions({
    dashboard: dashboard({ portfolio: section({ stale: true, data: { positions: [], account: {} } }) }),
    now: NOW
  });
  const item = actions.find(a => a.type === "DATA_QUALITY");
  assert.equal(item.symbol, null);
  assert.equal(item.sourceType, "PORTFOLIO");
  assert.equal(item.sourceId, null);
});

// --- 정렬·안정성·방어 ---

test("priority·deadline·occurredAt·key 순으로 안정 정렬하고 동일 입력이면 동일 출력", () => {
  const input = {
    dashboard: dashboard({
      portfolio: section({ stale: true, data: { positions: [{ symbol: "AAPL" }], account: {} } }),
      pendingOrderProposals: section({
        data: [
          order({ id: 1, status: "PROPOSED", expiresAt: "2026-08-18T02:00:00Z" }),
          order({ id: 2, status: "MANUAL_REVIEW_REQUIRED" })
        ]
      }),
      pendingEvents: section({
        data: [
          event({ id: 10, affectedSymbols: ["AAPL"], occurredAt: "2026-08-17T10:00:00Z" }),
          event({ id: 11, affectedSymbols: ["TSLA"], occurredAt: "2026-08-17T11:00:00Z" })
        ]
      })
    }),
    now: NOW
  };
  const first = buildActions(input);
  const second = buildActions(input);
  assert.deepEqual(first, second);

  const priorities = first.map(a => a.priority);
  const rank = { URGENT: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };
  for (let i = 1; i < priorities.length; i++) {
    assert.ok(rank[priorities[i - 1]] <= rank[priorities[i]], "priority 오름차순");
  }
  // URGENT 주문이 맨 앞.
  assert.equal(first[0].id, 2);
});

test("같은 우선순위면 deadline 오름차순, null은 뒤", () => {
  const actions = buildActions({
    dashboard: dashboard({
      pendingOrderProposals: section({
        data: [
          order({ id: 1, status: "PROPOSED", expiresAt: null }),
          order({ id: 2, status: "PROPOSED", expiresAt: "2026-08-18T05:00:00Z" }),
          order({ id: 3, status: "PROPOSED", expiresAt: "2026-08-18T03:00:00Z" })
        ]
      })
    }),
    now: NOW
  });
  // 셋 다 HIGH(만료창 밖). deadline 있는 것 오름차순 먼저, null 마지막.
  assert.deepEqual(actions.map(a => a.id), [3, 2, 1]);
});

test("Section이 unavailable이거나 data가 null이면 throw 없이 건너뛴다", () => {
  const actions = buildActions({
    dashboard: dashboard({
      portfolio: section({ unavailable: true, data: null }),
      pendingOrderProposals: section({ unavailable: true, data: null }),
      pendingEvents: section({ data: null }),
      analysis: section({ unavailable: true, data: null })
    }),
    now: NOW
  });
  assert.deepEqual(actions, []);
});

test("빈 입력·undefined에도 throw하지 않는다", () => {
  assert.doesNotThrow(() => buildActions({}));
  assert.doesNotThrow(() => buildActions(undefined));
  assert.deepEqual(buildActions({}), []);
  assert.deepEqual(buildActions(undefined), []);
});
