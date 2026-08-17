import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import * as workspace from "../app/route-workspace.js";

const { describeError, loginHref } = workspace;

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

test("shared workspace exposes explicit route title, nav, and connection regions", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /"data-route-region":\s*"title"/);
  assert.match(source, /"data-route-region":\s*"nav"/);
  assert.match(source, /"data-route-region":\s*"connection"/);
  assert.match(source, /aria-label":\s*"계좌 연결"/);
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

test("a missing latest analysis still loads the read-only stock surfaces", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  // 분석 404는 빈 상태로 확정하되, 시세/호가/경고 GET까지 조기 반환하지 않는다.
  assert.match(source, /if \(value\.status !== 404\) \{[\s\S]*?\n\s*return;\n\s*\}\n\s*analysisResult = \{ result: null \};/);
  assert.match(source, /analysisResult = \{ result: null \};[\s\S]*?loadOrderbook/);
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

test("maps UI section failure codes to user guidance", () => {
  assert.equal(describeError("ANALYSIS_UNAVAILABLE"),
    "분석 정보를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.");
  assert.equal(describeError("ORDER_PROPOSALS_UNAVAILABLE"),
    "주문 검토 정보를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.");
  assert.equal(describeError("PORTFOLIO_HISTORY_NOT_FOUND"),
    "아직 기록된 포트폴리오 이력이 없습니다.");
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
  // 로그인 후 홈도 저장된 계좌를 자동 복구한다.
  assert.match(source, /const saved = readSavedConnectionId\(window\.localStorage\);[\s\S]*?openWorkspace\(saved\);/);
  assert.doesNotMatch(source, /if \(route !== "home"\) \{[\s\S]*?trade\.connectionId/);
});

test("restores only a non-empty saved connection id", () => {
  assert.equal(typeof workspace.readSavedConnectionId, "function");
  const storage = { getItem: key => key === "trade.connectionId" ? "  connection-1  " : null };
  assert.equal(workspace.readSavedConnectionId(storage), "connection-1");
  assert.equal(workspace.readSavedConnectionId({ getItem: () => "   " }), "");
  assert.equal(workspace.readSavedConnectionId({ getItem: () => null }), "");
});

test("keeps the connected account primary while exposing an explicit account switch", () => {
  assert.equal(typeof workspace.AccountSwitcher, "function");
  const html = renderToStaticMarkup(createElement(workspace.AccountSwitcher, {
    accountLabel: "기본계좌",
    accounts: [{ id: "connection-1", brokerType: "TOSS_INVEST", status: "ACTIVE" }],
    connectionId: "connection-1",
    busy: false,
    onSwitch() {}
  }));
  assert.match(html, /기본계좌/);
  assert.match(html, /계좌 변경/);
  assert.match(html, /토스증권/);
  assert.match(html, /계좌 1/);
  // 연결 ID는 소유 연결 식별에만 사용하고 UI에 노출하지 않는다.
  assert.doesNotMatch(html, /connection-1/);
});

test("home uses the operations composition and removes the old chart-first shell", async () => {
  const workspaceSource = await readFile(new URL("route-workspace.js", root), "utf8");
  const dashboardSource = await readFile(new URL("dashboard-view.js", root), "utf8");
  const homeSource = `${workspaceSource}\n${dashboardSource}`;

  assert.match(homeSource, /home-operations-shell/);
  for (const region of [
    "freshness-status", "core-metrics", "portfolio-trend", "holdings",
    "review-queue", "events", "market-context", "review-summary"
  ]) {
    assert.match(homeSource, new RegExp(`data-home-region":\\s*"${region}"`));
  }
  assert.doesNotMatch(homeSource, /home-reference-shell|home-dashboard-columns|home-dashboard-main|home-dashboard-account/);
  assert.match(workspaceSource, /h\(DashboardView, \{[\s\S]*?homeLayout: "operations"[\s\S]*?portfolioHistory[\s\S]*?historyBusy[\s\S]*?riskPolicy[\s\S]*?realtimePrices[\s\S]*?homeCandles[\s\S]*?homeCandleInterval/);
});

test("home loads portfolio history but reuses dashboard.pendingEvents instead of listEvents", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  const loadWorkspace = source.match(/async function loadWorkspace\(id\) \{[\s\S]*?\n  \}/);
  assert.ok(loadWorkspace);
  assert.match(loadWorkspace[0], /route === "home"[\s\S]*?loadPortfolioHistory\(id, HISTORY_QUERY\)/);
  assert.match(source, /const homeHistoryRequest = useRef\(0\)/);
  assert.match(loadWorkspace[0], /const request = homeHistoryRequest\.current \+ 1;\s*homeHistoryRequest\.current = request;/);
  assert.match(loadWorkspace[0], /if \(homeHistoryRequest\.current === request\) setPortfolioHistory/);
  assert.match(loadWorkspace[0], /if \(homeHistoryRequest\.current === request\) setHistoryBusy\(false\)/);
  assert.match(loadWorkspace[0], /setHistoryBusy\(true\)[\s\S]*?\.finally\(\(\) => \{[\s\S]*?setHistoryBusy\(false\);[\s\S]*?\}\)/);
  assert.match(loadWorkspace[0], /setPortfolioHistory\(\{ unavailable: true, unavailableReason: value\.code \?\? value\.message \}\)/);
  assert.match(loadWorkspace[0], /loadHomeCandles\(id, selectHomeSymbol\(dashboard\), homeCandleInterval\)/);
  assert.match(loadWorkspace[0], /const needsEvents = route !== "home";/);
  assert.match(loadWorkspace[0], /if \(needsEvents\) \{\s*try \{\s*setEvents\(await listEvents\(id\)\);/);
  assert.doesNotMatch(loadWorkspace[0], /if \(route === "home"\) \{\s*setEvents\(await listEvents\(id\)\);/);

  const homeDashboard = source.match(/h\(DashboardView, \{[\s\S]*?onHomeCandleIntervalChange: homeCandleIntervalChange[\s\S]*?\}\)/);
  assert.ok(homeDashboard);
  assert.match(homeDashboard[0], /dashboard/);
  assert.match(homeDashboard[0], /portfolioHistory/);
  assert.doesNotMatch(homeDashboard[0], /events:/);
});

test("event action links load the requested event detail", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  assert.match(source, /new URLSearchParams\(window\.location\.search\)\.get\("event"\)/);
  assert.match(source, /requestedEventId[\s\S]*?setSelectedEvent\(await loadEvent\(id, requestedEventId\)\)/);
});

test("portfolio route marks initial history busy until the real request settles", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  const loadWorkspace = source.match(/async function loadWorkspace\(id\) \{[\s\S]*?\n  \}/);
  assert.ok(loadWorkspace);

  const portfolioLoad = loadWorkspace[0].match(/if \(route === "portfolio"\) \{[\s\S]*?\n    \}/);
  assert.ok(portfolioLoad);
  assert.match(portfolioLoad[0], /setHistoryBusy\(true\);[\s\S]*?setPortfolioHistory\(await loadPortfolioHistory\(id, HISTORY_QUERY\)\);/);
  assert.match(portfolioLoad[0], /catch \(value\) \{[\s\S]*?setError\(describeError\(value\.message\)\);[\s\S]*?\} finally \{[\s\S]*?setHistoryBusy\(false\);/);
});

test("home candle loading is guarded by exact request key and canonical interval keys", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  const loader = source.match(/function loadHomeCandles\(id, symbol, interval\) \{[\s\S]*?\n  \}/);

  assert.ok(loader);
  assert.match(source, /selectHomeSymbol\(dashboard\)/);
  assert.match(loader[0], /homeCandleRequestKey\(id, symbol, interval\)/);
  assert.match(loader[0], /needsHomeCandleRequest\(homeCandleRequest\.current, next\)/);
  assert.match(loader[0], /loadCandles\(id, symbol, interval\)/);
  assert.doesNotMatch(loader[0], /AAPL|MSFT|NVDA|GOOGL|AMZN|TSLA/);
  assert.match(source, /CANDLE_INTERVALS\.some\(option => option\.key === interval\)/);
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

test("predictions route separates initial loading from operations refresh", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /const \[predictionLoading, setPredictionLoading\] = useState\(false\)/);
  assert.match(source, /const \[operationsBusy, setOperationsBusy\] = useState\(false\)/);
  assert.match(source, /setPredictionLoading\(true\);\s*setOperationsBusy\(true\);\s*Promise\.allSettled\(\[/);
  assert.match(source, /Promise\.allSettled\(\[[\s\S]*?\.finally\(\(\) => \{\s*setPredictionLoading\(false\);\s*setOperationsBusy\(false\);\s*\}\)/);
  assert.match(source, /h\(AnalysisOutcomeView,[\s\S]*?busy: predictionLoading \|\| outcomeBusy/);
  assert.match(source, /h\(PaperPerformanceView,[\s\S]*?busy: predictionLoading \|\| paperBusy/);
  assert.match(source, /h\(PredictionOperationsView,[\s\S]*?busy: operationsBusy/);
  assert.match(source, /h\(PredictionOperationsView,[\s\S]*?actionBusy: Boolean\(busy\)/);
  const refresh = source.match(/onRefresh: \(\) => \{[\s\S]*?finally\(\(\) => setOperationsBusy\(false\)\);/);
  assert.ok(refresh);
  assert.match(refresh[0], /setOperationsBusy\(true\)/);
  assert.doesNotMatch(refresh[0], /setPredictionLoading/);
});

test("settings route keeps readiness loading distinct and orders readiness risk broker panels", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /const \[readinessBusy, setReadinessBusy\] = useState\(route === "settings"\)/);
  assert.match(source, /setReadinessBusy\(true\);\s*loadOperationalReadiness\(\)[\s\S]*?\.finally\(\(\) => setReadinessBusy\(false\)\)/);

  const refresh = source.match(/function refreshReadiness\(\) \{[\s\S]*?\n  \}/);
  assert.ok(refresh);
  assert.match(refresh[0], /setReadinessBusy\(true\)/);
  assert.match(refresh[0], /\.finally\(\(\) => setReadinessBusy\(false\)\)/);
  assert.match(source, /h\(OperationsReadinessView,[\s\S]*?busy: readinessBusy \|\| busy === "readiness"/);

  const settings = source.match(/route === "settings" \? h\(OperationsReadinessView,[\s\S]*?h\(BrokerOnboarding,/);
  assert.ok(settings);
  assert.ok(settings[0].indexOf("OperationsReadinessView") < settings[0].indexOf("RiskPolicyPanel"));
  assert.ok(settings[0].indexOf("RiskPolicyPanel") < settings[0].indexOf("BrokerOnboarding"));
});
