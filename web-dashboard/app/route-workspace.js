"use client";

import { createElement as h, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation.js";

import { AnalysisOutcomeView } from "./analysis-outcome-view.js";
import { BrokerOnboarding } from "./broker-onboarding.js";
import { DashboardView } from "./dashboard-view.js";
import { EventWorkflow } from "./event-workflow.js";
import { MarketOverviewView } from "./market-overview-view.js";
import { CANDLE_INTERVALS } from "./market-candle-chart.js";
import { NotificationCenter } from "./notification-center.js";
import { OrderApprovalPanel } from "./order-approval-panel.jsx";
import { OrdersView } from "./orders-view.js";
import { OperationsReadinessView } from "./operations-readiness-view.js";
import { PaperPerformanceView } from "./paper-performance-view.js";
import { PortfolioHistoryView } from "./portfolio-history-view.js";
import { PredictionOperationsView } from "./prediction-operations-view.js";
import { RiskPolicyPanel } from "./risk-policy-view.js";
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
  modifyLiveOrder
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

export function AccountSwitcher({ accountLabel = "기본계좌", connectionId = "", busy = false, onSwitch, onConnectionChange }) {
  return h("details", { className: "account-switcher", "data-route-region": "account-switcher" },
    h("summary", null,
      h("span", { className: "account-switcher-primary" }, accountLabel),
      h("span", { className: "account-switcher-action" }, "계좌 변경")),
    h("form", {
      className: "account-switcher-form",
      onSubmit: event => {
        event.preventDefault();
        onSwitch(event.currentTarget.elements.connectionId.value);
      }
    },
    h("label", { htmlFor: "account-switcher-connection-id" }, "연결된 계좌"),
    h("div", null,
      h("input", {
        id: "account-switcher-connection-id",
        name: "connectionId",
        value: connectionId,
        onChange: event => onConnectionChange?.(event.target.value),
        required: true
      }),
      h("button", { type: "submit", disabled: busy || !connectionId.trim() }, "계좌 불러오기"))));
}
export function RouteNav({ symbol }) {
  const pathname = usePathname();
  const stockHref = symbol ? `/stocks/${encodeURIComponent(symbol)}` : null;
  const links = [
    ["/", "홈"], ["/portfolio", "포트폴리오"],
    [stockHref, "종목"],
    ["/events", "이벤트"], ["/orders", "주문"], ["/predictions", "예측"],
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
  const [riskOpen, setRiskOpen] = useState(false);
  const [readiness, setReadiness] = useState(null);
  const [readinessError, setReadinessError] = useState("");
  const [readinessBusy, setReadinessBusy] = useState(route === "settings");
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
    if (route === "home") {
      loadUnreadCount().then(result => setUnreadCount(result.count)).catch(() => setUnreadCount(null));
    }
    if (route === "settings") {
      setReadinessBusy(true);
      loadOperationalReadiness().then(setReadiness)
        .catch(value => setReadinessError(describeError(value.message)))
        .finally(() => setReadinessBusy(false));
    }
    // 로그인 후에는 마지막으로 선택한 계좌를 홈에서도 즉시 복구한다.
    const saved = readSavedConnectionId(window.localStorage);
    if (saved) {
      setConnectionId(saved);
      openWorkspace(saved);
    }
  }, [session]);

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
      setError("연결 ID가 필요합니다.");
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
    try {
      const dashboard = await loadDashboard(id);
      setDashboard(dashboard);
      setConnection({ id, status: "ACTIVE" });
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
      } catch (value) {
        setError(describeError(value.message));
      }
    }
    if (route === "portfolio") {
      try {
        setPortfolioHistory(await loadPortfolioHistory(id, HISTORY_QUERY));
      } catch (value) {
        setError(describeError(value.message));
      }
    }
    if (route === "predictions" || route === "stock") {
      loadPredictionModelVersions().then(setModels).catch(value => setPredictionError(describeError(value.message)));
    }
    if (route === "predictions") {
      // 네 조회를 개별 정산해 하나가 실패해도 나머지를 거짓 empty 로 만들지 않는다.
      setPredictionLoading(true);
      setOperationsBusy(true);
      Promise.allSettled([
        loadAnalysisPredictions(id, OUTCOME_QUERY),
        loadPredictionIngestionApiKeys(),
        loadPredictionOperations(),
        loadPaperPerformance(id, PAPER_QUERY)
      ]).then(([outcomeResult, keysResult, operationsResult, paperResult]) => {
        if (outcomeResult.status === "fulfilled") {
          setOutcome(outcomeResult.value);
        } else {
          setPredictionError(describeError(outcomeResult.reason.message));
        }
        if (keysResult.status === "fulfilled") {
          setPredictionKeys(keysResult.value);
        } else {
          setPredictionError(describeError(keysResult.reason.message));
        }
        if (operationsResult.status === "fulfilled") {
          setPredictionOperations(operationsResult.value);
        } else {
          setPredictionError(describeError(operationsResult.reason.message));
        }
        if (paperResult.status === "fulfilled") {
          setPaperPerformance(paperResult.value);
        } else {
          setPredictionError(describeError(paperResult.reason.message));
        }
      }).finally(() => {
        setPredictionLoading(false);
        setOperationsBusy(false);
      });
    }

    if (route === "home") {
      const surfaceFailure = value => ({ status: "ERROR", unavailableReason: value.code ?? value.message });
      setHistoryBusy(true);
      loadPortfolioHistory(id, HISTORY_QUERY)
        .then(setPortfolioHistory)
        .catch(value => setPortfolioHistory({ unavailable: true, unavailableReason: value.code ?? value.message }))
        .finally(() => setHistoryBusy(false));
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
    return confirmed ? runOrderCommand(orderId, "cancel") : Promise.resolve();
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
    return h(StockAnalysisProductSurface, {
      symbol: stockSymbol, analysis: stockAnalysis, forecast: stockForecast, explanation: stockExplanation,
      relatedEvents: events.filter(event => event.affectedSymbols?.some(
        affected => affected.toUpperCase() === stockSymbol)),
      history: stockHistory, status: stockStatus, errors: describedErrors, busy: Boolean(busy),
      onCreateAnalysis: createAnalysis, onCreateForecast: createForecast,
      onCreateExplanation: createExplanation, onSelectSnapshot: selectSnapshot,
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

  function routeContent() {
    if (route === "stock") {
      return stockSurface();
    }
    if (route === "portfolio") {
      return workspaceReady
        ? h("div", null,
          h(DashboardView, { dashboard, includeOrders: false, realtimePrices }),
          h(PortfolioHistoryView, {
            history: portfolioHistory, query: HISTORY_QUERY, busy: historyBusy,
            onQuery: query => {
              setHistoryBusy(true);
              loadPortfolioHistory(connectionId, query).then(setPortfolioHistory)
                .catch(value => setError(describeError(value.message))).finally(() => setHistoryBusy(false));
            }
          }))
        : connectionNotice("계좌를 연결하면 포트폴리오를 확인할 수 있습니다.");
    }
    if (route === "orders") {
      return h("main", { className: "route-stack" },
        workspaceReady
          ? h("div", null,
            approvalOrder
              ? h(OrderApprovalPanel, {
                order: approvalOrder,
                busy: busyOrderIds.has(approvalOrder.id),
                error: approvalError,
                onConfirm: displayed =>
                  runOrderCommand(approvalOrder.id, "approve", displayed),
                onReject: () => confirmOrderCancel(approvalOrder.id),
                onClose: () => {
                  setApprovalOrder(null);
                  setApprovalError(null);
                }
              })
              : null,
            h(OrdersView, {
              section: dashboard?.pendingOrderProposals,
              busyOrderId: busyOrderIds,
              onOrderAction: orderAction,
              onModifyPrice: modifyLiveOrderAction,
              buyingPower,
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
    if (route === "predictions") {
      return h("main", { className: "route-stack" },
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
        h(PaperPerformanceView, {
          performance: paperPerformance, query: paperQuery, busy: predictionLoading || paperBusy,
          onQuery: query => {
            setPaperQuery(query);
            setPaperBusy(true);
            loadPaperPerformance(connectionId, query).then(setPaperPerformance)
              .catch(value => setPredictionError(describeError(value.message)))
              .finally(() => setPaperBusy(false));
          }
        }),
        h(PredictionOperationsView, {
          operations: predictionOperations, keys: predictionKeys, issuedKey,
          busy: operationsBusy,
          actionBusy: Boolean(busy),
          error: predictionError, onIssue: command => mutation("key", async () => {
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
            return Promise.all([
              loadPredictionIngestionApiKeys(), loadPredictionOperations()
            ]).then(([keys, operations]) => {
              setPredictionKeys(keys); setPredictionOperations(operations);
            }).catch(value => setPredictionError(describeError(value.message)))
              .finally(() => setOperationsBusy(false));
          },
          onDismissKey: () => setIssuedKey(null)
        }));
    }
    return h("main", { className: "route-stack" },
      route === "settings" ? h(OperationsReadinessView, {
        readiness, busy: readinessBusy || busy === "readiness", error: readinessError,
        onRefresh: () => refreshReadiness().catch(() => {}),
        onProbe: probeReadiness
      }) : null,
      h(RiskPolicyPanel, {
        policy: riskPolicy, history: [], open: riskOpen, busy: Boolean(busy),
        onToggle: () => setRiskOpen(value => !value),
        onUpdate: input => mutation("risk-policy", async () => setRiskPolicy(await updateRiskPolicy(input))),
        onLoadHistory() {}
      }),
      h(BrokerOnboarding, {
        connection, connectionId, busyAction: busy,
        onCredentials: credentialsAction, onCommand: brokerAction
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
    // 홈은 상단 액션(리스크 정책·알림)과 랜딩/워크스페이스 전환을 갖는 별도 레이아웃이지만
    // 세션·연결·주문·안전 게이트는 위의 공유 상태/핸들러를 그대로 쓴다(D-36).
    const homeSymbol = selectHomeSymbol(dashboard);
    const marketOverview = h(MarketOverviewView, {
      exchangeRate, calendar: marketCalendar, rankings,
      onRankingCategory: category => {
        setRankings({ status: "LOADING" });
        loadRankings(connectionId, category).then(setRankings)
          .catch(value => setRankings({ status: "ERROR", unavailableReason: value.code ?? value.message }));
      }
    });
    return h("div", null,
      statusRegion,
      h("header", { className: "topbar" },
        h("div", { "data-route-region": "title" },
          h("p", { className: "eyebrow" }, "TRADE · 미국주식"),
          h("h1", null, workspaceReady ? "내 자산" : "내 투자, 한눈에")),
        h("div", { className: "topbar-actions" },
          workspaceReady ? h(AccountSwitcher, {
            accountLabel: "기본계좌",
            connectionId: connectionId.trim(),
            busy: workspaceStatus === "loading" || Boolean(busy),
            onSwitch: openWorkspace,
            onConnectionChange: setConnectionId
          }) : null,
          workspaceReady ? h(RiskPolicyPanel, {
            policy: riskPolicy,
            history: riskPolicyHistory,
            open: riskOpen,
            busy: Boolean(busy),
            onToggle: () => setRiskOpen(value => !value),
            onUpdate: riskPolicyUpdate,
            onLoadHistory: riskPolicyLoadHistory
          }) : null,
          workspaceReady ? h(NotificationCenter, {
            unreadCount,
            notifications,
            open: notificationsOpen,
            busy: Boolean(busy),
            onToggle: notificationsToggle,
            onMarkRead: notificationMarkRead
          }) : null,
          h("button", {
            type: "button", className: "secondary",
            // 로그아웃이 실패해도 로컬 세션을 반드시 폐기해 탈출구를 보장한다.
            onClick: () => Promise.resolve().then(logout).catch(value => setError(describeError(value.message))).finally(() => setSession(null))
          }, "로그아웃"))),
      workspaceReady ? h(RouteNav, { symbol: homeSymbol }) : null,
      error ? h("p", { className: "error", role: "alert" }, error) : null,
      !workspaceReady ? h("main", { className: "onboarding-wrap landing-shell" },
        h("div", { className: "landing-copy" },
          h("p", { className: "landing-kicker" }, "미국주식 투자 관리"),
          h("h2", null, "오늘의 투자를\n더 또렷하게"),
          h("p", null, "토스증권 계좌를 연결하면 자산, 위험, 분석을 필요한 순간에만 보여드립니다.")),
        h(BrokerOnboarding, {
          connection,
          connectionId: connectionId.trim(),
          busyAction: busy,
          onCredentials: credentialsAction,
          onCommand: brokerAction
        }),
        h("details", { className: "connection-picker", "data-route-region": "connection" },
          h("summary", null, "기존 연결 불러오기"),
          h("form", { className: "connection-form", onSubmit: event => {
            event.preventDefault(); openWorkspace(connectionId);
          } },
            h("label", { htmlFor: "connection-id" }, "연결 ID"),
            h("div", null,
              h("input", {
                id: "connection-id",
                value: connectionId,
                onChange: event => setConnectionId(event.target.value),
                placeholder: "연결 ID를 입력하세요",
                required: true
              }),
              h("button", {
                type: "submit",
                disabled: workspaceStatus === "loading" || Boolean(busy)
              }, "불러오기"))))) : null,
      workspaceReady ? h("div", { className: "workspace-content home-operations-shell" },
        approvalOrder
          ? h(OrderApprovalPanel, {
            order: approvalOrder,
            busy: busyOrderIds.has(approvalOrder.id),
            error: approvalError,
            onConfirm: displayed => runOrderCommand(approvalOrder.id, "approve", displayed),
            onReject: () => confirmOrderCancel(approvalOrder.id),
            onClose: () => {
              setApprovalOrder(null);
              setApprovalError(null);
            }
          })
          : null,
        h(DashboardView, {
          dashboard,
          busyOrderId: busyOrderIds,
          onOrderAction: orderAction,
          realtimePrices,
          homeLayout: "operations",
          portfolioHistory,
          historyBusy,
          riskPolicy,
          marketOverview,
          homeCandles,
          homeCandleSymbol: homeSymbol,
          homeCandleInterval,
          onHomeCandleIntervalChange: homeCandleIntervalChange
        })) : null);
  }
  return h("div", null,
    statusRegion,
    h("header", { className: "topbar" },
      h("div", { "data-route-region": "title" },
        h("p", { className: "eyebrow" }, "TRADE · 미국주식"),
        h("h1", null, {
          portfolio: "포트폴리오", stock: stockSymbol || "종목 분석", events: "이벤트",
          orders: "주문", predictions: "분석", settings: "설정"
        }[route] ?? "내 자산")),
      h("button", {
        type: "button", className: "secondary",
        // 로그아웃이 실패해도 로컬 세션을 반드시 폐기해 탈출구를 보장한다.
        onClick: () => Promise.resolve().then(logout).catch(value => setError(describeError(value.message))).finally(() => setSession(null))
      }, "로그아웃")),
    h(RouteNav, { symbol: stockSymbol }),
    h("section", { "data-route-region": "connection", "aria-label": "계좌 연결" },
      h("form", { className: "connection-form", onSubmit: event => {
        event.preventDefault(); openWorkspace(event.currentTarget.elements.connectionId.value);
      } },
        h("label", { htmlFor: "route-connection-id" }, "연결 ID"),
        h("div", null,
          h("input", {
            id: "route-connection-id", name: "connectionId", value: connectionId,
            onChange: event => setConnectionId(event.target.value)
          }),
          h("button", {
            type: "submit",
            // 워크스페이스 로드 중에도 "열기" 를 비활성해 진행 중임을 알린다(D-35).
            disabled: workspaceStatus === "loading" || Boolean(busy)
          }, "열기"))),
      ErrorMessage({ value: error })),
    routeContent());
}
