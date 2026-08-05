"use client";

import { createElement as h, useState } from "react";

function value(number) {
  return number ?? "확인 필요";
}

function Comparison({ detail }) {
  const comparison = detail?.analysisComparison?.comparison;
  if (!comparison) {
    return h("p", { className: "empty" }, "비교 결과가 아직 없습니다");
  }
  return h("div", null,
    h("p", { className: "quality" },
      h("span", null, comparison.baselineAvailable ? "BASELINE" : "NO BASELINE")),
    h("h3", null, "영향받은 포지션"),
    comparison.positions.length
      ? h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...[
            "종목", "통화",
            "평가금액 이전", "평가금액 이후", "평가금액 변화",
            "손익 이전", "손익 이후", "손익 변화",
            "비중 이전", "비중 이후", "비중 변화"
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
      : h("p", { className: "empty" }, "영향받은 포지션 변화가 없습니다"),
    h("h3", null, "통화별 합계"),
    comparison.currencyTotals.length
      ? h("div", { className: "table-wrap" }, h("table", null,
        h("thead", null, h("tr", null,
          ...[
            "통화",
            "평가금액 이전", "평가금액 이후", "평가금액 변화",
            "손익 이전", "손익 이후", "손익 변화",
            "집중도 이전", "집중도 이후", "집중도 변화"
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
      : h("p", { className: "empty" }, "통화별 합계 변화가 없습니다"));
}

function EventList({ events, onSelect }) {
  if (events.length === 0) {
    return h("p", { className: "empty" }, "이벤트가 없습니다");
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
        h("p", { className: "eyebrow" }, "이벤트 인텔리전스"),
        h("h2", null, "수동 이벤트")),
      busyAction ? h("span", { className: "busy" }, `${busyAction}…`) : null),
    h("div", { className: "event-layout" },
      h("form", { className: "event-form", onSubmit: submit },
        h("label", null, "출처 이벤트 ID",
          h("input", { name: "sourceEventId", required: true, maxLength: 200 })),
        h("label", null, "이벤트 유형",
          h("input", { name: "type", required: true, maxLength: 60 })),
        h("label", null, "요약",
          h("textarea", { name: "summary", required: true, maxLength: 1000 })),
        h("label", null, "발생 시각",
          h("input", { name: "occurredAt", type: "datetime-local", required: true })),
        h("fieldset", { className: "symbol-picker" },
          h("legend", null, "영향받은 종목"),
          positions.length
            ? positions.map(position => h("label", { key: position.symbol },
              h("input", {
                type: "checkbox",
                checked: selectedSymbols.has(position.symbol),
                onChange: event => toggle(position.symbol, event.target.checked)
              }),
              position.symbol))
            : h("p", { className: "empty" }, "먼저 보유 종목이 있는 포트폴리오를 여세요")),
        h("button", {
          type: "submit",
          disabled: busy || !connectionId || selectedSymbols.size === 0
        }, "이벤트 등록")),
      h("div", null,
        h("h3", null, "이벤트"),
        h(EventList, { events, onSelect }))),
    h("div", { className: "event-detail" },
      h("header", null,
        h("div", null,
          h("h3", null, selectedEvent?.summary ?? "이벤트를 선택하세요"),
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
          }, "재분석"),
          [
            ["CONFIRMED", "확인"],
            ["HELD", "보류"],
            ["IGNORED", "무시"]
          ].map(([status, label]) => h("button", {
            key: status,
            type: "button",
            className: "secondary",
            disabled: busy || !selected,
            onClick: () => onReview(selectedEvent.id, status, selectedEvent.reviewVersion)
          }, label)))),
      h(Comparison, { detail: selectedEvent })));
}
