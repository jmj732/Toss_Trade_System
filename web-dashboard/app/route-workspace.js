"use client";

import { createElement as h, useEffect, useRef, useState } from "react";

import { AnalysisOutcomeView } from "./analysis-outcome-view.js";
import { BrokerOnboarding } from "./broker-onboarding.js";
import { DashboardView } from "./dashboard-view.js";
import { EventWorkflow } from "./event-workflow.js";
import { OrdersView } from "./orders-view.js";
import { OperationsReadinessView } from "./operations-readiness-view.js";
import { PortfolioHistoryView } from "./portfolio-history-view.js";
import { PredictionOperationsView } from "./prediction-operations-view.js";
import { RiskPolicyPanel } from "./risk-policy-view.js";
import { StockAnalysisProductSurface } from "./stock-analysis-product-surface.js";
import {
  actOnProposal,
  analyzePortfolio,
  createAnalysisPrediction,
  createBrokerConnection,
  createEvent,
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

export function RouteNav({ symbol }) {
  const links = [
    ["/", "홈"], ["/portfolio", "포트폴리오"],
    [symbol ? `/stocks/${encodeURIComponent(symbol)}` : "/stocks/AAPL", "종목"],
    ["/events", "이벤트"], ["/orders", "주문"], ["/predictions", "예측"],
    ["/settings", "설정"]
  ];
  return h("nav", { className: "route-nav", "aria-label": "주요 메뉴" },
    ...links.map(([href, label]) => h("a", { href, key: href }, label)));
}

export function loginHref(route, symbol = "") {
  const path = route === "stock"
    ? `/stocks/${encodeURIComponent(symbol.trim().toUpperCase() || "AAPL")}`
    : route === "portfolio" ? "/portfolio"
      : route === "events" ? "/events"
        : route === "orders" ? "/orders"
          : route === "predictions" ? "/predictions"
            : route === "settings" ? "/settings" : "/";
  return `/login?returnTo=${encodeURIComponent(path)}`;
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
  const [busyOrderId, setBusyOrderId] = useState(null);
  const [error, setError] = useState("");
  const [riskPolicy, setRiskPolicy] = useState(null);
  const [riskOpen, setRiskOpen] = useState(false);
  const [readiness, setReadiness] = useState(null);
  const [readinessError, setReadinessError] = useState("");
  const opened = useRef(false);

  useEffect(() => {
    loadSession().then(setSession).catch(value => setError(value.message));
  }, []);

  useEffect(() => {
    if (!session || opened.current) {
      return;
    }
    opened.current = true;
    loadRiskPolicy().then(setRiskPolicy).catch(value => setError(value.message));
    if (route === "settings") {
      loadOperationalReadiness().then(setReadiness).catch(value => setReadinessError(value.message));
    }
    const saved = window.localStorage.getItem("trade.connectionId") ?? "";
    if (saved) {
      setConnectionId(saved);
      openWorkspace(saved);
    } else if (route === "stock") {
      loadStockSurface();
    }
  }, [session]);

  function statusFrom(result) {
    const status = (result?.result ?? result)?.status;
    return status === "DEGRADED" ? "DEGRADED"
      : status === "FAILED" ? "FAILED"
        : status === "RUNNING" ? "PROGRESS" : "READY";
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

  async function openWorkspace(value = connectionId) {
    const id = value.trim();
    if (!id) {
      setError("연결 ID가 필요합니다.");
      return;
    }
    setError("");
    setConnectionId(id);
    window.localStorage.setItem("trade.connectionId", id);
    try {
      const [nextDashboard, nextEvents] = await Promise.all([
        loadDashboard(id), listEvents(id)
      ]);
      setDashboard(nextDashboard);
      setEvents(nextEvents);
      setConnection({ id, status: "ACTIVE" });
    } catch (value) {
      setError(value.message);
      return;
    }
    if (route === "portfolio") {
      try {
        setPortfolioHistory(await loadPortfolioHistory(id, HISTORY_QUERY));
      } catch (value) {
        setError(value.message);
      }
    }
    if (route === "predictions" || route === "stock") {
      loadPredictionModelVersions().then(setModels).catch(value => setPredictionError(value.message));
    }
    if (route === "predictions") {
      Promise.all([
        loadAnalysisPredictions(id, OUTCOME_QUERY),
        loadPredictionIngestionApiKeys(),
        loadPredictionOperations()
      ]).then(([nextOutcome, keys, operations]) => {
        setOutcome(nextOutcome);
        setPredictionKeys(keys);
        setPredictionOperations(operations);
      }).catch(value => setPredictionError(value.message));
    }
    if (route === "stock") {
      loadStockSurface();
    }
  }

  function mutation(label, task) {
    setBusy(label);
    setError("");
    return Promise.resolve().then(task).catch(value => setError(value.message))
      .finally(() => setBusy(""));
  }

  function orderAction(orderId, action) {
    setBusyOrderId(orderId);
    return actOnProposal(orderId, action, crypto.randomUUID())
      .then(() => loadDashboard(connectionId.trim()).then(setDashboard))
      .catch(value => setError(value.message))
      .finally(() => setBusyOrderId(null));
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
    return mutation("event", async () => {
      const created = await createEvent(connectionId, command);
      const [nextEvents, detail] = await Promise.all([
        listEvents(connectionId), loadEvent(connectionId, created.id)
      ]);
      setEvents(nextEvents);
      setSelectedEvent(detail);
    });
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
    return h(StockAnalysisProductSurface, {
      symbol: stockSymbol, analysis: stockAnalysis, forecast: stockForecast, explanation: stockExplanation,
      relatedEvents: events.filter(event => event.affectedSymbols?.some(
        affected => affected.toUpperCase() === stockSymbol)),
      history: stockHistory, status: stockStatus, errors: stockErrors,
      onCreateAnalysis: createAnalysis, onCreateForecast: createForecast,
      onCreateExplanation: createExplanation, onSelectSnapshot: selectSnapshot
    });
  }

  function routeContent() {
    if (route === "stock") {
      return stockSurface();
    }
    if (route === "portfolio") {
      return dashboard
        ? h("div", null,
          h(DashboardView, { dashboard, includeOrders: false }),
          h(PortfolioHistoryView, {
            history: portfolioHistory, query: HISTORY_QUERY, busy: historyBusy,
            onQuery: query => {
              setHistoryBusy(true);
              loadPortfolioHistory(connectionId, query).then(setPortfolioHistory)
                .catch(value => setError(value.message)).finally(() => setHistoryBusy(false));
            }
          }))
        : h("p", { className: "empty" }, "계좌를 연결하면 포트폴리오를 확인할 수 있습니다.");
    }
    if (route === "orders") {
      return h(OrdersView, {
        section: dashboard?.pendingOrderProposals,
        busyOrderId,
        onOrderAction: orderAction
      });
    }
    if (route === "events") {
      return h(EventWorkflow, {
        key: connectionId.trim(),
        positions: dashboard?.portfolio?.data?.positions ?? [],
        events, selectedEvent, connectionId, busyAction: busy,
        onCreate: eventCreate, onSelect: eventSelect,
        onReanalyze: eventReanalyze, onReview: eventReview
      });
    }
    if (route === "predictions") {
      return h("main", { className: "route-stack" },
        h(AnalysisOutcomeView, {
          performance: outcome, versions: models, query: outcomeQuery,
          busy: false, createBusy: Boolean(busy), createError: predictionError,
          onQuery: query => {
            setOutcomeQuery(query);
            loadAnalysisPredictions(connectionId, query).then(setOutcome)
              .catch(value => setPredictionError(value.message));
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
          }),
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
    return h("main", { className: "center" }, h("p", null, "Loading session…"));
  }
  if (session === null) {
    return h("main", { className: "center" }, h("div", { className: "login-card" },
        h("p", { className: "eyebrow" }, "TRADE CONTROL"),
      h("h1", null, "로그인이 필요합니다"),
      h("a", { className: "button-link", href: loginHref(route, stockSymbol) }, "로그인")));
  }
  return h("div", null,
    h("header", { className: "topbar" },
      h("div", null,
        h("p", { className: "eyebrow" }, "TRADE · 미국주식"),
        h("h1", null, {
          portfolio: "포트폴리오", stock: stockSymbol || "종목 분석", events: "이벤트",
          orders: "주문", predictions: "분석", settings: "설정"
        }[route] ?? "내 자산")),
      h("button", { type: "button", className: "secondary", onClick: () => logout().then(() => setSession(null)) },
        "Sign out")),
    h(RouteNav, { symbol: stockSymbol }),
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
    ErrorMessage({ value: error }),
    routeContent());
}
