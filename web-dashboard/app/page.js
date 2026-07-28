"use client";

import { createElement as h, useEffect, useRef, useState } from "react";

import { BrokerOnboarding } from "./broker-onboarding.js";
import { DashboardView } from "./dashboard-view.js";
import {
  actOnProposal,
  analyzePortfolio,
  createBrokerConnection,
  createSingleFlight,
  deleteBrokerConnection,
  loadDashboard,
  loadSession,
  logout,
  replaceBrokerCredentials,
  syncPortfolio,
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
  const singleFlight = useRef();
  singleFlight.current ??= createSingleFlight();

  useEffect(() => {
    loadSession().then(setSession).catch(value => setError(value.message));
  }, []);

  async function openDashboard(event) {
    event.preventDefault();
    setError("");
    try {
      setDashboard(await loadDashboard(connectionId.trim()));
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
      h("button", { type: "button", className: "secondary", onClick: signOut }, "Sign out")),
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
      })),
    dashboard ? h(DashboardView, {
      dashboard,
      busyOrderId,
      onOrderAction: orderAction
    }) : h("main", { className: "center compact" },
      h("p", null, "Enter an owned broker connection UUID.")));
}
