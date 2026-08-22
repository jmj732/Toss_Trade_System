"use client";

// Shared visual primitives for "how close is this to a limit / how is this weighted"
// facts. Every value here must come from the server (current/limit/weight already on
// the payload) — this module only lays it out, it never estimates or defaults a value.
// Shared meter look for Home, Portfolio, and Position Plan.

import { createElement as h } from "react";

function toFiniteNumber(value) {
  if (value === null || value === undefined || value === "") return null;
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

// current/limit → 0..100 clamp. Returns null (render nothing) when either fact is
// missing — never substitutes 0 or 100, since that would assert a limit state that
// wasn't reported.
function usagePercent(current, limit) {
  const c = toFiniteNumber(current);
  const l = toFiniteNumber(limit);
  if (c === null || l === null || l === 0) return null;
  return Math.max(0, Math.min(100, (c / l) * 100));
}

// tone: "ok" | "warn" | "danger" — caller decides from the same server boolean/enum
// it already renders as text (e.g. riskEvaluation item.breached, not a new threshold
// invented here).
export function Meter({ value, max, tone = "ok", label }) {
  const percent = usagePercent(value, max);
  if (percent === null) return null;
  const fillClass = tone === "danger" ? "meter-fill meter-fill--danger"
    : tone === "warn" ? "meter-fill meter-fill--warn" : "meter-fill";
  return h("div", {
    className: "meter",
    role: "progressbar",
    "aria-valuenow": Math.round(percent),
    "aria-valuemin": 0,
    "aria-valuemax": 100,
    "aria-label": label
  }, h("div", { className: fillClass, style: { "--meter-percent": `${percent}%` } }));
}
