import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import * as workspace from "../app/route-workspace.js";

const { describeError, loginHref } = workspace;

const root = new URL("../app/", import.meta.url);

test("publishes independent App Router surfaces", async () => {
  for (const route of ["portfolio", "events", "orders", "settings"]) {
    const source = await readFile(new URL(`${route}/page.js`, root), "utf8");
    assert.match(source, /RouteWorkspace/);
  }
  // 예측 경로는 Settings 로 리다이렉트한다(북마크 보존). RouteWorkspace 를 마운트하지 않는다.
  const predictions = await readFile(new URL("predictions/page.js", root), "utf8");
  assert.match(predictions, /redirect\("\/settings"\)/);
  assert.doesNotMatch(predictions, /RouteWorkspace/);
  const stock = await readFile(new URL("stocks/[symbol]/page.js", root), "utf8");
  assert.match(stock, /RouteWorkspace/);
  assert.match(stock, /symbol/);
});

// 예측 기능(품질·모델 레지스트리·모의 성과·운영/API Key)의 도달 경로는 Settings 한 곳뿐이다(중복 마운트 금지).
test("prediction features live only in Settings after the /predictions redirect", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  // 예측 라우트 분기는 제거됐다.
  assert.doesNotMatch(source, /if \(route === "predictions"\)/);
  // 세 예측 뷰는 Settings 섹션 슬롯에서만 마운트된다.
  assert.match(source, /analysis: h\("div"[\s\S]*?h\(AnalysisOutcomeView, \{/);
  assert.match(source, /predictionOperationsView\(\)\),/);
  assert.match(source, /strategy: h\(PaperPerformanceView, \{/);
  // 각 뷰는 한 번씩만 마운트된다(중복 마운트 없음).
  assert.equal((source.match(/h\(AnalysisOutcomeView, \{/g) ?? []).length, 1);
  assert.equal((source.match(/h\(PaperPerformanceView, \{/g) ?? []).length, 1);
});

test("shared workspace exposes explicit route title and nav, with a single shell account switcher", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /"data-route-region":\s*"title"/);
  assert.match(source, /"data-route-region":\s*"nav"/);
  // Shell 단일화: 라우트별 계좌 연결 섹션을 제거하고 topbar 의 AccountSwitcher 하나만 남긴다.
  assert.doesNotMatch(source, /"data-route-region":\s*"connection"/);
  assert.doesNotMatch(source, /aria-label":\s*"계좌 연결"/);
  // AccountSwitcher 는 공통 topbar 헬퍼 한 곳에서만 마운트한다.
  assert.equal((source.match(/h\(AccountSwitcher,/g) ?? []).length, 1);
  assert.match(source, /function workspaceTopbar\(/);
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

test("home mounts the adaptive HomeDecisionCenter and drops the old operations shell", async () => {
  const workspaceSource = await readFile(new URL("route-workspace.js", root), "utf8");
  const dashboardSource = await readFile(new URL("dashboard-view.js", root), "utf8");
  const homeSource = `${workspaceSource}\n${dashboardSource}`;

  // 구 operations shell 과 그 region 들은 사라진다.
  assert.doesNotMatch(homeSource, /home-operations-shell|home-core-metrics/);
  for (const region of ["freshness-status", "core-metrics", "review-queue", "review-summary"]) {
    assert.doesNotMatch(homeSource, new RegExp(`data-home-region":\\s*"${region}"`));
  }
  // 홈은 surface 상태머신 + Action 목록을 계산해 HomeDecisionCenter 로 넘긴다(순서: buildActions → resolveSurfaceState).
  assert.match(workspaceSource, /const actions = buildActions\(\{ dashboard, now \}\);/);
  assert.match(workspaceSource, /const surface = resolveSurfaceState\(\{ connectionId[\s\S]*?actions, now \}\);/);
  assert.match(workspaceSource, /h\(HomeDecisionCenter, \{[\s\S]*?surface[\s\S]*?actions[\s\S]*?dashboard[\s\S]*?portfolioHistory[\s\S]*?marketContext[\s\S]*?\}\)/);
  // marketContext 는 기존 캔들 + 시장 개요 + 실시간 시세를 묶어 주입한다.
  assert.match(workspaceSource, /const marketContext = h\("div"[\s\S]*?MarketCandleChart[\s\S]*?marketOverview[\s\S]*?RealtimePriceTicker/);
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

  assert.match(source, /onIntervalChange: homeCandleIntervalChange/);
  const homeCenter = source.match(/h\(HomeDecisionCenter, \{[\s\S]*?\}\)\) : null/);
  assert.ok(homeCenter);
  assert.match(homeCenter[0], /dashboard/);
  assert.match(homeCenter[0], /portfolioHistory/);
  assert.doesNotMatch(homeCenter[0], /events:/);
});

// BC-6/BC-7: 프론트가 세 계약을 소비하도록 배선됐는지 고정한다.
test("workspace loads the USER kill switch and injects it into the surface state", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /import \{[\s\S]*?loadKillSwitch[\s\S]*?\} from "\.\.\/lib\/api\.js"/);
  // USER scope 만 조회한다(GLOBAL 을 프론트에서 합성하지 않는다).
  assert.match(source, /loadKillSwitch\("USER"\)\.then\(setKillSwitch\)/);
  // 조회 실패는 null(미확정)로 둔다 — 조용히 {engaged:false} 로 접지 않는다.
  assert.match(source, /catch\(\(\) => setKillSwitch\(null\)\)/);
  assert.doesNotMatch(source, /setKillSwitch\(\{ engaged: false/);
  // killSwitch 를 surface 상태머신에 주입한다.
  assert.match(source, /resolveSurfaceState\(\{ connectionId[\s\S]*?killSwitch[\s\S]*?\}\)/);
  // engaged===true 차단 배너를 Shell 에 마운트한다.
  assert.match(source, /h\(KillSwitchBanner, \{ killSwitch \}\)/);
  // Settings 는 상태 표시로 미설정/정지/해제를 구분해 노출한다.
  assert.match(source, /h\(KillSwitchStatus, \{ killSwitch \}\)/);
});

test("orders route wires the BC-6 preview handler into the order draft", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /import \{[\s\S]*?previewPaperOrder[\s\S]*?\} from "\.\.\/lib\/api\.js"/);
  assert.match(source, /function previewOrder\(command\) \{\s*return previewPaperOrder\(command\);/);
  assert.match(source, /h\(OrderCreationPanel, \{[\s\S]*?onPreview: previewOrder/);
  // kill switch engaged 면 주문 작성이 차단된다.
  assert.match(source, /tradingHalted: killSwitch\?\.engaged === true/);
});

// P2: Orders 는 하나의 화면이되 Paper/Live 실행 컨텍스트가 명확히 분리된다.
test("orders route mounts the Paper/Live execution-context tabs bound to orderContext", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /import \{ OrdersView, OrderContextTabs \} from "\.\/orders-view\.js"/);
  assert.match(source, /const \[orderContext, setOrderContext\] = useState\("PAPER"\)/);
  assert.match(source, /h\(OrderContextTabs, \{[\s\S]*?context: orderContext[\s\S]*?onSelect: setOrderContext/);
});

test("order creation is Paper-only; Live shows a create-not-supported notice", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /const isLiveContext = orderContext === "LIVE"/);
  // Live 컨텍스트는 작성 폼 대신 안내를 노출한다(작성 미지원).
  assert.match(source, /isLiveContext\s*\?[\s\S]*?실거래 주문 생성은 아직 지원하지 않습니다/);
  // 승인·전송·정정·취소는 연동됐고 생성만 미지원임을 문구가 정확히 반영한다.
  assert.match(source, /기존 실거래 주문의 승인·전송·정정·취소만 가능합니다/);
  // 반대 분기에서만 작성 폼을 마운트한다.
  assert.match(source, /:\s*h\(OrderCreationPanel, \{/);
});

test("orders route passes context and the kill-switch halt flag into OrdersView", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /const tradingHalted = killSwitch\?\.engaged === true/);
  assert.match(source, /h\(OrdersView, \{[\s\S]*?context: orderContext[\s\S]*?tradingHalted,/);
  // 승인 패널도 거래중지 게이트를 받는다.
  assert.match(source, /h\(OrderApprovalPanel, \{[\s\S]*?tradingHalted,/);
});

test("buying power is merged into the creation form, not the orders queue (§11.6)", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  // buyingPower 는 이제 작성 폼(OrderCreationPanel)으로 전달된다.
  assert.match(source, /h\(OrderCreationPanel, \{[\s\S]*?buyingPower\s*\}/);
  // OrdersView 로는 더 이상 buyingPower 를 넘기지 않는다.
  const ordersViewCall = source.match(/h\(OrdersView, \{[\s\S]*?\}\)/);
  assert.ok(ordersViewCall);
  assert.doesNotMatch(ordersViewCall[0], /buyingPower/);
});

test("deep-link ?order= selects the tab matching the order's executionMode", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(
    source,
    /if \(requested\.executionMode === "LIVE" \|\| requested\.executionMode === "PAPER"\) \{\s*setOrderContext\(requested\.executionMode\);/);
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

// V-48: PaperPerformanceView 는 이제 Settings 의 전략 섹션에서 도달한다.
test("PaperPerformanceView is reachable in Settings", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");
  assert.match(source, /import \{ PaperPerformanceView \}/);
  assert.match(source, /strategy: h\(PaperPerformanceView/);
  assert.match(source, /loadPaperPerformance\(id/);
});

// 예측/운영 로딩은 진입 즉시가 아니라 Settings 섹션을 펼칠 때 지연 로드된다.
test("settings analysis section keeps initial loading distinct from operations refresh", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /const \[predictionLoading, setPredictionLoading\] = useState\(false\)/);
  assert.match(source, /const \[operationsBusy, setOperationsBusy\] = useState\(false\)/);
  // 진입(loadWorkspace)에는 예측/운영/settings 조회 블록이 없다(지연 로딩).
  const loadWorkspace = source.match(/async function loadWorkspace\(id\) \{[\s\S]*?\n  \}/);
  assert.ok(loadWorkspace);
  assert.doesNotMatch(loadWorkspace[0], /route === "predictions"/);
  assert.doesNotMatch(loadWorkspace[0], /route === "settings"/);
  // 분석·모델 섹션 지연 로더: 개별 정산 + 두 busy 를 함께 켜고 함께 끈다.
  assert.match(source, /if \(key === "analysis"\) \{[\s\S]*?setPredictionLoading\(true\);\s*setOperationsBusy\(true\);/);
  assert.match(source, /Promise\.allSettled\(\[\s*loadAnalysisPredictions\(id, OUTCOME_QUERY\),\s*loadPredictionIngestionApiKeys\(\),\s*loadPredictionOperations\(\)/);
  assert.match(source, /\.finally\(\(\) => \{\s*setPredictionLoading\(false\);\s*setOperationsBusy\(false\);\s*\}\)/);
  assert.match(source, /h\(AnalysisOutcomeView,[\s\S]*?busy: predictionLoading \|\| outcomeBusy/);
  assert.match(source, /h\(PaperPerformanceView,[\s\S]*?busy: predictionLoading \|\| paperBusy/);
  assert.match(source, /h\(PredictionOperationsView,[\s\S]*?busy: operationsBusy/);
  assert.match(source, /h\(PredictionOperationsView,[\s\S]*?actionBusy: Boolean\(busy\)/);
  // operations refresh 는 operationsBusy 만 건드리고 predictionLoading 은 건드리지 않는다.
  const refresh = source.match(/onRefresh: \(\) => \{[\s\S]*?finally\(\(\) => setOperationsBusy\(false\)\);/);
  assert.ok(refresh);
  assert.match(refresh[0], /setOperationsBusy\(true\)/);
  assert.doesNotMatch(refresh[0], /setPredictionLoading/);
});

test("settings mounts five lazy <details> sections in account/risk/data/analysis/strategy order", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  // 지연 로딩이라 진입 시 readiness 를 자동 조회하지 않는다(초기 busy=false).
  assert.match(source, /const \[readinessBusy, setReadinessBusy\] = useState\(false\)/);
  // 섹션 로더는 키별로 한 번만 로드한다(이미 로드한 섹션은 재요청 금지).
  assert.match(source, /function loadSettingsSection\(key\) \{[\s\S]*?if \(settingsLoaded\.current\.has\(key\)\) \{[\s\S]*?return;/);
  assert.match(source, /settingsLoaded\.current\.add\(key\)/);
  // 데이터 섹션은 readiness 만, 전략 섹션은 paper 성과만 로드한다.
  assert.match(source, /if \(key === "data"\) \{[\s\S]*?loadOperationalReadiness\(\)\.then\(setReadiness\)/);
  assert.match(source, /if \(key === "strategy"\) \{[\s\S]*?loadPaperPerformance\(id, PAPER_QUERY\)\.then\(setPaperPerformance\)/);

  // SettingsSections 로 5개 섹션을 마운트하고 펼침 로더를 배선한다.
  assert.match(source, /h\(SettingsSections, \{\s*onExpand: loadSettingsSection/);
  // 섹션 순서: 계좌 → 위험 → 데이터 → 분석·모델 → 전략.
  const at = key => source.indexOf(key);
  assert.ok(at("account: h(BrokerOnboarding") > -1);
  assert.ok(at("account: h(BrokerOnboarding") < at('risk: h("div"'));
  assert.ok(at('risk: h("div"') < at("data: h(OperationsReadinessView"));
  assert.ok(at("data: h(OperationsReadinessView") < at('analysis: h("div"'));
  assert.ok(at('analysis: h("div"') < at("strategy: h(PaperPerformanceView"));
  // 위험 섹션은 RiskPolicyPanel + KillSwitchStatus 를 함께 싣는다.
  assert.match(source, /risk: h\("div"[\s\S]*?RiskPolicyPanel[\s\S]*?KillSwitchStatus/);

  const refresh = source.match(/function refreshReadiness\(\) \{[\s\S]*?\n  \}/);
  assert.ok(refresh);
  assert.match(refresh[0], /setReadinessBusy\(true\)/);
  assert.match(refresh[0], /\.finally\(\(\) => setReadinessBusy\(false\)\)/);
  assert.match(source, /h\(OperationsReadinessView,[\s\S]*?busy: readinessBusy \|\| busy === "readiness"/);
});

// ---------------------------------------------------------------------------
// Live 실행: 승인·전송·취소가 live 계약으로 배선되고, 승인과 전송이 분리돼 있음을 고정한다.
// ---------------------------------------------------------------------------

// step-up 토큰 캐시 재사용/재발급 규칙(만료 토큰으로 조용히 재시도하지 않는다).
test("liveStepUpUsable reuses a fresh cached token but forces reissue on expiry or mismatch", () => {
  const now = 1_000_000;
  const fresh = { orderId: "o1", token: "tok", expiresAt: new Date(now + 60_000).toISOString() };
  // 같은 주문 + 미만료 → 재사용.
  assert.equal(workspace.liveStepUpUsable(fresh, "o1", now), true);
  // 만료 → 재발급(false).
  assert.equal(
    workspace.liveStepUpUsable({ ...fresh, expiresAt: new Date(now - 1).toISOString() }, "o1", now),
    false);
  // 다른 주문 → 재발급.
  assert.equal(workspace.liveStepUpUsable(fresh, "o2", now), false);
  // 토큰 없음 / 레코드 없음 → 재발급.
  assert.equal(workspace.liveStepUpUsable({ ...fresh, token: "" }, "o1", now), false);
  assert.equal(workspace.liveStepUpUsable(null, "o1", now), false);
  // expiresAt 없음 → 사용 가능(만료 개념 없음).
  assert.equal(workspace.liveStepUpUsable({ orderId: "o1", token: "tok", expiresAt: null }, "o1", now), true);
  // expiresAt 파싱 불가 → 안전하게 재발급.
  assert.equal(workspace.liveStepUpUsable({ orderId: "o1", token: "tok", expiresAt: "nonsense" }, "o1", now), false);
});

test("live approve, dispatch, and cancel are wired to the live contract helpers", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  assert.match(source, /import \{[\s\S]*?approveLiveOrder[\s\S]*?dispatchLiveOrder[\s\S]*?cancelLiveOrder[\s\S]*?\} from "\.\.\/lib\/api\.js"/);
  // step-up 토큰은 캐시 후 만료 검사(liveStepUpUsable)를 거쳐 재사용/재발급한다.
  assert.match(source, /async function ensureLiveStepUp\(orderId\) \{[\s\S]*?liveStepUpUsable\(liveStepUpRef\.current, orderId\)[\s\S]*?issueLiveOrderStepUp\(orderId\)/);
  // 승인은 사용자가 확인한 표시값으로만 approve 를 호출한다(브로커로 나가지 않는다).
  assert.match(source, /function submitLiveApproval\(orderId, displayed\) \{[\s\S]*?approveLiveOrder\(orderId, \{[\s\S]*?displayedQuantity: displayed\.quantity/);
  // 취소는 cancelLiveOrder 로 간다.
  assert.match(source, /function runLiveCancel\(orderId\) \{[\s\S]*?cancelLiveOrder\(orderId, token\)/);
  // Live 컨텍스트에서만 분리된 핸들러를 OrdersView 로 넘기고, 전송 핸들러를 배선한다.
  assert.match(source, /onOrderAction: isLiveContext \? liveOrderAction : orderAction/);
  assert.match(source, /onDispatch: dispatchLiveOrderAction/);
});

test("live dispatch is a separate broker send: window.confirm gated and carries a caller-minted Idempotency-Key", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  const dispatch = source.match(/function dispatchLiveOrderAction\(orderId\) \{[\s\S]*?\n  \}/);
  assert.ok(dispatch);
  // 전송은 브로커 발주라 반드시 사용자 재확인을 거친다.
  assert.match(dispatch[0], /window\.confirm\(/);
  // Idempotency-Key 는 호출부가 crypto.randomUUID() 로 생성해 넘긴다.
  assert.match(dispatch[0], /dispatchLiveOrder\(orderId, crypto\.randomUUID\(\), token\)/);

  // 승인과 전송은 절대 한 조작으로 합치지 않는다: submitLiveApproval 은 dispatch 를 호출하지 않는다.
  const approve = source.match(/function submitLiveApproval\(orderId, displayed\) \{[\s\S]*?\n  \}/);
  assert.ok(approve);
  assert.doesNotMatch(approve[0], /dispatchLiveOrder/);
});

test("live approval routes the confirm panel through the live approve flow, not the paper path", async () => {
  const source = await readFile(new URL("route-workspace.js", root), "utf8");

  // 승인 패널의 확인은 executionMode 로 Live/Paper 를 갈라 각각의 흐름으로 보낸다.
  assert.match(
    source,
    /onConfirm: displayed =>\s*approvalOrder\.executionMode === "LIVE"\s*\?\s*submitLiveApproval\(approvalOrder\.id, displayed\)\s*:\s*runOrderCommand\(approvalOrder\.id, "approve", displayed\)/);
  // Live 취소는 confirmOrderCancel 이 executionMode 로 갈라 runLiveCancel 로 보낸다.
  assert.match(source, /order\?\.executionMode === "LIVE"\s*\?\s*runLiveCancel\(orderId\)\s*:\s*runOrderCommand\(orderId, "cancel"\)/);
});
