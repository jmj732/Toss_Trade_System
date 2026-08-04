"use client";

import { createElement as h, useEffect, useRef, useState } from "react";

import { BrokerOnboarding } from "./broker-onboarding.js";
import { DashboardView } from "./dashboard-view.js";
import { NotificationCenter } from "./notification-center.js";
import { RiskPolicyPanel } from "./risk-policy-view.js";
import { loginHref, RouteNav } from "./route-workspace.js";
import {
  actOnProposal,
  analyzePortfolio,
  createBrokerConnection,
  createSingleFlight,
  deleteBrokerConnection,
  listNotifications,
  loadDashboard,
  loadRiskPolicy,
  loadRiskPolicyHistory,
  loadSession,
  loadUnreadCount,
  logout,
  markNotificationRead,
  replaceBrokerCredentials,
  syncPortfolio,
  updateRiskPolicy,
  verifyBrokerConnection
} from "../lib/api.js";

export default function Home() {
  const [session, setSession] = useState(undefined);
  const [connectionId, setConnectionId] = useState("");
  const [connection, setConnection] = useState(null);
  const [dashboard, setDashboard] = useState(null);
  const [busyAction, setBusyAction] = useState(null);
  const [busyOrderId, setBusyOrderId] = useState(null);
  const [error, setError] = useState("");
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(0);
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
    try {
      setDashboard(await loadDashboard(id));
    } catch (value) {
      setError(value.message);
    }
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
        setDashboard(await loadDashboard(created.id));
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
      }
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
        h("h1", null, "내 투자, 한눈에"),
        h("p", null, "계속하려면 로그인해 주세요."),
        h("a", { className: "button-link", href: loginHref("home") }, "로그인")));
  }

  const workspaceOpen = Boolean(dashboard);

  return h("div", null,
    h("header", { className: "topbar" },
      h("div", null,
        h("p", { className: "eyebrow" }, "TRADE · 미국주식"),
        h("h1", null, workspaceOpen ? "내 자산" : "내 투자, 한눈에")),
      h("div", { className: "topbar-actions" },
        workspaceOpen ? h(RiskPolicyPanel, {
          policy: riskPolicy,
          history: riskPolicyHistory,
          open: riskPolicyOpen,
          busy: Boolean(busyAction),
          onToggle: riskPolicyToggle,
          onUpdate: riskPolicyUpdate,
          onLoadHistory: riskPolicyLoadHistory
        }) : null,
        workspaceOpen ? h(NotificationCenter, {
          unreadCount,
          notifications,
          open: notificationsOpen,
          busy: Boolean(busyAction),
          onToggle: notificationsToggle,
          onMarkRead: notificationMarkRead
        }) : null,
        h("button", { type: "button", className: "secondary", onClick: signOut }, "로그아웃"))),
    workspaceOpen ? h(RouteNav, {
      symbol: dashboard?.portfolio?.data?.positions?.[0]?.symbol
    }) : null,
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    !workspaceOpen ? h("main", { className: "onboarding-wrap landing-shell" },
      h("div", { className: "landing-copy" },
        h("p", { className: "landing-kicker" }, "미국주식 투자 관리"),
        h("h2", null, "오늘의 투자를\n더 또렷하게"),
        h("p", null, "토스증권 계좌를 연결하면 자산, 위험, 분석을 필요한 순간에만 보여드립니다.")),
      h(BrokerOnboarding, {
        connection,
        connectionId: connectionId.trim(),
        busyAction,
        onCredentials: credentialsAction,
        onCommand: brokerAction
      }),
      h("details", { className: "connection-picker" },
        h("summary", null, "기존 연결 불러오기"),
        h("form", { className: "connection-form", onSubmit: openDashboard },
          h("label", { htmlFor: "connection-id" }, "연결 ID"),
          h("div", null,
            h("input", {
              id: "connection-id",
              value: connectionId,
              onChange: event => setConnectionId(event.target.value),
              placeholder: "연결 ID를 입력하세요",
              required: true
            }),
            h("button", { type: "submit" }, "불러오기"))))) : null,
    workspaceOpen ? h("div", { className: "workspace-content" },
      h(DashboardView, {
        dashboard,
        busyOrderId,
        onOrderAction: orderAction
      })) : null);
}
