"use client";

import { createElement as h, useEffect, useState } from "react";

import { DashboardView } from "./dashboard-view.js";
import { actOnProposal, loadDashboard, loadSession, logout } from "../lib/api.js";

export default function Home() {
  const [session, setSession] = useState(undefined);
  const [connectionId, setConnectionId] = useState("");
  const [dashboard, setDashboard] = useState(null);
  const [busyOrderId, setBusyOrderId] = useState(null);
  const [error, setError] = useState("");

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
          onChange: event => setConnectionId(event.target.value),
          placeholder: "00000000-0000-0000-0000-000000000000",
          required: true
        }),
        h("button", { type: "submit" }, "Open dashboard"))),
    error ? h("p", { className: "error", role: "alert" }, error) : null,
    dashboard ? h(DashboardView, {
      dashboard,
      busyOrderId,
      onOrderAction: orderAction
    }) : h("main", { className: "center compact" },
      h("p", null, "Enter an owned broker connection UUID.")));
}
