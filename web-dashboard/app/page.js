"use client";

import { createElement as h, useEffect, useRef, useState } from "react";

import { BrokerOnboarding } from "./broker-onboarding.js";
import { DashboardView } from "./dashboard-view.js";
import { EventWorkflow } from "./event-workflow.js";
import { NotificationCenter } from "./notification-center.js";
import { PortfolioHistoryView } from "./portfolio-history-view.js";
import { RiskPolicyPanel } from "./risk-policy-view.js";
import {
  actOnProposal,
  analyzePortfolio,
  createBrokerConnection,
  createEvent,
  createSingleFlight,
  deleteBrokerConnection,
  listEvents,
  listNotifications,
  loadDashboard,
  loadEvent,
  loadPortfolioHistory,
  loadRiskPolicy,
  loadRiskPolicyHistory,
  loadSession,
  loadUnreadCount,
  logout,
  markNotificationRead,
  reanalyzeEvent,
  replaceBrokerCredentials,
  reviewEvent,
  syncPortfolio,
  updateRiskPolicy,
  verifyBrokerConnection
} from "../lib/api.js";

const DEFAULT_HISTORY_QUERY = { from: "", to: "", maxPoints: 90 };

export default function Home() {
  const [session, setSession] = useState(undefined);
  const [connectionId, setConnectionId] = useState("");
  const [connection, setConnection] = useState(null);
  const [dashboard, setDashboard] = useState(null);
  const [events, setEvents] = useState([]);
  const [selectedEvent, setSelectedEvent] = useState(null);
  const [busyAction, setBusyAction] = useState(null);
  const [busyOrderId, setBusyOrderId] = useState(null);
  const [error, setError] = useState("");
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [portfolioHistory, setPortfolioHistory] = useState(null);
  const [historyQuery, setHistoryQuery] = useState(DEFAULT_HISTORY_QUERY);
  const [historyBusy, setHistoryBusy] = useState(false);
  const [riskPolicyOpen, setRiskPolicyOpen] = useState(false);
  const [riskPolicy, setRiskPolicy] = useState(null);
  const [riskPolicyHistory, setRiskPolicyHistory] = useState([]);
  const singleFlight = useRef();
  singleFlight.current ??= createSingleFlight();

  useEffect(() => {
    loadSession().then(setSession).catch(value => setError(value.message));
  }, []);

  useEffect(() => {
    if (!session) {
      return;
    }
    loadUnreadCount().then(result => setUnreadCount(result.count)).catch(() => {});
    loadRiskPolicy().then(setRiskPolicy).catch(value => setError(value.message));
  }, [session]);

  async function openDashboard(event) {
    event.preventDefault();
    setError("");
    const id = connectionId.trim();
    setHistoryQuery(DEFAULT_HISTORY_QUERY);
    try {
      const [nextDashboard, nextEvents] = await Promise.all([
        loadDashboard(id),
        listEvents(id)
      ]);
      setDashboard(nextDashboard);
      setEvents(nextEvents);
    } catch (value) {
      setError(value.message);
      return;
    }
    // A history load failure is its own section's problem, not a reason to hide the
    // portfolio/analysis/events/proposals the user actually opened the connection to see.
    try {
      setPortfolioHistory(await loadPortfolioHistory(id, DEFAULT_HISTORY_QUERY));
    } catch (value) {
      setError(value.message);
    }
  }

  function historyQueryChange(query) {
    const id = connectionId.trim();
    setHistoryQuery(query);
    setHistoryBusy(true);
    setError("");
    loadPortfolioHistory(id, query)
      .then(setPortfolioHistory)
      .catch(value => setError(value.message))
      .finally(() => setHistoryBusy(false));
  }

  async function orderAction(orderId, action) {
    setBusyOrderId(orderId);
    setError("");
    try {
      await actOnProposal(orderId, action, session, crypto.randomUUID());
      setDashboard(await loadDashboard(connectionId.trim()));
    } catch (value) {
      setError(value.message);
    } finally {
      setBusyOrderId(null);
    }
  }

  function runMutation(action, task) {
    return singleFlight.current(async () => {
      setBusyAction(action);
      setError("");
      try {
        await task();
      } catch (value) {
        setError(value.message);
      } finally {
        setBusyAction(null);
      }
    });
  }

  function credentialsAction(action, credentials) {
    return runMutation(action, async () => {
      if (action === "create") {
        const created = await createBrokerConnection(credentials, session);
        setConnectionId(created.id);
        setConnection(created);
        setDashboard(null);
        return;
      }
      const replaced = await replaceBrokerCredentials(
        connectionId.trim(), credentials, session);
      setConnection(replaced);
      setDashboard(null);
    });
  }

  function brokerAction(action) {
    const id = connectionId.trim();
    if (action === "delete"
        && !window.confirm("Delete this broker connection and its credentials?")) {
      return;
    }
    return runMutation(action, async () => {
      if (action === "verify") {
        setConnection(await verifyBrokerConnection(id, session));
      } else if (action === "sync") {
        await syncPortfolio(id, session);
        setDashboard(await loadDashboard(id));
      } else if (action === "analysis") {
        await analyzePortfolio(id, session);
        setDashboard(await loadDashboard(id));
      } else if (action === "delete") {
        await deleteBrokerConnection(id, session);
        setConnectionId("");
        setConnection(null);
        setDashboard(null);
        setEvents([]);
        setSelectedEvent(null);
      }
    });
  }

  function eventCreate(command) {
    const id = connectionId.trim();
    return runMutation("event-create", async () => {
      const created = await createEvent(id, command, session);
      const [nextDashboard, nextEvents, detail] = await Promise.all([
        loadDashboard(id),
        listEvents(id),
        loadEvent(id, created.id)
      ]);
      setDashboard(nextDashboard);
      setEvents(nextEvents);
      setSelectedEvent(detail);
    });
  }

  function eventSelect(eventId) {
    return runMutation("event-load", async () => {
      setSelectedEvent(await loadEvent(connectionId.trim(), eventId));
    });
  }

  function eventReanalyze(eventId) {
    const id = connectionId.trim();
    return runMutation("event-analysis", async () => {
      await reanalyzeEvent(id, eventId, session);
      const [nextEvents, detail] = await Promise.all([
        listEvents(id),
        loadEvent(id, eventId)
      ]);
      setEvents(nextEvents);
      setSelectedEvent(detail);
    });
  }

  function eventReview(eventId, status, version) {
    const id = connectionId.trim();
    return runMutation("event-review", async () => {
      const detail = await reviewEvent(
        id, eventId, status, version, session, crypto.randomUUID());
      setSelectedEvent(detail);
      setEvents(await listEvents(id));
    });
  }

  function notificationsToggle() {
    const next = !notificationsOpen;
    setNotificationsOpen(next);
    if (next) {
      listNotifications(false, 50).then(setNotifications)
        .catch(value => setError(value.message));
    }
  }

  function notificationMarkRead(notificationId) {
    return runMutation("notification-read", async () => {
      await markNotificationRead(notificationId, session);
      const [nextNotifications, nextUnreadCount] = await Promise.all([
        listNotifications(false, 50),
        loadUnreadCount()
      ]);
      setNotifications(nextNotifications);
      setUnreadCount(nextUnreadCount.count);
    });
  }

  function riskPolicyToggle() {
    setRiskPolicyOpen(open => !open);
  }

  function riskPolicyUpdate(input) {
    return runMutation("risk-policy-update", async () => {
      try {
        setRiskPolicy(await updateRiskPolicy(input, session));
      } catch (value) {
        // A stale expectedVersion means someone else's edit already landed — refresh so the
        // next Save attempt uses the real current version instead of retrying the same one
        // forever.
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
    return runMutation("risk-policy-history", async () => {
      setRiskPolicyHistory(await loadRiskPolicyHistory(50));
    });
  }

  async function signOut() {
    setError("");
    try {
      await logout(session);
      setSession(null);
      setDashboard(null);
    } catch (value) {
      setError(value.message);
    }
  }

  if (session === undefined) {
    return h("main", { className: "center" }, h("p", null, "Loading session…"));
  }
  if (session === null) {
    return h("main", { className: "center" },
      h("div", { className: "login-card" },
        h("p", { className: "eyebrow" }, "TRADE CONTROL"),
        h("h1", null, "Portfolio cockpit"),
        h("p", null, "Sign in with your configured identity provider."),
        h("a", { className: "button-link", href: "/auth/login" }, "OIDC sign in")));
  }

  return h("div", null,
    h("header", { className: "topbar" },
      h("div", null,
        h("p", { className: "eyebrow" }, "TRADE CONTROL"),
        h("h1", null, "Portfolio cockpit")),
      h("div", { className: "topbar-actions" },
        h(RiskPolicyPanel, {
          policy: riskPolicy,
          history: riskPolicyHistory,
          open: riskPolicyOpen,
          busy: Boolean(busyAction),
          onToggle: riskPolicyToggle,
          onUpdate: riskPolicyUpdate,
          onLoadHistory: riskPolicyLoadHistory
        }),
        h(NotificationCenter, {
          unreadCount,
          notifications,
          open: notificationsOpen,
          busy: Boolean(busyAction),
          onToggle: notificationsToggle,
          onMarkRead: notificationMarkRead
        }),
        h("button", { type: "button", className: "secondary", onClick: signOut }, "Sign out"))),
    h("form", { className: "connection-form", onSubmit: openDashboard },
      h("label", { htmlFor: "connection-id" }, "Broker connection UUID"),
      h("div", null,
        h("input", {
          id: "connection-id",
          value: connectionId,
          onChange: event => {
            setConnectionId(event.target.value);
            setConnection(null);
            setDashboard(null);
            setEvents([]);
            setSelectedEvent(null);
            setPortfolioHistory(null);
            setHistoryQuery(DEFAULT_HISTORY_QUERY);
          },
          placeholder: "00000000-0000-0000-0000-000000000000",
          required: true
        }),
        h("button", { type: "submit" }, "Open dashboard"))),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    h("main", { className: "onboarding-wrap" },
      h(BrokerOnboarding, {
        connection,
        connectionId: connectionId.trim(),
        busyAction,
        onCredentials: credentialsAction,
        onCommand: brokerAction
      }),
      h(EventWorkflow, {
        key: connectionId.trim(),
        positions: dashboard?.portfolio?.data?.positions ?? [],
        events,
        selectedEvent,
        connectionId: connectionId.trim(),
        busyAction,
        onCreate: eventCreate,
        onSelect: eventSelect,
        onReanalyze: eventReanalyze,
        onReview: eventReview
      })),
    dashboard ? h("div", null,
      h(DashboardView, {
        dashboard,
        busyOrderId,
        onOrderAction: orderAction
      }),
      h(PortfolioHistoryView, {
        key: connectionId.trim(),
        history: portfolioHistory,
        query: historyQuery,
        busy: historyBusy,
        onQuery: historyQueryChange
      })) : h("main", { className: "center compact" },
      h("p", null, "Enter an owned broker connection UUID.")));
}
