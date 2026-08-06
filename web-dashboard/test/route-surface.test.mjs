import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { describeError } from "../app/route-workspace.js";

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
