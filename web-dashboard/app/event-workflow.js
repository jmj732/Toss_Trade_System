"use client";

import { createElement as h, useState } from "react";

import {
  formatAmount, formatRatio, formatSignedAmount, localInputToInstant
} from "../lib/format.js";

function amount(currency, number) {
  return formatAmount(currency, number);
}

function signed(currency, number) {
  return formatSignedAmount(currency, number);
}

function ratio(number) {
  return formatRatio(number);
}

function Comparison({ detail }) {
  const comparison = detail?.analysisComparison?.comparison;
  if (!comparison) {
    return h("p", { className: "empty" }, "비교 결과가 아직 없습니다");
  }
  return h("div", null,
    // V-43: 패널 헤더 정렬용 .quality 유틸을 문단에 오용하지 않는다. 상태 배지는 .badge-pill 로.
    h("p", null,
      h("span", { className: "badge-pill badge-pill--neutral" },
        comparison.baselineAvailable ? "기준값 있음" : "기준값 없음")),
    h("h3", null, "영향받은 포지션"),
    comparison.positions.length
      ? h("div", {
        className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "영향받은 포지션 변화 표"
      }, h("table", null,
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
            h("td", null, amount(position.currency, position.beforeMarketValue)),
            h("td", null, amount(position.currency, position.afterMarketValue)),
            h("td", null, signed(position.currency, position.marketValueChange)),
            h("td", null, signed(position.currency, position.beforeProfitLoss)),
            h("td", null, signed(position.currency, position.afterProfitLoss)),
            h("td", null, signed(position.currency, position.profitLossChange)),
            h("td", null, ratio(position.beforeWeight)),
            h("td", null, ratio(position.afterWeight)),
            h("td", null, ratio(position.weightChange)))))))
      : h("p", { className: "empty" }, "영향받은 포지션 변화가 없습니다"),
    h("h3", null, "통화별 합계"),
    comparison.currencyTotals.length
      ? h("div", {
        className: "table-wrap", tabIndex: 0, role: "region", "aria-label": "통화별 합계 변화 표"
      }, h("table", null,
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
            h("td", null, amount(total.currency, total.beforeMarketValue)),
            h("td", null, amount(total.currency, total.afterMarketValue)),
            h("td", null, signed(total.currency, total.marketValueChange)),
            h("td", null, signed(total.currency, total.beforeProfitLoss)),
            h("td", null, signed(total.currency, total.afterProfitLoss)),
            h("td", null, signed(total.currency, total.profitLossChange)),
            h("td", null, ratio(total.beforeConcentration)),
            h("td", null, ratio(total.afterConcentration)),
            h("td", null, ratio(total.concentrationChange)))))))
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
    // 생성 성공 시에만 폼을 비운다. 실패하면 입력값을 보존해 재시도를 가능하게 한다.
    Promise.resolve(onCreate({
      source: "MANUAL",
      sourceEventId: data.get("sourceEventId"),
      type: data.get("type"),
      summary: data.get("summary"),
      affectedSymbols: [...selectedSymbols],
      occurredAt: localInputToInstant(data.get("occurredAt"))
    })).then(() => {
      form.reset();
      setSelectedSymbols(new Set());
    }).catch(() => {});
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
        h("label", null, "발생 시각 (현지 시각으로 입력)",
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
