"use client";

import { createElement as h, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation.js";

import { AnalysisOutcomeView } from "./analysis-outcome-view.js";
import { BrokerOnboarding } from "./broker-onboarding.js";
import { RealtimePriceTicker } from "./dashboard-view.js";
import {
  DataFreshnessIndicator,
  GlobalStockSearch,
  KillSwitchBanner,
  KillSwitchStatus,
  MarketStatusIndicator,
  PortfolioPositionTable,
  PortfolioRiskPanel,
  PortfolioSummary
} from "./decision-center.js";
import { HomeDecisionCenter } from "./home-decision-center.js";
import { EventWorkflow } from "./event-workflow.js";
import { MarketOverviewView } from "./market-overview-view.js";
import { CANDLE_INTERVALS, MarketCandleChart } from "./market-candle-chart.js";
import { buildActions } from "../lib/action-model.js";
import { resolveSurfaceState } from "../lib/surface-state.js";
import { NotificationCenter } from "./notification-center.js";
import { OrderCreationPanel } from "./order-creation-panel.js";
import { OrderApprovalPanel } from "./order-approval-panel.jsx";
import { OrdersView, OrderContextTabs } from "./orders-view.js";
import { OperationsReadinessView } from "./operations-readiness-view.js";
import { PaperPerformanceView } from "./paper-performance-view.js";
import { PortfolioHistoryView } from "./portfolio-history-view.js";
import { PredictionOperationsView } from "./prediction-operations-view.js";
import { RiskPolicyPanel } from "./risk-policy-view.js";
import { SettingsSections } from "./settings-sections.js";
import { StockAnalysisProductSurface } from "./stock-analysis-product-surface.js";
import { describeError } from "../lib/error-messages.js";
import {
  actOnProposal,
  orderActionKey,
  analyzePortfolio,
  createAnalysisPrediction,
  createBrokerConnection,
  createEvent,
  createSingleFlight,
  createStockAnalysis,
  createStockAnalysisExplanation,
  createStockForecast,
  deleteBrokerConnection,
  deletePredictionModelVersion,
  deprecatePredictionModelVersion,
  issuePredictionIngestionApiKey,
  listEvents,
  listBrokerConnections,
  listNotifications,
  loadAnalysisPredictions,
  loadDashboard,
  loadEvent,
  loadPaperPerformance,
  loadPortfolioHistory,
  loadPredictionIngestionApiKeys,
  loadPredictionModelVersions,
  loadPredictionOperations,
  loadOperationalReadiness,
  loadRiskPolicy,
  loadRiskPolicyHistory,
  loadSession,
  loadUnreadCount,
  markNotificationRead,
  loadStockAnalysis,
  loadStockAnalysisExplanation,
  loadStockAnalysisHistory,
  loadStockAnalysisRun,
  loadStockForecast,
  logout,
  reanalyzeEvent,
  registerPredictionModelVersion,
  replaceBrokerCredentials,
  reviewEvent,
  revokePredictionIngestionApiKey,
  rotatePredictionIngestionApiKey,
  syncPortfolio,
  updateRiskPolicy,
  verifyBrokerConnection,
  runProviderReadinessCheck,
  loadOrderbook,
  loadCandles,
  loadExchangeRate,
  loadMarketCalendar,
  loadStockWarnings,
  loadInvestorTrading,
  loadRankings,
  loadCommissions,
  loadRealtimePrices,
  loadAccountBuyingPower,
  issueLiveOrderStepUp,
  modifyLiveOrder,
  approveLiveOrder,
  dispatchLiveOrder,
  cancelLiveOrder,
  proposePaperOrder,
  previewPaperOrder,
  loadKillSwitch
} from "../lib/api.js";

const HISTORY_QUERY = { from: "", to: "", maxPoints: 90 };
const PAPER_QUERY = { from: "", to: "", maxPoints: 90 };
const OUTCOME_QUERY = { from: "", to: "", modelVersion: "", contractVersion: "", symbol: "" };

export { describeError } from "../lib/error-messages.js";
export function selectHomeSymbol(dashboard) {
  const positions = dashboard?.portfolio?.data?.positions;
  if (!Array.isArray(positions)) {
    return "";
  }
  const symbol = positions.find(value => typeof value?.symbol === "string" && value.symbol.trim())?.symbol;
  return typeof symbol === "string" ? symbol.trim().toUpperCase() : "";
}

export function homeCandleRequestKey(connectionId, symbol, interval) {
  return `${connectionId}\n${symbol}\n${interval}`;
}

export function needsHomeCandleRequest(previous, next) {
  return previous !== next;
}

export function readSavedConnectionId(storage) {
  return storage?.getItem("trade.connectionId")?.trim() ?? "";
}

// Live step-up 토큰은 orderId 별로 캐시하되 expiresAt 을 확인한다. 만료됐거나(파싱 불가 포함)
// 다른 주문이면 재사용하지 않고 재발급하게 한다 — 만료 토큰으로 조용히 재시도하면 재인증이
// 누락된 채 실주문이 나갈 수 있다.
export function liveStepUpUsable(record, orderId, now = Date.now()) {
  if (!record || record.orderId !== orderId || !record.token) {
    return false;
  }
  if (record.expiresAt == null) {
    return true;
  }
  const expiry = new Date(record.expiresAt).getTime();
  return Number.isFinite(expiry) && expiry > now;
}

export function AccountSwitcher({ accountLabel = "계좌", accounts = [], connectionId = "", workspaceStatus = "", busy = false, onSwitch }) {
  return h("details", { className: "account-switcher", "data-route-region": "account-switcher" },
    h("summary", null,
      h("span", { className: "account-switcher-primary" }, accountLabel),
      h("span", { className: "account-switcher-action" }, "계좌 변경")),
    h("div", { className: "account-switcher-options", role: "menu", "aria-label": "연결된 계좌" },
      accounts.length
        ? accounts.map((account, index) => h("button", {
          key: String(account.id), type: "button", role: "menuitem", className: "account-option",
          disabled: workspaceStatus === "loading" || Boolean(busy) || account.id === connectionId,
          onClick: () => onSwitch(account.id)
        }, `${account.brokerType === "TOSS_INVEST" ? "토스증권" : account.brokerType ?? "브로커"} · 계좌 ${index + 1}`))
        : h("p", { className: "empty" }, "연결된 계좌가 없습니다")));
}
export function RouteNav({ symbol }) {
  const pathname = usePathname();
  const stockHref = symbol ? `/stocks/${encodeURIComponent(symbol)}` : null;
  const links = [
    ["/", "홈"], ["/portfolio", "포트폴리오"],
    [stockHref, "종목"],
    ["/events", "이벤트"], ["/orders", "주문"],
    ["/settings", "설정"]
  ];
  return h("nav", { className: "route-nav", "data-route-region": "nav", "aria-label": "주요 메뉴" },
    ...links.map(([href, label]) => href
      // 보유 종목이 없으면 종목 링크는 이동 대상이 없으므로 비활성으로 노출한다.
      ? h("a", {
        href, key: label,
        "aria-current": pathname === href ? "page" : undefined
      }, label)
      : h("span", { key: label, "aria-disabled": "true" }, label)));
}

export function loginHref(route, symbol = "") {
  const ticker = symbol.trim().toUpperCase();
  const path = route === "stock"
    // 심볼을 모르면 보유하지 않을 수 있는 종목을 지어내지 않고 항상 유효한 홈으로 보낸다(D-38).
    ? (ticker ? `/stocks/${encodeURIComponent(ticker)}` : "/")
    : route === "portfolio" ? "/portfolio"
      : route === "events" ? "/events"
        : route === "orders" ? "/orders"
          : route === "predictions" ? "/predictions"
            : route === "settings" ? "/settings" : "/";
  return `/auth/login?returnTo=${encodeURIComponent(path)}`;
}

function ErrorMessage({ value }) {
  return value ? h("p", { className: "error", role: "alert" }, value) : null;
}

export function RouteWorkspace({ route, symbol = "" }) {
  const stockSymbol = symbol.trim().toUpperCase();
  const [session, setSession] = useState(undefined);
  const [connectionId, setConnectionId] = useState("");
  const [connections, setConnections] = useState([]);
  const [connection, setConnection] = useState(null);
  const [dashboard, setDashboard] = useState(null);
  const [events, setEvents] = useState([]);
  const [selectedEvent, setSelectedEvent] = useState(null);
  const [portfolioHistory, setPortfolioHistory] = useState(null);
  const [historyBusy, setHistoryBusy] = useState(false);
  const [stockAnalysis, setStockAnalysis] = useState(null);
  const [stockForecast, setStockForecast] = useState(null);
  const [stockExplanation, setStockExplanation] = useState(null);
  const [stockHistory, setStockHistory] = useState([]);
  const [stockStatus, setStockStatus] = useState({});
  const [stockErrors, setStockErrors] = useState({});
  const [models, setModels] = useState([]);
  const [outcome, setOutcome] = useState(null);
  const [predictionKeys, setPredictionKeys] = useState([]);
  const [predictionOperations, setPredictionOperations] = useState(null);
  const [outcomeQuery, setOutcomeQuery] = useState(OUTCOME_QUERY);
  const [predictionError, setPredictionError] = useState("");
  const [busy, setBusy] = useState("");
  const [predictionLoading, setPredictionLoading] = useState(false);
  const [operationsBusy, setOperationsBusy] = useState(false);
  const [outcomeBusy, setOutcomeBusy] = useState(false);
  // 주문별 진행 상태를 Set 으로 둔다. 스칼라면 두 주문 동시 실행 시
  // 먼저 끝난 쪽이 진행 중인 다른 버튼을 재활성화한다(D-13).
  const [busyOrderIds, setBusyOrderIds] = useState(() => new Set());
  const [approvalOrder, setApprovalOrder] = useState(null);
  const [approvalError, setApprovalError] = useState(null);
  const [error, setError] = useState("");
  // "idle"(미로딩) · "loading" · "ready" · "error" 를 구분해 거짓 empty 를 막는다.
  const [workspaceStatus, setWorkspaceStatus] = useState("idle");
  const [liveMessage, setLiveMessage] = useState("");
  const [riskPolicy, setRiskPolicy] = useState(null);
  const [riskPolicyHistory, setRiskPolicyHistory] = useState([]);
  // BC-7: kill switch(USER scope). null = 미확정(로딩/조회 실패). 조회 실패를 삼켜
  // {engaged:false}("정상")로 접지 않는다 — 거래 차단 상태 오해를 막기 위한 구분이다.
  const [killSwitch, setKillSwitch] = useState(null);
  const [riskOpen, setRiskOpen] = useState(false);
  const [readiness, setReadiness] = useState(null);
  const [readinessError, setReadinessError] = useState("");
  // Settings 는 지연 로딩이라 진입 시 readiness 를 자동 조회하지 않는다(펼칠 때 로드).
  const [readinessBusy, setReadinessBusy] = useState(false);
  // 홈 상단 액션(알림). 로드 전/실패는 null(미확정)로 둔다.
  // 실패를 삼켜 0("읽지 않음 없음")으로 오인시키지 않는다.
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(null);
  // 발급된 예측 수집 API 키는 상태로만 한 번 노출하고 저장소에 쓰지 않는다(V-49).
  const [issuedKey, setIssuedKey] = useState(null);
  const [paperPerformance, setPaperPerformance] = useState(null);
  const [paperQuery, setPaperQuery] = useState(PAPER_QUERY);
  const [paperBusy, setPaperBusy] = useState(false);
  const [orderDraft, setOrderDraft] = useState({ symbol: "", side: "BUY" });
  // P2: Orders 화면의 실행 컨텍스트 탭(모의 Paper / 실거래 Live). 큐 필터와 작성 폼 가용성을 함께 바꾼다.
  const [orderContext, setOrderContext] = useState("PAPER");

  // 신규 API 연동용 State
  const [exchangeRate, setExchangeRate] = useState(null);
  const [marketCalendar, setMarketCalendar] = useState(null);
  const [rankings, setRankings] = useState(null);
  const [realtimePrices, setRealtimePrices] = useState(null);
  const [buyingPower, setBuyingPower] = useState(null);
  const [orderbook, setOrderbook] = useState(null);
  const [candles, setCandles] = useState(null);
  const [candleTimeframe, setCandleTimeframe] = useState("1d");
  const [homeCandleInterval, setHomeCandleInterval] = useState("1m");
  const [homeCandles, setHomeCandles] = useState(null);
  const [investorTrading, setInvestorTrading] = useState(null);
  const [stockWarnings, setStockWarnings] = useState(null);
  const [commissions, setCommissions] = useState(null);

  const opened = useRef(false);
  const openFlight = useRef();
  openFlight.current ??= createSingleFlight();
  // 변경 작업을 단일 실행으로 감싸 연타로 인한 중복 제출을 막는다(모든 라우트 공통).
  const mutationFlight = useRef();
  mutationFlight.current ??= createSingleFlight();
  const homeCandleRequest = useRef("");
  const homeHistoryRequest = useRef(0);
  // Settings 지연 로딩: 이미 로드한 섹션 키를 담아 재요청을 막는다(펼침당 1회만 로드).
  const settingsLoaded = useRef();
  settingsLoaded.current ??= new Set();
  // Live step-up 토큰 캐시: { orderId, token, expiresAt }. 승인 검토 시 발급해 두고
  // 승인·전송·취소에서 재사용하되, 만료됐으면 재발급한다(liveStepUpUsable).
  const liveStepUpRef = useRef(null);

  useEffect(() => {
    loadSession().then(setSession).catch(value => {
      // 세션 조회 실패 시 로딩 화면에 갇히지 않도록 비로그인으로 확정하고 사유를 노출한다.
      setError(describeError(value.message));
      setSession(null);
    });
  }, []);

  useEffect(() => {
    if (!session || opened.current) {
      return;
    }
    opened.current = true;
    loadRiskPolicy().then(setRiskPolicy).catch(value => setError(describeError(value.message)));
    // BC-7: USER scope 만 조회한다. GLOBAL 정지는 별도 계약(후속)이라 여기서 합성하지 않는다.
    // 실패는 null(미확정)로 유지한다 — 조용히 "정상"으로 만들지 않는다.
    loadKillSwitch("USER").then(setKillSwitch).catch(() => setKillSwitch(null));
    if (route === "home") {
      loadUnreadCount().then(result => setUnreadCount(result.count)).catch(() => setUnreadCount(null));
    }
    // 계좌 ID는 내부 복구 키로만 사용한다. 일반 UI 선택은 실제 소유 연결 목록으로 한다.
    const saved = readSavedConnectionId(window.localStorage);
    listBrokerConnections().then(values => {
      const available = Array.isArray(values) ? values : [];
      setConnections(available);
      const selected = available.find(value => value.id === saved) ?? available[0];
      if (selected?.id) {
        setConnectionId(selected.id);
        openWorkspace(selected.id);
      }
    }).catch(() => {
      // 구버전 backend와의 복구 호환. ID는 화면에 다시 표시하지 않는다.
      if (saved) {
        setConnectionId(saved);
        openWorkspace(saved);
      }
    });
  }, [session]);

  useEffect(() => {
    if (route !== "orders" || typeof window === "undefined") {
      return;
    }
    const params = new URLSearchParams(window.location.search);
    setOrderDraft({
      symbol: params.get("symbol")?.trim().toUpperCase() ?? "",
      side: params.get("side") === "SELL" ? "SELL" : "BUY"
    });
  }, [route]);

  // Esc 로 열린 리스크 정책 드롭다운을 닫을 수 있게 한다.
  useEffect(() => {
    if (!riskOpen) {
      return undefined;
    }
    const onKeyDown = event => {
      if (event.key === "Escape") {
        setRiskOpen(false);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [riskOpen]);

  function statusFrom(result) {
    const status = (result?.result ?? result)?.status;
    switch (status) {
      case "DEGRADED": return "DEGRADED";
      case "FAILED": return "FAILED";
      case "RUNNING": return "PROGRESS";
      case "COMPLETED":
      case "SUCCEEDED":
      case "READY": return "READY";
      // CANCELED·QUEUED·TIMEOUT 등 미지 상태를 "정상"으로 위장하지 않는다.
      default: return status ? "UNKNOWN" : "READY";
    }
  }

  async function loadStockSurface(selectedConnectionId = connectionId, selectedRunId = "") {
    if (!stockSymbol) {
      return;
    }
    setOrderbook(null);
    setCandles(null);
    setRealtimePrices(null);
    setInvestorTrading(null);
    setStockWarnings(null);
    setCommissions(null);
    setStockStatus({ analysis: "PROGRESS", forecast: "PROGRESS", explanation: "PROGRESS" });
    setStockErrors({});
    const historyTask = loadStockAnalysisHistory(stockSymbol).then(setStockHistory).catch(value => {
      setStockErrors(current => ({ ...current, history: value.message }));
    });
    let analysisResult;
    try {
      analysisResult = selectedRunId
        ? await loadStockAnalysisRun(stockSymbol, selectedRunId)
        : await loadStockAnalysis(stockSymbol);
    } catch (value) {
      await historyTask;
      setStockAnalysis(null);
      setStockForecast(null);
      setStockExplanation(null);
      setStockStatus({ analysis: value.status === 404 ? "READY" : "FAILED", forecast: "READY", explanation: "READY" });
      if (value.status !== 404) {
        setStockErrors(current => ({ ...current, analysis: value.message }));
        return;
      }
      analysisResult = { result: null };
    }
    setStockAnalysis(analysisResult);
    setStockStatus(current => ({ ...current, analysis: statusFrom(analysisResult) }));
    setStockErrors(current => ({ ...current, analysis: analysisResult.errorCode ?? "" }));
    await historyTask;

    if (selectedConnectionId) {
      const surfaceFailure = value => ({ status: "ERROR", unavailableReason: value.code ?? value.message });
      loadOrderbook(selectedConnectionId, stockSymbol).then(setOrderbook).catch(value => setOrderbook(surfaceFailure(value)));
      loadCandles(selectedConnectionId, stockSymbol, candleTimeframe).then(setCandles).catch(value => setCandles(surfaceFailure(value)));
      loadRealtimePrices(selectedConnectionId, stockSymbol).then(setRealtimePrices).catch(value => setRealtimePrices(surfaceFailure(value)));
      loadInvestorTrading(selectedConnectionId, stockSymbol).then(setInvestorTrading).catch(value => setInvestorTrading(surfaceFailure(value)));
      loadStockWarnings(selectedConnectionId, stockSymbol).then(setStockWarnings).catch(value => setStockWarnings(surfaceFailure(value)));
      loadCommissions(selectedConnectionId, stockSymbol, "BUY").then(setCommissions).catch(value => setCommissions(surfaceFailure(value)));
    }

    if (!analysisResult.result) {
      setStockForecast(null);
      setStockExplanation(null);
      setStockStatus(current => ({
        ...current, forecast: "DEGRADED", explanation: "DEGRADED"
      }));
      setStockErrors(current => ({
        ...current, forecast: "SNAPSHOT_NOT_READY", explanation: "SNAPSHOT_NOT_READY"
      }));
      return;
    }
    const read = async (task, key, setter) => {
      try {
        const result = await task();
        setter(result);
        setStockStatus(current => ({ ...current, [key]: statusFrom(result) }));
      } catch (value) {
        if (value.status === 404) {
          setter(null);
          setStockStatus(current => ({ ...current, [key]: "READY" }));
        } else {
          setStockStatus(current => ({ ...current, [key]: "FAILED" }));
          setStockErrors(current => ({ ...current, [key]: value.message }));
        }
      }
    };
    await Promise.all([
      read(() => loadStockForecast(stockSymbol, analysisResult.runId), "forecast", setStockForecast),
      read(() => loadStockAnalysisExplanation(stockSymbol, analysisResult.runId), "explanation", setStockExplanation)
    ]);
  }

  function openWorkspace(value = connectionId) {
    const id = value.trim();
    if (!id) {
      setError("계좌를 먼저 선택하세요.");
      return Promise.resolve();
    }
    // "열기" 연타로 동시 요청이 경쟁하지 않도록 단일 실행으로 감싼다.
    return openFlight.current(() => loadWorkspace(id));
  }

  async function loadWorkspace(id) {
    setError("");
    setConnectionId(id);
    setWorkspaceStatus("loading");
    window.localStorage.setItem("trade.connectionId", id);
    // connectionId 를 받는 나머지 API는 소유 연결 dashboard 가 준비된 뒤에만 호출한다.
    // loadWorkspace 전체에서 방금 로드한 dashboard 를 참조해야 하므로 try 바깥에 선언한다.
    // (try 블록 안에서 const 로 선언하면 아래 orders 딥링크 등이 클로저의 오래된 state 를 읽는다.)
    let dashboard;
    try {
      dashboard = await loadDashboard(id);
      setDashboard(dashboard);
      setConnection(connections.find(value => value.id === id) ?? { id, status: "ACTIVE" });
      setWorkspaceStatus("ready");
      if (route === "home") {
        loadHomeCandles(id, selectHomeSymbol(dashboard), homeCandleInterval);
      }
    } catch (value) {
      setError(describeError(value.message));
      setWorkspaceStatus("error");
      return;
    }
    const needsEvents = route !== "home";
    if (needsEvents) {
      try {
        setEvents(await listEvents(id));
        if (route === "events" && typeof window !== "undefined") {
          const requestedEventId = new URLSearchParams(window.location.search).get("event");
          if (requestedEventId) {
            setSelectedEvent(await loadEvent(id, requestedEventId));
          }
        }
      } catch (value) {
        setError(describeError(value.message));
      }
    }
    if (route === "portfolio") {
      setHistoryBusy(true);
      try {
        setPortfolioHistory(await loadPortfolioHistory(id, HISTORY_QUERY));
      } catch (value) {
        setError(describeError(value.message));
      } finally {
        setHistoryBusy(false);
      }
    }
    if (route === "stock") {
      loadPredictionModelVersions().then(setModels).catch(value => setPredictionError(describeError(value.message)));
    }
    // Settings 는 진입 시 모든 섹션을 한꺼번에 요청하지 않는다. 각 <details> 섹션은
    // 펼칠 때 loadSettingsSection 이 한 번만 로드한다(아래 lazy 로더 참고).

    if (route === "home") {
      const surfaceFailure = value => ({ status: "ERROR", unavailableReason: value.code ?? value.message });
      const request = homeHistoryRequest.current + 1;
      homeHistoryRequest.current = request;
      setHistoryBusy(true);
      loadPortfolioHistory(id, HISTORY_QUERY)
        .then(value => {
          if (homeHistoryRequest.current === request) setPortfolioHistory(value);
        })
        .catch(value => {
          if (homeHistoryRequest.current === request) {
            setPortfolioHistory({ unavailable: true, unavailableReason: value.code ?? value.message });
          }
        })
        .finally(() => {
          if (homeHistoryRequest.current === request) setHistoryBusy(false);
        });
      loadExchangeRate(id).then(setExchangeRate).catch(value => setExchangeRate(surfaceFailure(value)));
      loadMarketCalendar(id, "US").then(setMarketCalendar).catch(value => setMarketCalendar(surfaceFailure(value)));
      loadRankings(id, "VOLUME").then(setRankings).catch(value => setRankings(surfaceFailure(value)));
      loadRealtimePrices(id, "AAPL,MSFT,NVDA,GOOGL,AMZN,TSLA").then(setRealtimePrices)
        .catch(value => setRealtimePrices({ status: "ERROR", unavailableReason: value.code ?? value.message }));
    }
    if (route === "orders") {
      setBuyingPower({ status: "LOADING" });
      loadAccountBuyingPower(id, "USD").then(setBuyingPower)
        .catch(value => setBuyingPower({ status: "ERROR", unavailableReason: value.code ?? value.message }));
      // 홈 ActionQueue 의 "승인 검토" 가 /orders?order=<id> 로 보내는 경우 해당 주문의 승인 패널을 연다.
      if (typeof window !== "undefined") {
        const requestedOrderId = new URLSearchParams(window.location.search).get("order");
        if (requestedOrderId) {
          const requested = (dashboard?.pendingOrderProposals?.data ?? [])
            .find(candidate => candidate.id === requestedOrderId);
          if (requested) {
            setApprovalError(null);
            setApprovalOrder(requested);
            // 딥링크 진입 시 해당 주문의 실행 컨텍스트 탭을 자동 선택한다(미지 executionMode 는 현재 탭 유지).
            if (requested.executionMode === "LIVE" || requested.executionMode === "PAPER") {
              setOrderContext(requested.executionMode);
            }
          }
        }
      }
    }
    if (route === "stock") {
      await loadStockSurface(id);
    }
  }

  function loadHomeCandles(id, symbol, interval) {
    if (!symbol) {
      homeCandleRequest.current = "";
      setHomeCandles(null);
      return;
    }
    const next = homeCandleRequestKey(id, symbol, interval);
    if (!needsHomeCandleRequest(homeCandleRequest.current, next)) {
      return;
    }
    homeCandleRequest.current = next;
    setHomeCandles({ status: "LOADING" });
    loadCandles(id, symbol, interval)
      .then(value => {
        if (homeCandleRequest.current === next) setHomeCandles(value);
      })
      .catch(value => {
        if (homeCandleRequest.current === next) {
          setHomeCandles({ status: "ERROR", unavailableReason: value.code ?? value.message });
        }
      });
  }

  function mutation(label, task) {
    // 모든 변경 작업을 단일 실행으로 감싼다: 연타 시 두 번째 호출은 첫 요청의 프라미스로
    // 합쳐져 중복 제출을 막는다(openWorkspace 와는 다른 flight 라 중첩 호출도 안전하다).
    return mutationFlight.current(() => {
      setBusy(label);
      setError("");
      return Promise.resolve().then(task)
        .then(() => setLiveMessage(`${label} 작업을 완료했습니다.`))
        .catch(value => {
          setError(describeError(value.message));
          setLiveMessage(`${label} 작업이 실패했습니다.`);
        })
        .finally(() => setBusy(""));
    });
  }

  function markOrderBusy(orderId, busyState) {
    setBusyOrderIds(previous => {
      const next = new Set(previous);
      if (busyState) {
        next.add(orderId);
      } else {
        next.delete(orderId);
      }
      return next;
    });
  }

  // 승인은 2단계다. 목록의 "승인" 은 미리보기 패널을 열기만 하고,
  // 실제 요청은 사용자가 본 값으로만 submitApproval 에서 전송한다(D-01).
  function orderAction(orderId, action) {
    if (action === "approve") {
      const order = (dashboard?.pendingOrderProposals?.data ?? [])
        .find(candidate => candidate.id === orderId);
      setApprovalError(null);
      setApprovalOrder(order ?? { id: orderId });
      return Promise.resolve();
    }
    return confirmOrderCancel(orderId);
  }

  function confirmOrderCancel(orderId) {
    if (typeof window === "undefined") {
      return Promise.resolve();
    }
    const confirmed = window.confirm("이 주문을 취소/거부하시겠습니까? 이 작업은 주문 취소 또는 승인 거부로 기록됩니다.");
    if (!confirmed) {
      return Promise.resolve();
    }
    // Paper 와 Live 는 취소 엔드포인트가 다르다. 실행은 각각 분리된 함수가 담당하고,
    // 이 확인 게이트만 공유한다(executionMode 로 endpoint 를 고르는 API 함수는 만들지 않는다).
    const order = (dashboard?.pendingOrderProposals?.data ?? [])
      .find(candidate => candidate.id === orderId);
    return order?.executionMode === "LIVE"
      ? runLiveCancel(orderId)
      : runOrderCommand(orderId, "cancel");
  }

  function runOrderCommand(orderId, action, displayed) {
    markOrderBusy(orderId, true);
    setError("");
    const options = { idempotencyKey: orderActionKey(orderId, action) };
    if (displayed) {
      options.displayed = displayed;
    }
    return actOnProposal(orderId, action, options)
      .then(() => loadDashboard(connectionId.trim()).then(setDashboard))
      .then(() => {
        setApprovalOrder(null);
        setApprovalError(null);
        setLiveMessage("주문 처리를 완료했습니다.");
      })
      .catch(value => {
        if (action === "approve") {
          setApprovalError(value);
        }
        setError(describeError(value.message));
        setLiveMessage("주문 처리가 실패했습니다.");
      })
      .finally(() => markOrderBusy(orderId, false));
  }

  function modifyLiveOrderAction(orderId, newLimitPrice) {
    return mutation("live-order-modify", async () => {
      const issued = await issueLiveOrderStepUp(orderId);
      await modifyLiveOrder(orderId, newLimitPrice, issued?.stepUpToken);
      await loadWorkspace(connectionId.trim());
    });
  }

  // Live step-up 토큰을 확보한다. 캐시가 유효하면 재사용하고, 만료됐거나 다른 주문이면
  // 재발급한다(D-42 동선과 별개인 step-up 만료 처리). 만료 토큰으로 조용히 재시도하지 않는다.
  async function ensureLiveStepUp(orderId) {
    if (liveStepUpUsable(liveStepUpRef.current, orderId)) {
      return liveStepUpRef.current.token;
    }
    const issued = await issueLiveOrderStepUp(orderId);
    liveStepUpRef.current = {
      orderId,
      token: issued?.stepUpToken ?? "",
      expiresAt: issued?.expiresAt ?? null
    };
    return liveStepUpRef.current.token;
  }

  // Live 실행 3종(approve/dispatch/cancel)의 공통 뼈대. single-flight(mutationFlight) +
  // 주문별 busy Set(D-13) + step-up(만료 시 재발급) + 성공/실패 후 워크스페이스 재조회로
  // 낙관적 상태를 남기지 않는다.
  function runLiveOrderMutation(orderId, run, options = {}) {
    return mutationFlight.current(() => {
      markOrderBusy(orderId, true);
      setError("");
      return ensureLiveStepUp(orderId)
        .then(token => run(token))
        .then(() => loadWorkspace(connectionId.trim()))
        .then(() => {
          options.onSuccess?.();
          if (options.successMessage) {
            setLiveMessage(options.successMessage);
          }
        })
        .catch(value => {
          options.onFailure?.(value);
          setError(describeError(value.message));
          if (options.failureMessage) {
            setLiveMessage(options.failureMessage);
          }
        })
        .finally(() => markOrderBusy(orderId, false));
    });
  }

  // Live "승인 검토": step-up 을 먼저 발급한 뒤 표시값 확인 패널을 연다(아직 approve 아님).
  function openLiveApproval(orderId) {
    const order = (dashboard?.pendingOrderProposals?.data ?? [])
      .find(candidate => candidate.id === orderId);
    return mutationFlight.current(() => {
      markOrderBusy(orderId, true);
      setApprovalError(null);
      setError("");
      return ensureLiveStepUp(orderId)
        .then(() => setApprovalOrder(order ?? { id: orderId }))
        .catch(value => {
          setApprovalError(value);
          setError(describeError(value.message));
        })
        .finally(() => markOrderBusy(orderId, false));
    });
  }

  // Live 승인: 사용자가 확인한 표시값 + step-up 으로 approve. 브로커로 나가지 않는다(전송은 별도).
  function submitLiveApproval(orderId, displayed) {
    return runLiveOrderMutation(orderId,
      token => approveLiveOrder(orderId, {
        displayedQuantity: displayed.quantity,
        displayedMaxLoss: displayed.maxLoss,
        displayedCurrency: displayed.currency
      }, token),
      {
        successMessage: "승인됨 · 아직 브로커로 전송되지 않았습니다. 전송하려면 별도 '브로커로 전송'이 필요합니다.",
        failureMessage: "승인 처리가 실패했습니다.",
        onSuccess: () => {
          setApprovalOrder(null);
          setApprovalError(null);
        },
        onFailure: value => setApprovalError(value)
      });
  }

  // Live 전송: 승인된 주문을 브로커로 실제 발주한다. 승인과 전송은 절대 한 버튼으로 합치지
  // 않는다 — window.confirm 으로 재확인하고 Idempotency-Key(호출부 생성)를 붙인다.
  function dispatchLiveOrderAction(orderId) {
    if (typeof window !== "undefined"
        && !window.confirm(
          "이 주문을 브로커로 전송할까요? 전송하면 실제 계좌에서 주문이 실행됩니다. 승인과 전송은 별개 단계입니다.")) {
      return Promise.resolve();
    }
    return runLiveOrderMutation(orderId,
      token => dispatchLiveOrder(orderId, crypto.randomUUID(), token),
      {
        successMessage: "브로커로 전송했습니다.",
        failureMessage: "브로커 전송이 실패했습니다."
      });
  }

  // Live 취소/거부: confirmOrderCancel 의 확인을 통과한 뒤 실행된다.
  function runLiveCancel(orderId) {
    return runLiveOrderMutation(orderId,
      token => cancelLiveOrder(orderId, token),
      {
        successMessage: "실거래 주문을 취소했습니다.",
        failureMessage: "주문 취소가 실패했습니다.",
        onSuccess: () => {
          setApprovalOrder(null);
          setApprovalError(null);
        }
      });
  }

  // Live 컨텍스트의 목록 액션 핸들러(Paper 의 orderAction 과 분리). 승인은 패널을 열고,
  // 취소는 공유 확인 게이트(confirmOrderCancel)로 보낸다.
  function liveOrderAction(orderId, action) {
    if (action === "approve") {
      return openLiveApproval(orderId);
    }
    return confirmOrderCancel(orderId);
  }

  function createOrderProposal(command) {
    return mutation("order-propose", async () => {
      const proposal = await proposePaperOrder(command);
      setApprovalOrder(proposal);
      setApprovalError(null);
      const refreshed = await loadDashboard(connectionId);
      setDashboard(refreshed);
    });
  }

  // BC-6: 비영속 사전 위험 검사. 결과·busy·무효화는 OrderCreationPanel 이 소유하므로
  // 여기서는 서버 호출만 반환한다(오류도 그대로 전파해 패널이 describeError 로 노출한다).
  function previewOrder(command) {
    return previewPaperOrder(command);
  }

  function createAnalysis() {
    return mutation("analysis", async () => {
      await createStockAnalysis(stockSymbol, {});
      await loadStockSurface();
    });
  }

  function createForecast() {
    const active = models.find(model => model.status === "ACTIVE");
    if (!connectionId) {
      setStockErrors(current => ({ ...current, forecast: "CONNECTION_REQUIRED" }));
      setStockStatus(current => ({ ...current, forecast: "FAILED" }));
      return Promise.resolve();
    }
    if (!active) {
      setStockErrors(current => ({ ...current, forecast: "ACTIVE_MODEL_VERSION_REQUIRED" }));
      setStockStatus(current => ({ ...current, forecast: "FAILED" }));
      return Promise.resolve();
    }
    return mutation("forecast", async () => {
      await createStockForecast(stockSymbol, {
        connectionId, modelVersion: active.modelVersion, contractVersion: active.contractVersion
      });
      await loadStockSurface();
    });
  }

  function createExplanation() {
    return mutation("explanation", async () => {
      await createStockAnalysisExplanation(stockSymbol);
      await loadStockSurface();
    });
  }

  function selectSnapshot(runId) {
    return mutation("snapshot", () => loadStockSurface(connectionId, runId));
  }

  function eventSelect(id) {
    return mutation("event", async () => setSelectedEvent(await loadEvent(connectionId, id)));
  }

  function eventCreate(command) {
    setBusy("event");
    setError("");
    // 실패 시 재던져 폼 초기화를 막는다(입력값 보존). 성공했을 때만 폼이 비워진다.
    return (async () => {
      const created = await createEvent(connectionId, command);
      const [nextEvents, detail] = await Promise.all([
        listEvents(connectionId), loadEvent(connectionId, created.id)
      ]);
      setEvents(nextEvents);
      setSelectedEvent(detail);
      setLiveMessage("이벤트를 등록했습니다.");
    })().catch(value => {
      setError(describeError(value.message));
      setLiveMessage("이벤트 등록이 실패했습니다.");
      throw value;
    }).finally(() => setBusy(""));
  }

  function eventReanalyze(id) {
    return mutation("event", async () => {
      await reanalyzeEvent(connectionId, id);
      setSelectedEvent(await loadEvent(connectionId, id));
      setEvents(await listEvents(connectionId));
    });
  }

  function eventReview(id, status, version) {
    return mutation("event", async () => {
      setSelectedEvent(await reviewEvent(
        connectionId, id, status, version, crypto.randomUUID()));
      setEvents(await listEvents(connectionId));
    });
  }

  function credentialsAction(action, credentials) {
    return mutation(action, async () => {
      const result = action === "create"
        ? await createBrokerConnection(credentials)
        : await replaceBrokerCredentials(connectionId, credentials);
      setConnection(result);
      if (action === "create") {
        setConnectionId(result.id);
        window.localStorage.setItem("trade.connectionId", result.id);
      }
    });
  }

  function brokerAction(action) {
    const id = connectionId.trim();
    // 브로커 연결/자격 증명 삭제는 되돌릴 수 없으므로 모든 라우트에서 확인을 받는다.
    if (action === "delete"
        && !window.confirm("이 브로커 연결과 자격 증명을 삭제할까요?")) {
      return;
    }
    return mutation(action, async () => {
      if (action === "verify") {
        setConnection(await verifyBrokerConnection(id));
      } else if (action === "sync") {
        await syncPortfolio(id);
        await openWorkspace(id);
      } else if (action === "analysis") {
        await analyzePortfolio(id);
        await openWorkspace(id);
      } else if (action === "delete") {
        await deleteBrokerConnection(id);
        setConnection(null);
        setConnectionId("");
        window.localStorage.removeItem("trade.connectionId");
      }
    });
  }

  // 홈 상단의 알림 센터. 열 때만 최신 목록을 불러온다.
  function notificationsToggle() {
    const next = !notificationsOpen;
    setNotificationsOpen(next);
    if (next) {
      listNotifications(false, 50).then(setNotifications)
        .catch(value => setError(value.message));
    }
  }

  function notificationMarkRead(notificationId) {
    return mutation("notification-read", async () => {
      await markNotificationRead(notificationId);
      const [nextNotifications, nextUnreadCount] = await Promise.all([
        listNotifications(false, 50),
        loadUnreadCount()
      ]);
      setNotifications(nextNotifications);
      setUnreadCount(nextUnreadCount.count);
    });
  }

  // 홈 상단의 리스크 정책 패널(이력 포함). 버전 충돌 시 정책을 다시 불러와
  // 다음 저장이 최신 버전을 쓰게 하고, 이력이 열려 있으면 갱신한다.
  function riskPolicyUpdate(input) {
    return mutation("risk-policy", async () => {
      try {
        setRiskPolicy(await updateRiskPolicy(input));
      } catch (value) {
        if (value.message === "RISK_POLICY_VERSION_CONFLICT") {
          setRiskPolicy(await loadRiskPolicy());
        }
        throw value;
      }
      if (riskPolicyHistory.length > 0) {
        setRiskPolicyHistory(await loadRiskPolicyHistory(50));
      }
    });
  }

  function riskPolicyLoadHistory() {
    return mutation("risk-policy-history", async () => {
      setRiskPolicyHistory(await loadRiskPolicyHistory(50));
    });
  }

  function refreshReadiness() {
    setReadinessError("");
    setReadinessBusy(true);
    return loadOperationalReadiness().then(setReadiness).catch(value => {
      setReadinessError(value.message);
      throw value;
    }).finally(() => setReadinessBusy(false));
  }

  function probeReadiness(symbol) {
    setReadinessError("");
    return mutation("readiness", async () => {
      setReadiness(await runProviderReadinessCheck(symbol));
    });
  }

  function stockSurface() {
    const describedErrors = Object.fromEntries(
      Object.entries(stockErrors).map(([key, value]) => [key, describeError(value)]));
    const position = dashboard?.portfolio?.data?.positions?.find(item =>
      item?.symbol?.toUpperCase() === stockSymbol);
    const weight = dashboard?.analysis?.data?.result?.positions?.find(item =>
      item?.symbol?.toUpperCase() === stockSymbol)?.weight;
    return h(StockAnalysisProductSurface, {
      symbol: stockSymbol, analysis: stockAnalysis, forecast: stockForecast, explanation: stockExplanation,
      position: position ? { ...position, weight } : null,
      relatedEvents: events.filter(event => event.affectedSymbols?.some(
        affected => affected.toUpperCase() === stockSymbol)),
      history: stockHistory, status: stockStatus, errors: describedErrors, busy: Boolean(busy),
      onCreateAnalysis: createAnalysis, onCreateForecast: createForecast,
      onCreateExplanation: createExplanation, onSelectSnapshot: selectSnapshot,
      onCreateOrder: () => {
        if (typeof window !== "undefined") {
          window.location.href = `/orders?symbol=${encodeURIComponent(stockSymbol)}&side=BUY`;
        }
      },
      orderbook, realtimePrices, candles, candleTimeframe, investorTrading, stockWarnings, commissions,
      onCandleTimeframeChange: tf => {
        setCandleTimeframe(tf);
        setCandles(null); // Optimistic clear
        loadCandles(connectionId, stockSymbol, tf).then(setCandles).catch(value => setCandles({ status: "ERROR", unavailableReason: value.code ?? value.message }));
      }
    });
  }

  function homeCandleIntervalChange(interval) {
    if (!CANDLE_INTERVALS.some(option => option.key === interval)) {
      return;
    }
    setHomeCandleInterval(interval);
    loadHomeCandles(connectionId.trim(), selectHomeSymbol(dashboard), interval);
  }

  // 미로딩(idle)·로딩중·실패를 각각 구분해 "없음"으로 단언하지 않는다.
  function connectionNotice(idleText) {
    if (workspaceStatus === "loading") {
      return h("p", { className: "empty" }, "불러오는 중…");
    }
    if (workspaceStatus === "error") {
      return h("div", { className: "empty" },
        h("p", { role: "alert" }, error || "정보를 불러오지 못했습니다."),
        h("button", { type: "button", onClick: () => openWorkspace(connectionId) }, "다시 시도"));
    }
    return h("p", { className: "empty" }, idleText);
  }

  const workspaceReady = workspaceStatus === "ready" && Boolean(dashboard);

  // Settings 각 <details> 섹션의 지연 로더. 펼칠 때 호출되고, 이미 로드한 섹션은 재요청하지 않는다.
  function loadSettingsSection(key) {
    if (settingsLoaded.current.has(key)) {
      return;
    }
    settingsLoaded.current.add(key);
    const id = connectionId.trim();
    if (key === "data") {
      setReadinessError("");
      setReadinessBusy(true);
      loadOperationalReadiness().then(setReadiness)
        .catch(value => setReadinessError(describeError(value.message)))
        .finally(() => setReadinessBusy(false));
      return;
    }
    if (key === "analysis") {
      // 예측 품질·모델 레지스트리·API Key·운영을 개별 정산해 하나가 실패해도 나머지를 거짓 empty 로 만들지 않는다.
      setPredictionLoading(true);
      setOperationsBusy(true);
      loadPredictionModelVersions().then(setModels)
        .catch(value => setPredictionError(describeError(value.message)));
      Promise.allSettled([
        loadAnalysisPredictions(id, OUTCOME_QUERY),
        loadPredictionIngestionApiKeys(),
        loadPredictionOperations()
      ]).then(([outcomeResult, keysResult, operationsResult]) => {
        if (outcomeResult.status === "fulfilled") setOutcome(outcomeResult.value);
        else setPredictionError(describeError(outcomeResult.reason.message));
        if (keysResult.status === "fulfilled") setPredictionKeys(keysResult.value);
        else setPredictionError(describeError(keysResult.reason.message));
        if (operationsResult.status === "fulfilled") setPredictionOperations(operationsResult.value);
        else setPredictionError(describeError(operationsResult.reason.message));
      }).finally(() => {
        setPredictionLoading(false);
        setOperationsBusy(false);
      });
      return;
    }
    if (key === "strategy") {
      setPaperBusy(true);
      loadPaperPerformance(id, PAPER_QUERY).then(setPaperPerformance)
        .catch(value => setPredictionError(describeError(value.message)))
        .finally(() => setPaperBusy(false));
    }
  }

  function predictionOperationsView() {
    return h(PredictionOperationsView, {
      operations: predictionOperations, keys: predictionKeys, issuedKey,
      busy: operationsBusy,
      actionBusy: Boolean(busy),
      error: predictionError,
      onIssue: command => mutation("key", async () => {
        const result = await issuePredictionIngestionApiKey(command);
        if (result?.apiKey) {
          // 발급된 원문 키를 상태로만 한 번 노출한다. 저장소에 쓰지 않아 새로고침 시 사라진다(V-49).
          setIssuedKey(result);
          setPredictionKeys(await loadPredictionIngestionApiKeys());
        }
      }),
      onRotate: (id, command) => mutation("key", async () => {
        await rotatePredictionIngestionApiKey(id, command);
        setPredictionKeys(await loadPredictionIngestionApiKeys());
      }),
      onRevoke: id => mutation("key", async () => {
        await revokePredictionIngestionApiKey(id);
        setPredictionKeys(await loadPredictionIngestionApiKeys());
      }),
      onRefresh: () => {
        setOperationsBusy(true);
        return Promise.all([loadPredictionIngestionApiKeys(), loadPredictionOperations()])
          .then(([keys, operations]) => {
            setPredictionKeys(keys); setPredictionOperations(operations);
          })
          .catch(value => setPredictionError(describeError(value.message)))
          .finally(() => setOperationsBusy(false));
      },
      onDismissKey: () => setIssuedKey(null)
    });
  }

  function routeContent() {
    if (route === "stock") {
      return stockSurface();
    }
    if (route === "portfolio") {
      // Risk Panel 은 한도 초과 시 Summary 위로 승격한다(서버 breached 값만 사용, 산술 없음).
      const riskItems = dashboard?.riskEvaluation?.data?.items;
      const riskBreached = Array.isArray(riskItems) && riskItems.some(item => item.breached);
      const summary = h(PortfolioSummary, { key: "summary", dashboard });
      const riskPanel = h(PortfolioRiskPanel, { key: "risk", dashboard });
      const positions = h(PortfolioPositionTable, {
        key: "positions", section: dashboard?.portfolio, analysis: dashboard?.analysis,
        positionDecisions: dashboard?.positionDecisions
      });
      // 추세는 하단 <details> 로 강등한다(ActionQueue 는 홈 전용이라 여기 넣지 않는다).
      const history = h("details", { key: "history", className: "portfolio-history-secondary" },
        h("summary", null, "추세 · 자산/손익"),
        h(PortfolioHistoryView, {
          history: portfolioHistory, query: HISTORY_QUERY, busy: historyBusy,
          onQuery: query => {
            setHistoryBusy(true);
            loadPortfolioHistory(connectionId, query).then(setPortfolioHistory)
              .catch(value => setError(describeError(value.message))).finally(() => setHistoryBusy(false));
          }
        }));
      return workspaceReady
        ? h("main", { className: "route-stack" },
          ...(riskBreached ? [riskPanel, summary] : [summary, riskPanel]),
          positions,
          history)
        : connectionNotice("계좌를 연결하면 포트폴리오를 확인할 수 있습니다.");
    }
    if (route === "orders") {
      // BC-7: killSwitch.engaged===true 만 거래중지로 본다. null(미설정)·조회실패는 차단하지 않는다.
      const tradingHalted = killSwitch?.engaged === true;
      const isLiveContext = orderContext === "LIVE";
      return h("main", { className: "route-stack" },
        workspaceReady
          ? h("div", { className: "orders-workspace" },
            // P2: 화면 상단 실행 컨텍스트 탭. 큐 필터·작성 폼 가용성을 이 선택이 함께 바꾼다.
            h(OrderContextTabs, {
              context: orderContext,
              orders: dashboard?.pendingOrderProposals?.data ?? [],
              onSelect: setOrderContext
            }),
            // P2: 주문 작성은 Paper 컨텍스트에서만. Live 에서는 안내만 노출한다(작성 미지원).
            // 승인·전송·정정·취소는 이제 연동돼 있고, 주문 생성만 미지원이다(Phase 0 게이트 + credential feature flag).
            isLiveContext
              ? h("p", { className: "empty live-order-notice", role: "note", "data-live-order-notice": "" },
                "실거래 주문 생성은 아직 지원하지 않습니다 · 기존 실거래 주문의 승인·전송·정정·취소만 가능합니다")
              : h(OrderCreationPanel, {
                connectionId, initialSymbol: orderDraft.symbol, initialSide: orderDraft.side, busy: busy === "order-propose",
                error: busy === "order-propose" ? "" : error,
                onCreate: createOrderProposal,
                onPreview: previewOrder,
                tradingHalted: killSwitch?.engaged === true,
                haltReason: killSwitch?.reason ?? "",
                buyingPower
              }),
            approvalOrder
              ? h(OrderApprovalPanel, {
                order: approvalOrder,
                busy: busyOrderIds.has(approvalOrder.id),
                error: approvalError,
                tradingHalted,
                // Live 승인은 별도 흐름(step-up + live approve, 브로커 미전송)으로 보낸다.
                onConfirm: displayed =>
                  approvalOrder.executionMode === "LIVE"
                    ? submitLiveApproval(approvalOrder.id, displayed)
                    : runOrderCommand(approvalOrder.id, "approve", displayed),
                onReject: () => confirmOrderCancel(approvalOrder.id),
                onClose: () => {
                  setApprovalOrder(null);
                  setApprovalError(null);
                }
              })
              : null,
            h(OrdersView, {
              section: dashboard?.pendingOrderProposals,
              context: orderContext,
              busyOrderId: busyOrderIds,
              // Live 컨텍스트는 분리된 핸들러를 쓴다(승인 검토→패널, 취소→확인 게이트).
              onOrderAction: isLiveContext ? liveOrderAction : orderAction,
              onModifyPrice: modifyLiveOrderAction,
              onDispatch: dispatchLiveOrderAction,
              tradingHalted,
            }))
          : connectionNotice("계좌를 연결하면 대기 중인 주문을 확인할 수 있습니다."));
    }
    if (route === "events") {
      return h("main", { className: "route-stack" },
        workspaceReady
          ? h(EventWorkflow, {
            key: connectionId.trim(),
            positions: dashboard?.portfolio?.data?.positions ?? [],
            events, selectedEvent, connectionId, busyAction: busy,
            onCreate: eventCreate, onSelect: eventSelect,
            onReanalyze: eventReanalyze, onReview: eventReview
          })
          : connectionNotice("계좌를 연결하면 이벤트를 확인할 수 있습니다."));
    }
    // Settings: 5개 <details> 섹션(계좌 기본 펼침, 나머지 접힘). 상태는 여기서 소유하고,
    // 각 섹션 데이터는 펼칠 때 loadSettingsSection 이 한 번만 지연 로드한다.
    // 예측 관련 기능(AnalysisOutcomeView·PaperPerformanceView·PredictionOperationsView)은
    // /predictions 리다이렉트 이후 이 Settings 한 곳에서만 도달 가능하다(중복 마운트 없음).
    return h("main", { className: "route-stack" },
      h(SettingsSections, {
        onExpand: loadSettingsSection,
        account: h(BrokerOnboarding, {
          connection, connectionId, busyAction: busy,
          onCredentials: credentialsAction, onCommand: brokerAction
        }),
        risk: h("div", { className: "settings-risk" },
          h(RiskPolicyPanel, {
            policy: riskPolicy, history: [], open: riskOpen, busy: Boolean(busy),
            onToggle: () => setRiskOpen(value => !value),
            onUpdate: input => mutation("risk-policy", async () => setRiskPolicy(await updateRiskPolicy(input))),
            onLoadHistory() {}
          }),
          // BC-7: 거래 중지 상태(내 계정 기준). engaged===null(미설정)을 "정상"과 구분해 노출한다.
          h(KillSwitchStatus, { killSwitch })),
        data: h(OperationsReadinessView, {
          readiness, busy: readinessBusy || busy === "readiness", error: readinessError,
          onRefresh: () => refreshReadiness().catch(() => {}),
          onProbe: probeReadiness
        }),
        analysis: h("div", { className: "settings-analysis" },
          h(AnalysisOutcomeView, {
            performance: outcome, versions: models, query: outcomeQuery,
            busy: predictionLoading || outcomeBusy, createBusy: Boolean(busy), createError: predictionError,
            onQuery: query => {
              setOutcomeQuery(query);
              setOutcomeBusy(true);
              loadAnalysisPredictions(connectionId, query).then(setOutcome)
                .catch(value => setPredictionError(describeError(value.message)))
                .finally(() => setOutcomeBusy(false));
            },
            onCreate: command => mutation("prediction", async () => {
              await createAnalysisPrediction(connectionId, command);
              setOutcome(await loadAnalysisPredictions(connectionId, outcomeQuery));
            }),
            registryBusy: Boolean(busy), registryError: predictionError,
            onRegister: command => mutation("model", async () => {
              await registerPredictionModelVersion(command);
              setModels(await loadPredictionModelVersions());
            }),
            onDeprecate: id => mutation("model", async () => {
              await deprecatePredictionModelVersion(id);
              setModels(await loadPredictionModelVersions());
            }),
            onDelete: id => mutation("model", async () => {
              await deletePredictionModelVersion(id);
              setModels(await loadPredictionModelVersions());
            })
          }),
          predictionOperationsView()),
        strategy: h(PaperPerformanceView, {
          performance: paperPerformance, query: paperQuery, busy: predictionLoading || paperBusy,
          onQuery: query => {
            setPaperQuery(query);
            setPaperBusy(true);
            loadPaperPerformance(connectionId, query).then(setPaperPerformance)
              .catch(value => setPredictionError(describeError(value.message)))
              .finally(() => setPaperBusy(false));
          }
        })
      }));
  }

  // 비동기 갱신 결과를 보조기술에 알리는 단일 라이브 영역(홈 포함 모든 라우트 공통).
  const statusRegion = h("div", {
    role: "status", "aria-live": "polite", "aria-atomic": "true",
    style: {
      position: "absolute", width: "1px", height: "1px", padding: 0, margin: "-1px",
      overflow: "hidden", clip: "rect(0 0 0 0)", whiteSpace: "nowrap", border: 0
    }
  }, liveMessage);

  // 홈·비-홈 공통 topbar. AccountSwitcher 를 모든 라우트의 상단으로 단일화한다(중복 섹션 제거).
  function workspaceTopbar({ title, stateText = null }) {
    const account = dashboard?.portfolio?.data?.account;
    // 연결 UUID 대신 displayAccountNumber 를 쓰고, 없으면 AccountSwitcher 의 기본 라벨을 유지한다.
    const accountLabel = account?.displayAccountNumber ?? "계좌";
    return h("header", { className: "topbar" },
      h("div", { "data-route-region": "title" },
        h("p", { className: "eyebrow" }, "TRADE · 미국주식"),
        h("h1", null, title),
        stateText ? h("p", { className: "topbar-state", "data-home-state-label": "" }, stateText) : null),
      h("div", { className: "topbar-actions" },
        workspaceReady ? h(AccountSwitcher, {
          accountLabel,
          accounts: connections,
          connectionId: connectionId.trim(),
          workspaceStatus,
          busy: workspaceStatus === "loading" || Boolean(busy),
          onSwitch: openWorkspace
        }) : null,
        h(GlobalStockSearch, { onSearch: ticker => {
          window.location.href = `/stocks/${encodeURIComponent(ticker)}`;
        }}),
        h(MarketStatusIndicator, { calendar: marketCalendar }),
        workspaceReady ? h(DataFreshnessIndicator, { section: dashboard?.portfolio }) : null,
        workspaceReady ? h(NotificationCenter, {
          unreadCount, notifications, open: notificationsOpen, busy: Boolean(busy),
          onToggle: notificationsToggle, onMarkRead: notificationMarkRead
        }) : null,
        h("a", { className: "button-link secondary", href: "/settings" }, "설정"),
        h("button", {
          type: "button", className: "secondary",
          // 로그아웃이 실패해도 로컬 세션을 반드시 폐기해 탈출구를 보장한다.
          onClick: () => Promise.resolve().then(logout).catch(value => setError(describeError(value.message))).finally(() => setSession(null))
        }, "로그아웃")));
  }

  if (session === undefined) {
    return h("main", { className: "center" }, h("p", null, "세션 불러오는 중…"));
  }
  if (session === null) {
    if (route === "home") {
      // 홈의 로그인 카드는 기존 랜딩 문구를 그대로 보존한다(D-36).
      return h("main", { className: "center" },
        h("div", { className: "login-card" },
          h("p", { className: "eyebrow" }, "TRADE CONTROL"),
          h("h1", null, "내 투자, 한눈에"),
          error ? h("p", { className: "error", role: "alert" }, error) : null,
          h("p", null, "계속하려면 로그인해 주세요."),
          h("a", { className: "button-link", href: loginHref("home") }, "로그인")));
    }
    return h("main", { className: "center" }, h("div", { className: "login-card" },
        h("p", { className: "eyebrow" }, "TRADE CONTROL"),
      h("h1", null, "로그인이 필요합니다"),
      error ? h("p", { className: "error", role: "alert" }, error) : null,
      h("a", { className: "button-link", href: loginHref(route, stockSymbol) }, "로그인")));
  }
  if (route === "home") {
    // 홈은 랜딩/워크스페이스 전환을 갖지만 세션·연결·주문·안전 게이트는 공유 상태/핸들러를 쓴다(D-36).
    const homeSymbol = selectHomeSymbol(dashboard);
    const now = Date.now();
    // 순서 준수: buildActions → 그 결과를 resolveSurfaceState 에 주입.
    const actions = buildActions({ dashboard, now });
    const surface = resolveSurfaceState({ connectionId: connectionId.trim(), dashboard, killSwitch, actions, now });
    // BLOCKED(연결 필요 또는 포트폴리오 데이터 파손)에서는 결정 표면을 신뢰할 수 없으므로
    // HomeDecisionCenter 를 마운트하지 않고 온보딩/랜딩(+파손 안내)을 그린다. dashboard 가
    // 로드됐어도(workspaceReady) dataBroken 이면 빈 workspace-content 를 남기지 않는다(D-36).
    const showWorkspace = workspaceReady && surface.state !== "BLOCKED";
    const stateText = showWorkspace ? `${surface.label} · ${surface.actionCount}건` : null;
    const marketOverview = h(MarketOverviewView, {
      exchangeRate, calendar: marketCalendar, rankings,
      onRankingCategory: category => {
        setRankings({ status: "LOADING" });
        loadRankings(connectionId, category).then(setRankings)
          .catch(value => setRankings({ status: "ERROR", unavailableReason: value.code ?? value.message }));
      }
    });
    // 기존 캔들·시장 개요·실시간 시세를 묶어 marketContext 로 주입한다(기능·요청 동작 불변).
    const marketContext = h("div", { className: "home-market-context-body", "data-home-region-content": "market" },
      h(MarketCandleChart, {
        envelope: homeCandles, symbol: homeSymbol, interval: homeCandleInterval,
        onIntervalChange: homeCandleIntervalChange
      }),
      h("div", { className: "home-market-overview" }, marketOverview),
      h(RealtimePriceTicker, { prices: realtimePrices }));
    // 홈 ActionQueue 의 "승인 검토" 는 /orders?order=<id> 로 이동(승인 패널은 /orders 에만 마운트).
    const homeOrderAction = (id, action) => {
      if (action === "approve") {
        if (typeof window !== "undefined") {
          window.location.href = `/orders?order=${encodeURIComponent(id)}`;
        }
        return Promise.resolve();
      }
      return confirmOrderCancel(id);
    };
    return h("div", null,
      statusRegion,
      workspaceTopbar({ title: showWorkspace ? "Decision Center" : "내 투자, 한눈에", stateText }),
      h(KillSwitchBanner, { killSwitch }),
      showWorkspace ? h(RouteNav, { symbol: homeSymbol }) : null,
      error ? h("p", { className: "error", role: "alert" }, error) : null,
      !showWorkspace ? h("main", { className: "onboarding-wrap landing-shell" },
        h("div", { className: "landing-copy" },
          h("p", { className: "landing-kicker" }, "미국주식 투자 관리"),
          // dataBroken(계좌는 연결됐으나 포트폴리오 섹션 파손)은 온보딩이 아니라 복구 안내를 준다.
          surface.dataBroken
            ? h("h2", null, "포트폴리오 정보를\n불러오지 못했어요")
            : h("h2", null, "오늘의 투자를\n더 또렷하게"),
          surface.dataBroken
            ? h("p", { className: "error", role: "alert" },
              "포트폴리오 데이터를 일시적으로 불러올 수 없습니다. 잠시 후 다시 시도하거나 계좌 연결 상태를 확인해 주세요.")
            : h("p", null, "토스증권 계좌를 연결하면 자산, 위험, 분석을 필요한 순간에만 보여드립니다.")),
        h(BrokerOnboarding, {
          connection,
          connectionId: connectionId.trim(),
          busyAction: busy,
          onCredentials: credentialsAction,
          onCommand: brokerAction
        })) : null,
      showWorkspace ? h("div", { className: "workspace-content" },
        h(HomeDecisionCenter, {
          surface,
          actions,
          dashboard,
          riskPolicy,
          portfolioHistory,
          historyBusy,
          marketContext,
          busyOrderId: busyOrderIds,
          onOrderAction: homeOrderAction,
          onRefresh: () => openWorkspace(connectionId)
        })) : null);
  }
  return h("div", null,
    statusRegion,
    workspaceTopbar({
      title: {
        portfolio: "포트폴리오", stock: stockSymbol || "종목 분석", events: "이벤트",
        orders: "주문", predictions: "시스템", settings: "설정"
      }[route] ?? "내 자산"
    }),
    h(KillSwitchBanner, { killSwitch }),
    h(RouteNav, { symbol: stockSymbol }),
    ErrorMessage({ value: error }),
    routeContent());
}
