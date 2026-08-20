// e2e 매트릭스가 잡아낸 초기 렌더·레이아웃 결함의 회귀 가드.
// 단위 테스트가 renderToStaticMarkup 기반이라 "dashboard 가 아직 null 인 첫 렌더" 와
// "weight 는 맞는데 실제로는 펼쳐진 본문" 을 놓쳤다. 그 두 축을 여기서 고정한다.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createElement as h } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { HomeDecisionCenter } from "../app/home-decision-center.js";

const root = new URL("../app/", import.meta.url);

function workspaceSource() {
  return readFile(new URL("route-workspace.js", root), "utf8");
}

// 결함 1: PortfolioPositionTable 을 workspaceReady 삼항 밖에서 dashboard.portfolio 로 만들어
// 첫 렌더(dashboard === null)에 throw 했다. 모든 섹션 접근은 옵셔널 체이닝이어야 한다.
test("route workspace never reads dashboard sections without optional chaining", async () => {
  const source = await workspaceSource();
  const unguarded = source.match(/(^|[^?.\w])dashboard\.[a-zA-Z]/gm) ?? [];
  assert.deepEqual(unguarded, [],
    `dashboard 섹션은 항상 dashboard?. 로 읽어야 한다. 무가드 접근: ${unguarded.join(", ")}`);
});

// 결함 2: loadWorkspace 의 const dashboard 가 try 블록 안에 있어, orders 딥링크 분기가
// 컴포넌트 스코프의 오래된 state(null)를 읽었다. 선언은 try 바깥이어야 한다.
test("loadWorkspace declares dashboard outside the try so later branches see the loaded value", async () => {
  const source = await workspaceSource();
  const declaration = source.indexOf("let dashboard;");
  const tryBlock = source.indexOf("try {", declaration);
  assert.ok(declaration > -1, "loadWorkspace 는 let dashboard 를 try 바깥에 선언해야 한다");
  assert.ok(tryBlock > declaration, "선언이 try 블록보다 앞서야 한다");
  assert.doesNotMatch(source, /const dashboard = await loadDashboard\(/);
  assert.match(source, /dashboard = await loadDashboard\(id\)/);
});

// 결함 2: 딥링크가 방금 로드한 제안 목록에서 주문을 찾아 승인 패널을 연다.
test("order deep link opens the approval panel from the freshly loaded dashboard", async () => {
  const source = await workspaceSource();
  assert.match(source, /get\("order"\)/);
  assert.match(source, /dashboard\?\.pendingOrderProposals\?\.data \?\? \[\]/);
  assert.match(source, /setApprovalOrder\(requested\)/);
});

// 결함 3: BLOCKED 에서 workspace-content 만 비어 빈 페이지가 나왔다. 결정 표면을 신뢰할 수
// 없는 상태에서는 온보딩/랜딩을 그려야 한다(D-36 문구 보존).
test("blocked home falls back to onboarding instead of an empty workspace", async () => {
  const source = await workspaceSource();
  assert.match(source, /const showWorkspace = workspaceReady && surface\.state !== "BLOCKED"/);
  assert.match(source, /!showWorkspace \? h\("main", \{ className: "onboarding-wrap landing-shell" \}/);
  assert.match(source, /showWorkspace \? h\("div", \{ className: "workspace-content" \}/);
  // 데이터 파손은 미연결 온보딩과 다른 안내를 준다.
  assert.match(source, /surface\.dataBroken/);
});

function calmSurface() {
  return {
    state: "CALM",
    label: "확인할 결정 없음",
    actionCount: 0,
    urgentCount: 0,
    riskBreached: false,
    killSwitchEngaged: false,
    dataBroken: false
  };
}

function emptyDashboard() {
  return {
    portfolio: { stale: false, unknown: false, unavailable: false, data: { account: {}, positions: [], buyingPower: {} } },
    analysis: { stale: false, unknown: false, unavailable: false, data: null },
    pendingEvents: { stale: false, unknown: false, unavailable: false, data: [] },
    pendingOrderProposals: { stale: false, unknown: false, unavailable: false, data: [] }
  };
}

// 결함 7: weight 1 은 "한 줄" 이어야 하는데 trend·market 이 본문을 전부 펼친 채 렌더돼
// CALM 홈이 360 에서 4564px 가 됐다. 무거운 맥락 영역은 접힌 details 여야 한다.
test("low-weight context regions stay collapsed instead of expanding their body", () => {
  const markup = renderToStaticMarkup(h(HomeDecisionCenter, {
    surface: calmSurface(),
    actions: [],
    dashboard: emptyDashboard(),
    marketContext: h("div", { className: "market-probe" }, "시장 맥락 본문")
  }));
  for (const region of ["trend", "market"]) {
    const match = markup.match(new RegExp(`<(\\w+)[^>]*data-home-region="${region}"`));
    assert.ok(match, `${region} region 이 렌더돼야 한다`);
    assert.equal(match[1], "details",
      `${region} 은 weight 1 에서도 접힌 details 여야 한다(펼친 카드 금지)`);
  }
  // 접혀 있어도 마크업에는 존재해야 한다 — 기능을 삭제한 것이 아니라 강등한 것이다.
  assert.match(markup, /market-probe/);
  assert.doesNotMatch(markup, /<details[^>]*data-home-region="market"[^>]*open/);
});

// CALM 이라도 결정 영역은 접지 않는다. weight 1 은 compact 이지 숨김이 아니다.
test("calm home keeps the decision queue visible as a compact panel", () => {
  const markup = renderToStaticMarkup(h(HomeDecisionCenter, {
    surface: calmSurface(),
    actions: [],
    dashboard: emptyDashboard()
  }));
  assert.match(markup, /data-home-region="actions"[^>]*data-weight="1"|data-weight="1"[^>]*data-home-region="actions"/);
  assert.match(markup, /확인할 결정이 없습니다/);
  assert.match(markup, /panel--compact/);
});
