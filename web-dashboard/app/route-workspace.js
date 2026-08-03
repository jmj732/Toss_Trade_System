"use client";

import { createElement as h, useEffect, useRef, useState } from "react";

import { AnalysisOutcomeView } from "./analysis-outcome-view.js";
import { BrokerOnboarding } from "./broker-onboarding.js";
import { DashboardView } from "./dashboard-view.js";
import { EventWorkflow } from "./event-workflow.js";
import { OrdersView } from "./orders-view.js";
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
  verifyBrokerConnection
} from "../lib/api.js";

const HISTORY_QUERY = { from: "", to: "", maxPoints: 90 };
const OUTCOME_QUERY = { from: "", to: "", modelVersion: "", contractVersion: "", symbol: "" };

export function RouteNav({ symbol }) {
  const links = [
    ["/", "Dashboard"], ["/portfolio", "Portfolio"],
    [symbol ? `/stocks/${encodeURIComponent(symbol)}` : "/stocks/AAPL", "Stocks"],
    ["/events", "Events"], ["/orders", "Orders"], ["/predictions", "Predictions"],
    ["/settings", "Settings"]
  ];
  return h("nav", { className: "route-nav", "aria-label": "Primary" },
    ...links.map(([href, label]) => h("a", { href, key: href }, label)));
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
      setError("Broker connection UUID is required for this surface");
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
    return actOnProposal(orderId, action, session, crypto.randomUUID())
      .then(() => loadDashboard(connectionId.trim()).then(setDashboard))
      .catch(value => setError(value.message))
      .finally(() => setBusyOrderId(null));
  }

  function createAnalysis() {
    return mutation("analysis", async () => {
      await createStockAnalysis(stockSymbol, {}, session);
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
      }, session);
      await loadStockSurface();
    });
  }

  function createExplanation() {
    return mutation("explanation", async () => {
      await createStockAnalysisExplanation(stockSymbol, session);
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
      const created = await createEvent(connectionId, command, session);
      const [nextEvents, detail] = await Promise.all([
        listEvents(connectionId), loadEvent(connectionId, created.id)
      ]);
      setEvents(nextEvents);
      setSelectedEvent(detail);
    });
  }

  function eventReanalyze(id) {
    return mutation("event", async () => {
      await reanalyzeEvent(connectionId, id, session);
      setSelectedEvent(await loadEvent(connectionId, id));
      setEvents(await listEvents(connectionId));
    });
  }

  function eventReview(id, status, version) {
    return mutation("event", async () => {
      setSelectedEvent(await reviewEvent(
        connectionId, id, status, version, session, crypto.randomUUID()));
      setEvents(await listEvents(connectionId));
    });
  }

  function credentialsAction(action, credentials) {
    return mutation(action, async () => {
      const result = action === "create"
        ? await createBrokerConnection(credentials, session)
        : await replaceBrokerCredentials(connectionId, credentials, session);
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
        setConnection(await verifyBrokerConnection(id, session));
      } else if (action === "sync") {
        await syncPortfolio(id, session);
        await openWorkspace(id);
      } else if (action === "analysis") {
        await analyzePortfolio(id, session);
        await openWorkspace(id);
      } else if (action === "delete") {
        await deleteBrokerConnection(id, session);
        setConnection(null);
        setConnectionId("");
        window.localStorage.removeItem("trade.connectionId");
      }
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
        : h("p", { className: "empty" }, "Open a broker connection to view the portfolio");
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
            await createAnalysisPrediction(connectionId, command, session);
            setOutcome(await loadAnalysisPredictions(connectionId, outcomeQuery));
          }),
          registryBusy: Boolean(busy), registryError: predictionError,
          onRegister: command => mutation("model", async () => {
            await registerPredictionModelVersion(command, session);
            setModels(await loadPredictionModelVersions());
          }),
          onDeprecate: id => mutation("model", async () => {
            await deprecatePredictionModelVersion(id, session);
            setModels(await loadPredictionModelVersions());
          }),
          onDelete: id => mutation("model", async () => {
            await deletePredictionModelVersion(id, session);
            setModels(await loadPredictionModelVersions());
          })
        }),
        h(PredictionOperationsView, {
          operations: predictionOperations, keys: predictionKeys, busy: Boolean(busy),
          error: predictionError, onIssue: command => mutation("key", async () => {
            const result = await issuePredictionIngestionApiKey(command, session);
            if (result?.apiKey) {
              setPredictionKeys(await loadPredictionIngestionApiKeys());
            }
          }),
          onRotate: (id, command) => mutation("key", async () => {
            await rotatePredictionIngestionApiKey(id, command, session);
            setPredictionKeys(await loadPredictionIngestionApiKeys());
          }),
          onRevoke: id => mutation("key", async () => {
            await revokePredictionIngestionApiKey(id, session);
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
      h(BrokerOnboarding, {
        connection, connectionId, busyAction: busy,
        onCredentials: credentialsAction, onCommand: brokerAction
      }),
      h(RiskPolicyPanel, {
        policy: riskPolicy, history: [], open: riskOpen, busy: Boolean(busy),
        onToggle: () => setRiskOpen(value => !value),
        onUpdate: input => mutation("risk-policy", async () => setRiskPolicy(await updateRiskPolicy(input, session))),
        onLoadHistory() {}
      }));
  }

  if (session === undefined) {
    return h("main", { className: "center" }, h("p", null, "Loading session…"));
  }
  if (session === null) {
    return h("main", { className: "center" }, h("div", { className: "login-card" },
      h("p", { className: "eyebrow" }, "TRADE CONTROL"),
      h("h1", null, "Sign in required"),
      h("a", { className: "button-link", href: "/auth/login" }, "OIDC sign in")));
  }
  return h("div", null,
    h("header", { className: "topbar" },
      h("div", null, h("p", { className: "eyebrow" }, "TRADE CONTROL"), h("h1", null, route)),
      h("button", { type: "button", className: "secondary", onClick: () => logout(session).then(() => setSession(null)) },
        "Sign out")),
    h(RouteNav, { symbol: stockSymbol }),
    h("form", { className: "connection-form", onSubmit: event => {
      event.preventDefault(); openWorkspace(event.currentTarget.elements.connectionId.value);
    } },
      h("label", { htmlFor: "route-connection-id" }, "Broker connection UUID"),
      h("div", null,
        h("input", {
          id: "route-connection-id", name: "connectionId", value: connectionId,
          onChange: event => setConnectionId(event.target.value)
        }),
        h("button", { type: "submit", disabled: Boolean(busy) }, "Open"))),
    ErrorMessage({ value: error }),
    routeContent());
}
