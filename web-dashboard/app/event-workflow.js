"use client";

import { createElement as h, useState } from "react";

function value(number) {
  return number ?? "UNAVAILABLE";
}

function Comparison({ detail }) {
  const comparison = detail?.analysisComparison?.comparison;
  if (!comparison) {
    return h("p", { className: "empty" }, "No comparison yet");
  }
  return h("div", null,
    h("p", { className: "quality" },
      h("span", null, comparison.baselineAvailable ? "BASELINE" : "NO BASELINE")),
    h("h3", null, "Affected positions"),
    comparison.positions.length
      ? h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...[
            "Symbol", "Currency",
            "Market value Before", "Market value After", "Market value Change",
            "Profit / loss Before", "Profit / loss After", "Profit / loss Change",
            "Weight Before", "Weight After", "Weight Change"
          ].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...comparison.positions.map(position =>
          h("tr", { key: `${position.currency}-${position.symbol}` },
            h("td", null, position.symbol),
            h("td", null, position.currency),
            h("td", null, value(position.beforeMarketValue)),
            h("td", null, value(position.afterMarketValue)),
            h("td", null, value(position.marketValueChange)),
            h("td", null, value(position.beforeProfitLoss)),
            h("td", null, value(position.afterProfitLoss)),
            h("td", null, value(position.profitLossChange)),
            h("td", null, value(position.beforeWeight)),
            h("td", null, value(position.afterWeight)),
            h("td", null, value(position.weightChange)))))))
      : h("p", { className: "empty" }, "No affected position changes"),
    h("h3", null, "Currency totals"),
    comparison.currencyTotals.length
      ? h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...[
            "Currency",
            "Market value Before", "Market value After", "Market value Change",
            "Profit / loss Before", "Profit / loss After", "Profit / loss Change",
            "Concentration Before", "Concentration After", "Concentration Change"
          ].map(label =>
            h("th", { key: label, scope: "col" }, label)))),
        h("tbody", null, ...comparison.currencyTotals.map(total =>
          h("tr", { key: total.currency },
            h("td", null, total.currency),
            h("td", null, value(total.beforeMarketValue)),
            h("td", null, value(total.afterMarketValue)),
            h("td", null, value(total.marketValueChange)),
            h("td", null, value(total.beforeProfitLoss)),
            h("td", null, value(total.afterProfitLoss)),
            h("td", null, value(total.profitLossChange)),
            h("td", null, value(total.beforeConcentration)),
            h("td", null, value(total.afterConcentration)),
            h("td", null, value(total.concentrationChange)))))))
      : h("p", { className: "empty" }, "No currency total changes"));
}

function EventList({ events, onSelect }) {
  if (events.length === 0) {
    return h("p", { className: "empty" }, "No events");
  }
  return h("ul", { className: "list event-list" }, ...events.map(item =>
    h("li", { key: item.id },
      h("button", {
        type: "button",
        className: "event-select",
        onClick: () => onSelect(item.id)
      },
      h("strong", null, item.summary),
      h("span", null,
        `${item.reviewStatus} · v${item.reviewVersion}`
        + (item.comparisonAvailable ? " · COMPARED" : ""))))));
}

export function EventWorkflow({
  positions,
  events,
  selectedEvent,
  connectionId,
  busyAction,
  onCreate,
  onSelect,
  onReanalyze,
  onReview
}) {
  const [selectedSymbols, setSelectedSymbols] = useState(new Set());
  const busy = Boolean(busyAction);
  const selected = Boolean(selectedEvent);

  function toggle(symbol, checked) {
    setSelectedSymbols(current => {
      const next = new Set(current);
      checked ? next.add(symbol) : next.delete(symbol);
      return next;
    });
  }

  function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    onCreate({
      source: "MANUAL",
      sourceEventId: data.get("sourceEventId"),
      type: data.get("type"),
      summary: data.get("summary"),
      affectedSymbols: [...selectedSymbols],
      occurredAt: new Date(data.get("occurredAt")).toISOString()
    });
    form.reset();
    setSelectedSymbols(new Set());
  }

  return h("section", { className: "event-workflow panel", "aria-busy": busy },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "EVENT INTELLIGENCE"),
        h("h2", null, "Manual event")),
      busyAction ? h("span", { className: "busy" }, `${busyAction}…`) : null),
    h("div", { className: "event-layout" },
      h("form", { className: "event-form", onSubmit: submit },
        h("label", null, "Source event ID",
          h("input", { name: "sourceEventId", required: true, maxLength: 200 })),
        h("label", null, "Event type",
          h("input", { name: "type", required: true, maxLength: 60 })),
        h("label", null, "Summary",
          h("textarea", { name: "summary", required: true, maxLength: 1000 })),
        h("label", null, "Occurred at",
          h("input", { name: "occurredAt", type: "datetime-local", required: true })),
        h("fieldset", { className: "symbol-picker" },
          h("legend", null, "Affected symbols"),
          positions.length
            ? positions.map(position => h("label", { key: position.symbol },
              h("input", {
                type: "checkbox",
                checked: selectedSymbols.has(position.symbol),
                onChange: event => toggle(position.symbol, event.target.checked)
              }),
              position.symbol))
            : h("p", { className: "empty" }, "Open a portfolio with positions first")),
        h("button", {
          type: "submit",
          disabled: busy || !connectionId || selectedSymbols.size === 0
        }, "Register event")),
      h("div", null,
        h("h3", null, "Events"),
        h(EventList, { events, onSelect }))),
    h("div", { className: "event-detail" },
      h("header", null,
        h("div", null,
          h("h3", null, selectedEvent?.summary ?? "Select an event"),
          selectedEvent
            ? h("p", null,
              `${selectedEvent.reviewStatus} · v${selectedEvent.reviewVersion}`)
            : null),
        h("div", { className: "event-review-actions" },
          h("button", {
            type: "button",
            className: "secondary",
            disabled: busy || !selected,
            onClick: () => onReanalyze(selectedEvent.id)
          }, "Reanalyze"),
          [
            ["CONFIRMED", "Confirm"],
            ["HELD", "Hold"],
            ["IGNORED", "Ignore"]
          ].map(([status, label]) => h("button", {
            key: status,
            type: "button",
            className: "secondary",
            disabled: busy || !selected,
            onClick: () => onReview(selectedEvent.id, status, selectedEvent.reviewVersion)
          }, label)))),
      h(Comparison, { detail: selectedEvent })));
}
