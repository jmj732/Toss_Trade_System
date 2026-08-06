"use client";

import { createElement as h, useEffect, useRef, useState } from "react";
import { usePathname } from "next/navigation.js";

import { AnalysisOutcomeView } from "./analysis-outcome-view.js";
import { BrokerOnboarding } from "./broker-onboarding.js";
import { DashboardView } from "./dashboard-view.js";
import { EventWorkflow } from "./event-workflow.js";
import { OrderApprovalPanel } from "./order-approval-panel.jsx";
import { OrdersView } from "./orders-view.js";
import { OperationsReadinessView } from "./operations-readiness-view.js";
import { PortfolioHistoryView } from "./portfolio-history-view.js";
import { PredictionOperationsView } from "./prediction-operations-view.js";
import { RiskPolicyPanel } from "./risk-policy-view.js";
import { StockAnalysisProductSurface } from "./stock-analysis-product-surface.js";
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
  loadSession,
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
  runProviderReadinessCheck
} from "../lib/api.js";

const HISTORY_QUERY = { from: "", to: "", maxPoints: 90 };
const OUTCOME_QUERY = { from: "", to: "", modelVersion: "", contractVersion: "", symbol: "" };

// 백엔드 오류 코드를 한국어 안내 + 다음 행동으로 옮긴다. 미등록 코드만 원문을 그대로 노출한다.
const ERROR_MESSAGES = {
  SNAPSHOT_NOT_READY: "스냅샷이 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.",
  CONNECTION_REQUIRED: "먼저 계좌를 연결해 주세요.",
  ACTIVE_MODEL_VERSION_REQUIRED: "먼저 활성 모델 버전을 등록해 주세요.",
  ORDERS_UNAVAILABLE: "지금은 주문 정보를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.",
  INTERNAL_ERROR: "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
  PAPER_ORDER_STEP_UP_REQUIRED: "보안 확인이 필요합니다. 다시 로그인한 뒤 시도해 주세요.",
  PAPER_ORDER_CONFLICT: "이미 처리 중인 주문입니다. 잠시 후 상태를 확인해 주세요.",
  RISK_POLICY_VERSION_CONFLICT: "다른 변경이 먼저 반영됐습니다. 최신 정책을 불러온 뒤 다시 시도해 주세요."
};

export function describeError(code) {
  if (!code) {
    return code;
  }
  return ERROR_MESSAGES[code] ?? code;
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
  return h("nav", { className: "route-nav", "aria-label": "주요 메뉴" },
    ...links.map(([href, label]) => href
      // 보유 종목이 없으면 종목 링크는 이동 대상이 없으므로 비활성으로 노출한다.
      ? h("a", {
        href, key: label,
        "aria-current": pathname === href ? "page" : undefined
      }, label)
      : h("span", { key: label, "aria-disabled": "true" }, label)));
}

export function loginHref(route, symbol = "") {
  const path = route === "stock"
    ? `/stocks/${encodeURIComponent(symbol.trim().toUpperCase() || "AAPL")}`
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
  const [riskOpen, setRiskOpen] = useState(false);
  const [readiness, setReadiness] = useState(null);
  const [readinessError, setReadinessError] = useState("");
  const opened = useRef(false);
  const openFlight = useRef();
  openFlight.current ??= createSingleFlight();

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
    if (route === "settings") {
      loadOperationalReadiness().then(setReadiness).catch(value => setReadinessError(describeError(value.message)));
    }
    // 종목 데이터는 워크스페이스(계좌 연결) 로드 성공 여부와 무관하게 항상 조회한다.
    if (route === "stock") {
      loadStockSurface();
    }
    const saved = window.localStorage.getItem("trade.connectionId") ?? "";
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

  async function loadStockSurface(selectedRunId = "") {
    if (!stockSymbol) {
      return;
    }
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
      }
      return;
    }
    setStockAnalysis(analysisResult);
    setStockStatus(current => ({ ...current, analysis: statusFrom(analysisResult) }));
    setStockErrors(current => ({ ...current, analysis: analysisResult.errorCode ?? "" }));
    await historyTask;
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
    // 이벤트 조회 실패만으로 포트폴리오 전체를 폐기하지 않도록 부분 성공을 반영한다.
    const [dashboardResult, eventsResult] = await Promise.allSettled([
      loadDashboard(id), listEvents(id)
    ]);
    if (dashboardResult.status === "fulfilled") {
      setDashboard(dashboardResult.value);
      setConnection({ id, status: "ACTIVE" });
      setWorkspaceStatus("ready");
    } else {
      setError(describeError(dashboardResult.reason.message));
      setWorkspaceStatus("error");
    }
    if (eventsResult.status === "fulfilled") {
      setEvents(eventsResult.value);
    } else {
      setError(describeError(eventsResult.reason.message));
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
      // 세 조회를 개별 정산해 하나가 실패해도 나머지를 거짓 empty 로 만들지 않는다.
      Promise.allSettled([
        loadAnalysisPredictions(id, OUTCOME_QUERY),
        loadPredictionIngestionApiKeys(),
        loadPredictionOperations()
      ]).then(([outcomeResult, keysResult, operationsResult]) => {
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
      });
    }
  }

  function mutation(label, task) {
    setBusy(label);
    setError("");
    return Promise.resolve().then(task)
      .then(() => setLiveMessage(`${label} 작업을 완료했습니다.`))
      .catch(value => {
        setError(describeError(value.message));
        setLiveMessage(`${label} 작업이 실패했습니다.`);
      })
      .finally(() => setBusy(""));
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
    return runOrderCommand(orderId, "cancel");
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
    return mutation("snapshot", () => loadStockSurface(runId));
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

  function refreshReadiness() {
    setReadinessError("");
    return loadOperationalReadiness().then(setReadiness).catch(value => {
      setReadinessError(value.message);
      throw value;
    });
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
      onCreateExplanation: createExplanation, onSelectSnapshot: selectSnapshot
    });
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
          h(DashboardView, { dashboard, includeOrders: false }),
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
                onReject: () => runOrderCommand(approvalOrder.id, "cancel"),
                onClose: () => {
                  setApprovalOrder(null);
                  setApprovalError(null);
                }
              })
              : null,
            h(OrdersView, {
              section: dashboard?.pendingOrderProposals,
              busyOrderId: busyOrderIds,
              onOrderAction: orderAction
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
          busy: outcomeBusy, createBusy: Boolean(busy), createError: predictionError,
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
        h(PredictionOperationsView, {
          operations: predictionOperations, keys: predictionKeys, busy: Boolean(busy),
          error: predictionError, onIssue: command => mutation("key", async () => {
            const result = await issuePredictionIngestionApiKey(command);
            if (result?.apiKey) {
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
          onRefresh: () => Promise.all([
            loadPredictionIngestionApiKeys(), loadPredictionOperations()
          ]).then(([keys, operations]) => {
            setPredictionKeys(keys); setPredictionOperations(operations);
          }).catch(value => setPredictionError(describeError(value.message))),
          onDismissKey() {}
        }));
    }
    return h("main", { className: "route-stack" },
      route === "settings" ? h(OperationsReadinessView, {
        readiness, busy: busy === "readiness", error: readinessError,
        onRefresh: () => refreshReadiness().catch(() => {}),
        onProbe: probeReadiness
      }) : null,
      h(BrokerOnboarding, {
        connection, connectionId, busyAction: busy,
        onCredentials: credentialsAction, onCommand: brokerAction
      }),
      h(RiskPolicyPanel, {
        policy: riskPolicy, history: [], open: riskOpen, busy: Boolean(busy),
        onToggle: () => setRiskOpen(value => !value),
        onUpdate: input => mutation("risk-policy", async () => setRiskPolicy(await updateRiskPolicy(input))),
        onLoadHistory() {}
      }));
  }

  if (session === undefined) {
    return h("main", { className: "center" }, h("p", null, "세션 불러오는 중…"));
  }
  if (session === null) {
    return h("main", { className: "center" }, h("div", { className: "login-card" },
        h("p", { className: "eyebrow" }, "TRADE CONTROL"),
      h("h1", null, "로그인이 필요합니다"),
      error ? h("p", { className: "error", role: "alert" }, error) : null,
      h("a", { className: "button-link", href: loginHref(route, stockSymbol) }, "로그인")));
  }
  return h("div", null,
    // 비동기 갱신 결과를 보조기술에 알리는 단일 라이브 영역.
    h("div", {
      role: "status", "aria-live": "polite", "aria-atomic": "true",
      style: {
        position: "absolute", width: "1px", height: "1px", padding: 0, margin: "-1px",
        overflow: "hidden", clip: "rect(0 0 0 0)", whiteSpace: "nowrap", border: 0
      }
    }, liveMessage),
    h("header", { className: "topbar" },
      h("div", null,
        h("p", { className: "eyebrow" }, "TRADE · 미국주식"),
        h("h1", null, {
          portfolio: "포트폴리오", stock: stockSymbol || "종목 분석", events: "이벤트",
          orders: "주문", predictions: "분석", settings: "설정"
        }[route] ?? "내 자산")),
      h("button", {
        type: "button", className: "secondary",
        // 로그아웃이 실패해도 로컬 세션을 반드시 폐기해 탈출구를 보장한다.
        onClick: () => Promise.resolve().then(logout).catch(() => {}).finally(() => setSession(null))
      }, "로그아웃")),
    h(RouteNav, { symbol: stockSymbol }),
    h("section", { "aria-label": "계좌 연결" },
      h("form", { className: "connection-form", onSubmit: event => {
        event.preventDefault(); openWorkspace(event.currentTarget.elements.connectionId.value);
      } },
        h("label", { htmlFor: "route-connection-id" }, "연결 ID"),
        h("div", null,
          h("input", {
            id: "route-connection-id", name: "connectionId", value: connectionId,
            onChange: event => setConnectionId(event.target.value)
          }),
          h("button", { type: "submit", disabled: Boolean(busy) }, "열기"))),
      ErrorMessage({ value: error })),
    routeContent());
}
