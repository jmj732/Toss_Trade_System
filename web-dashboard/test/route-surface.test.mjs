import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { describeError, loginHref } from "../app/route-workspace.js";

const root = new URL("../app/", import.meta.url);

test("publishes independent App Router surfaces", async () => {
  for (const route of ["portfolio", "events", "orders", "predictions", "settings"]) {
    const source = await readFile(new URL(`${route}/page.js`, root), "utf8");
    assert.match(source, /RouteWorkspace/);
  }
  const stock = await readFile(new URL("stocks/[symbol]/page.js", root), "utf8");
  assert.match(stock, /RouteWorkspace/);
  assert.match(stock, /symbol/);
});

test("does not assert an empty orders list until the workspace has loaded", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  // 주문/이벤트는 미로딩·로딩중·실패를 구분하는 게이트를 통과한 뒤에만 목록 컴포넌트를 렌더한다.
  assert.match(source, /const workspaceReady = workspaceStatus === "ready"/);
  // orders 라우트: workspaceReady 참 분기 안에서만 OrdersView 를 렌더하고,
  // 거짓 분기는 connectionNotice 로 떨어진다. 2단계 승인 패널이 같은 분기에 함께 산다.
  assert.match(
    source,
    /if \(route === "orders"\)[\s\S]*?workspaceReady[\s\S]*?h\(OrdersView[\s\S]*?connectionNotice\("계좌를 연결하면 대기 중인 주문/);
  // 미로딩 상태에서 "대기 중인 주문이 없습니다"(OrdersView 의 empty 문구)를 직접 단언하지 않는다.
  assert.doesNotMatch(source, /대기 중인 주문이 없습니다/);
});

test("maps known backend error codes to Korean guidance and falls back to raw codes", () => {
  assert.equal(describeError("INTERNAL_ERROR"),
    "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.");
  assert.equal(describeError("SNAPSHOT_NOT_READY"),
    "스냅샷이 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.");
  // 미등록 코드는 원문을 그대로 보존한다.
  assert.equal(describeError("SOME_UNMAPPED_CODE"), "SOME_UNMAPPED_CODE");
  assert.equal(describeError(""), "");
});

// D-38: 심볼을 모를 때 존재하지 않을 수 있는 종목(AAPL)을 지어내지 않는다.
test("loginHref returns an always-valid route for stock without a symbol", () => {
  assert.equal(loginHref("stock", ""), "/auth/login?returnTo=%2F");
  assert.equal(loginHref("stock"), "/auth/login?returnTo=%2F");
  assert.doesNotMatch(loginHref("stock", ""), /AAPL/);
  // 심볼이 있으면 그대로 종목 경로로 이동한다.
  assert.equal(loginHref("stock", "tsla"), "/auth/login?returnTo=%2Fstocks%2FTSLA");
});

// D-36: 홈(/)도 다른 6개 라우트처럼 공유 RouteWorkspace 를 얇게 마운트한다.
test("the home route is served by the shared RouteWorkspace behind a thin page entry", async () => {
  const page = await readFile(new URL("page.js", root), "utf8");
  assert.match(page, /RouteWorkspace/);
  assert.match(page, /route:\s*"home"/);
  // 홈은 자체 상태 기계를 더는 두지 않는다(얇은 진입점).
  assert.doesNotMatch(page, /useState/);

  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  assert.match(source, /route === "home"/);
});

// D-36: 홈이 이전에 없던 안전 게이트를 공유 구현으로 끌어올린다.
test("home inherits the safety gates the standalone page previously lacked", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  // 브로커 연결 삭제는 모든 라우트에서 확인을 받는다.
  assert.match(source, /window\.confirm\("이 브로커 연결과 자격 증명을 삭제할까요\?"\)/);
  // 변경 작업은 단일 실행으로 감싼 mutation 헬퍼를 통과한다.
  assert.match(source, /createSingleFlight\(\)/);
  // 홈은 알림/리스크 정책(이력 포함)을 상단 액션에 싣는다.
  assert.match(source, /NotificationCenter/);
  assert.match(source, /loadRiskPolicyHistory/);
  // 버전 충돌 시 정책을 다시 불러와 다음 저장이 최신 버전을 쓰게 한다.
  assert.match(source, /RISK_POLICY_VERSION_CONFLICT/);
  // 읽지 않음 카운트 실패는 0 이 아니라 null(미확정)로 둔다.
  assert.match(source, /setUnreadCount\(null\)/);
  // 홈은 저장된 연결을 자동 복구하지 않는다.
  assert.match(source, /route !== "home"[\s\S]*?trade\.connectionId/);
});

// D-35: "열기"/"불러오기" 는 워크스페이스 로드 중 비활성으로 피드백을 준다.
test("openWorkspace surfaces a busy state while a workspace load is in flight", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  assert.match(source, /disabled:\s*workspaceStatus === "loading" \|\| Boolean\(busy\)/);
});

// V-49: 발급된 API 키를 상태로 보관해 한 번 노출하고, 닫기로 지운다.
test("an issued prediction API key is surfaced once and never persisted", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  assert.match(source, /setIssuedKey/);
  assert.match(source, /issuedKey/);
  assert.match(source, /onDismissKey:\s*\(\)\s*=>\s*setIssuedKey\(null\)/);
  // 발급 키는 저장소에 쓰지 않는다.
  assert.doesNotMatch(source, /localStorage[^\n]*[iI]ssuedKey/);
});

// V-48: PaperPerformanceView 를 예측 라우트에 배선한다.
test("PaperPerformanceView is reachable on the predictions route", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  assert.match(source, /import \{ PaperPerformanceView \}/);
  assert.match(source, /h\(PaperPerformanceView/);
  assert.match(source, /loadPaperPerformance\(id/);
});
