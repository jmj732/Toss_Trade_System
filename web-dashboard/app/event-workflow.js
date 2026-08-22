"use client";

import { createElement as h, useState } from "react";

import {
  UNKNOWN_TEXT, formatAmount, formatInstant, formatRatio, formatSignedAmount, localInputToInstant
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

const BUSY_LABELS = {
  "event-create": "등록 중…",
  "event-reanalyze": "재분석 중…",
  "event-review": "검토 반영 중…"
};

function busyLabel(action) {
  return BUSY_LABELS[action] ?? (action ? `${action}…` : null);
}

function eventStatus(event) {
  return `${event?.reviewStatus ?? "UNKNOWN"} · v${event?.reviewVersion ?? UNKNOWN_TEXT}`;
}

// 이벤트 종류 표식은 서버가 준 event.source 값만 그대로 보여준다(FILING 필터가 이미 쓰는
// event.source === "SEC" 와 같은 근거). 실제로 관측되는 값은 자동 수집 공급자 enum
// (SEC/IR/FED/FRED/BLS/BEA — MarketEventProviderId)과 수동 등록 시 프론트가 보내는
// "MANUAL"(이 파일의 submit() 참고) 뿐이라 그 외 카테고리는 만들지 않는다. 검토 상태 배지와는
// 다른 축이라 별도 배지로 둔다.
function eventSourceBadge(event) {
  const source = event?.source;
  if (!source) return null;
  const label = String(source).toUpperCase();
  const tone = label === "MANUAL" ? "neutral" : "ok";
  return h("span", { className: `badge-pill badge-pill--${tone}`, "data-event-source": label }, label);
}

function affectedSymbols(event) {
  return event?.affectedSymbols?.length ? event.affectedSymbols.join(", ") : UNKNOWN_TEXT;
}

function nextAction(event) {
  if (!event) return "이벤트 선택";
  if (event.reviewStatus === "CONFIRMED" || event.reviewStatus === "HELD" || event.reviewStatus === "IGNORED") {
    return event.comparisonAvailable ? "재분석 또는 검토 변경" : "재분석";
  }
  return "검토 결정";
}

function comparisonState(event) {
  return event?.comparisonAvailable ? "비교 완료" : "비교 대기";
}

function EventSignal({ event, compact = false }) {
  const tag = compact ? "span" : "p";
  return h(tag, { className: compact ? "event-signal event-signal--compact" : "event-signal" },
    h("span", { className: "badge-pill badge-pill--warn" }, eventStatus(event)),
    eventSourceBadge(event),
    h("span", null, "영향 ", affectedSymbols(event)),
    h("span", null, "시각 ", formatInstant(event?.occurredAt)),
    h("span", null, "다음 작업: ", nextAction(event)),
    event ? h("span", null, comparisonState(event)) : null);
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

function isHeldEvent(event, heldSymbols) {
  return (event?.affectedSymbols ?? []).some(symbol => heldSymbols.has(symbol?.toUpperCase()));
}

function EventList({ events, heldSymbols, onSelect }) {
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
      // 보유 종목 표식은 영향 종목과 보유 포지션의 교집합이 있을 때만 붙인다.
      isHeldEvent(item, heldSymbols)
        ? h("span", { className: "badge-pill badge-pill--info", "data-event-held": "true" }, "보유")
        : null,
      h(EventSignal, { event: item, compact: true })))));
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
  const [filter, setFilter] = useState("ALL");
  const busy = Boolean(busyAction);
  const selected = Boolean(selectedEvent);
  const heldSymbols = new Set(positions.map(position => position.symbol?.toUpperCase()).filter(Boolean));
  // 필터는 서버가 준 필드(source·macroScope)와 보유 심볼 교집합으로만 건다. 프론트에서 새 분류를
  // 추론하지 않는다. "실적"·"뉴스"는 대응하는 서버 필드가 없어(자유 텍스트 type뿐) 제공하지 않는다.
  const FILTERS = [
    ["ALL", "전체", () => true],
    ["HELD", "보유종목", event => isHeldEvent(event, heldSymbols)],
    ["FILING", "공시", event => event.source === "SEC"],
    ["MACRO", "거시", event => Array.isArray(event.macroScope) && event.macroScope.length > 0]
  ];
  const activeFilter = FILTERS.find(([key]) => key === filter) ?? FILTERS[0];
  const visibleEvents = events.filter(event => activeFilter[2](event));

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
        h("p", { className: "eyebrow" }, "Intelligence Feed"),
        h("h2", null, "이벤트 인텔리전스")),
      busyAction ? h("span", { className: "busy", title: `${busyAction}…` }, busyLabel(busyAction)) : null),
    // Feed(좌) + Detail(우) 2열. Intelligence → Impact → Action 순서로 읽히게 구성한다.
    h("div", { className: "event-layout" },
      h("div", { className: "intelligence-feed" },
        h("div", { className: "feed-filter" },
          h("label", { htmlFor: "event-type-filter" }, "필터"),
          h("select", { id: "event-type-filter", value: filter, onChange: event => setFilter(event.target.value) },
            ...FILTERS.map(([key, label]) => h("option", { key, value: key }, label)))),
        h(EventList, { events: visibleEvents, heldSymbols, onSelect })),
      h("div", { className: "event-detail" },
        h("header", null,
          h("div", null,
            h("h3", null, selectedEvent?.summary ?? "이벤트를 선택하세요"),
            selectedEvent
              ? h(EventSignal, { event: selectedEvent })
              : h("p", { className: "empty" }, "이벤트를 선택하면 상태와 영향 범위를 확인합니다"))),
        // 이 화면의 최대 자산: "내 포트폴리오가 얼마 움직였는가"에 직답하는 before/after 비교표를
        // Detail 본문 최상단으로 승격한다. 판단 변화(이전→신규 Decision)는 계약이 없어(BC-5) 만들지 않는다.
        h("div", { className: "event-impact" },
          h("h3", null, "포트폴리오 영향 (재분석 기준)"),
          h(Comparison, { detail: selectedEvent })),
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
          }, label)),
          selectedEvent?.affectedSymbols?.[0]
            ? h("a", { className: "button-link secondary", href: `/stocks/${encodeURIComponent(selectedEvent.affectedSymbols[0])}` }, "영향 종목 보기")
            : null,
          selectedEvent?.affectedSymbols?.[0]
            ? h("a", { className: "button-link secondary", href: `/orders?symbol=${encodeURIComponent(selectedEvent.affectedSymbols[0])}&side=BUY` }, "주문 작성")
            : null))),
    // 수동 이벤트 등록은 최하단 <details> 로 강등한다(기능은 유지).
    h("details", { className: "manual-event-secondary" },
      h("summary", null, "수동 이벤트 등록"),
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
      }, "이벤트 등록"))));
}
