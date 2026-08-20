// Adaptive Decision Center P0: 대시보드 원본을 사용자가 처리할 Action 목록으로 변환한다.
// 프론트는 값을 새로 계산하지 않는다 — 서버 필드를 문자열화하거나 enum→라벨 매핑만 한다.
// app/ 을 import 하지 않는다(레이어 경계). 한국어 상태 라벨 변환은 UI 책임이다.

import { URGENT_EXPIRY_WINDOW_MS } from "./surface-state.js";

export const ACTION_PRIORITIES = ["URGENT", "HIGH", "MEDIUM", "LOW"];
export const ACTION_TYPES = ["ORDER", "EVENT", "DATA_QUALITY"];

// 주문이 Action 이 되는 상태. 나머지(APPROVED/ACTIVE/COMPLETED/...)는 Orders 큐에서 본다.
const ORDER_ACTION_STATUSES = new Set([
  "PROPOSED", "MANUAL_REVIEW_REQUIRED", "RECONCILIATION_REQUIRED", "BLOCKED"
]);
const ORDER_URGENT_STATUSES = new Set([
  "MANUAL_REVIEW_REQUIRED", "RECONCILIATION_REQUIRED", "BLOCKED"
]);

// 이벤트가 Action 에서 제외되는 검토 상태. PENDING·미검토(null)는 포함한다.
const EVENT_RESOLVED_STATUSES = new Set(["CONFIRMED", "HELD", "IGNORED"]);

const SIDE_LABELS = { BUY: "매수", SELL: "매도" };

const PRIORITY_RANK = { URGENT: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

// unavailable 섹션은 데이터가 없다. data 만 안전하게 꺼낸다.
function sectionData(section) {
  if (!section || section.unavailable === true) {
    return null;
  }
  return section.data ?? null;
}

// 값이 있는 fact 만 남긴다(null/undefined/빈 문자열 제외).
function facts(entries) {
  return entries
    .filter(([, value]) => value !== null && value !== undefined && value !== "")
    .map(([label, value]) => ({ label, value: String(value) }))
    .slice(0, 4);
}

function orderActions({ dashboard, now }) {
  const orders = sectionData(dashboard?.pendingOrderProposals);
  if (!Array.isArray(orders)) {
    return [];
  }
  const items = [];
  for (const order of orders) {
    if (!order || !ORDER_ACTION_STATUSES.has(order.status)) {
      continue;
    }
    let priority;
    if (ORDER_URGENT_STATUSES.has(order.status)) {
      priority = "URGENT";
    } else {
      // PROPOSED: 만료 창 안(이미 만료 포함)이면 URGENT, 아니면 HIGH.
      const expiresMs = order.expiresAt ? Date.parse(order.expiresAt) : NaN;
      priority = Number.isFinite(expiresMs) && expiresMs - now <= URGENT_EXPIRY_WINDOW_MS
        ? "URGENT"
        : "HIGH";
    }
    // 미지 side 는 조용히 접지 않고 원문을 노출한다.
    const sideLabel = SIDE_LABELS[order.side] ?? order.side ?? "";
    const title = `${sideLabel} ${order.symbol ?? ""}`.trim();
    items.push({
      key: `order:${order.id}`,
      id: order.id,
      priority,
      type: "ORDER",
      symbol: order.symbol ?? null,
      title,
      // 상태 value 는 enum 원문. 한국어 변환은 UI 레이어(ORDER_STATUS_LABELS) 책임.
      facts: facts([
        ["상태", order.status],
        ["수량", order.quantity],
        ["유형", order.type],
        ["기한", order.expiresAt ?? null]
      ]),
      statusCode: order.status,
      deadline: order.expiresAt ?? null,
      occurredAt: order.createdAt ?? null,
      sourceType: "ORDER",
      sourceId: order.id != null ? String(order.id) : null,
      order,
      event: null
    });
  }
  return items;
}

function eventActions({ dashboard, events }) {
  // events 우선, 없으면 pendingEvents 섹션. 섹션은 "검토 대기 목록"이라
  // reviewStatus 부재(null/undefined)도 미검토로 보고 Action 에 포함한다.
  const source = Array.isArray(events) && events.length
    ? events
    : sectionData(dashboard?.pendingEvents);
  if (!Array.isArray(source)) {
    return [];
  }

  const positions = sectionData(dashboard?.portfolio)?.positions ?? [];
  const held = new Set(
    positions
      .map(position => position?.symbol)
      .filter(symbol => typeof symbol === "string" && symbol !== "")
      .map(symbol => symbol.toUpperCase())
  );

  const items = [];
  for (const event of source) {
    if (!event || EVENT_RESOLVED_STATUSES.has(event.reviewStatus)) {
      continue;
    }
    const affected = Array.isArray(event.affectedSymbols) ? event.affectedSymbols : [];
    const overlap = affected.filter(
      symbol => typeof symbol === "string" && held.has(symbol.toUpperCase())
    );
    const holdingImpact = overlap.length > 0;
    items.push({
      key: `event:${event.id}`,
      id: event.id,
      priority: holdingImpact ? "HIGH" : "MEDIUM",
      type: "EVENT",
      symbol: holdingImpact ? overlap[0] : (affected[0] ?? null),
      title: event.summary ?? event.type ?? "이벤트",
      facts: facts([
        ["유형", event.type],
        ["출처", event.source],
        ["발생", event.occurredAt],
        ["보유 종목", overlap.join(", ")]
      ]),
      deadline: null,
      occurredAt: event.occurredAt ?? null,
      sourceType: "EVENT",
      sourceId: event.id != null ? String(event.id) : null,
      order: null,
      event,
      holdingImpact,
      comparisonAvailable: Boolean(event.comparisonAvailable)
    });
  }
  return items;
}

function qualityAction(key, title, priority, factEntries) {
  return {
    key,
    id: key.slice("quality:".length),
    priority,
    type: "DATA_QUALITY",
    symbol: null,
    title,
    facts: facts(factEntries),
    deadline: null,
    occurredAt: null,
    sourceType: "PORTFOLIO",
    sourceId: null,
    order: null,
    event: null
  };
}

function qualityActions({ dashboard }) {
  const items = [];
  const portfolio = dashboard?.portfolio;
  const portfolioData = sectionData(portfolio);

  if (portfolio && portfolio.unavailable !== true && portfolio.stale === true) {
    items.push(qualityAction(
      "quality:PORTFOLIO_STALE",
      "포트폴리오 데이터가 지연됐습니다",
      "LOW",
      [["사유", portfolioData?.staleReason ?? null]]
    ));
  }
  if (portfolioData?.partial === true) {
    items.push(qualityAction(
      "quality:PORTFOLIO_PARTIAL",
      "포트폴리오 일부 데이터가 누락됐습니다",
      "LOW",
      []
    ));
  }
  if (portfolioData?.account?.cashBalanceStatus === "UNKNOWN") {
    items.push(qualityAction(
      "quality:CASH_UNKNOWN",
      "현금 잔고를 확인하지 못했습니다",
      "LOW",
      []
    ));
  }

  const analysisResult = sectionData(dashboard?.analysis)?.result;
  if (analysisResult?.status === "DEGRADED" || analysisResult?.quality?.partial === true) {
    items.push(qualityAction(
      "quality:ANALYSIS_DEGRADED",
      "분석 품질이 저하됐습니다",
      "MEDIUM",
      []
    ));
  }
  return items;
}

// priority → deadline(오름차순, null 뒤) → occurredAt(내림차순) → key(사전순).
// 동일 입력 → 동일 출력을 보장하기 위해 전 필드가 결정적이다.
function compareActions(a, b) {
  const rank = PRIORITY_RANK[a.priority] - PRIORITY_RANK[b.priority];
  if (rank !== 0) {
    return rank;
  }
  const aDeadline = a.deadline ? Date.parse(a.deadline) : null;
  const bDeadline = b.deadline ? Date.parse(b.deadline) : null;
  if (aDeadline !== bDeadline) {
    if (aDeadline === null) return 1;
    if (bDeadline === null) return -1;
    return aDeadline - bDeadline;
  }
  const aOccurred = a.occurredAt ? Date.parse(a.occurredAt) : null;
  const bOccurred = b.occurredAt ? Date.parse(b.occurredAt) : null;
  if (aOccurred !== bOccurred) {
    if (aOccurred === null) return 1;
    if (bOccurred === null) return -1;
    return bOccurred - aOccurred; // 최신 먼저
  }
  return a.key < b.key ? -1 : a.key > b.key ? 1 : 0;
}

export function buildActions(input = {}) {
  const { dashboard, events = [], now = Date.now() } = input ?? {};
  const items = [
    ...orderActions({ dashboard, now }),
    ...eventActions({ dashboard, events }),
    ...qualityActions({ dashboard })
  ];
  return items.sort(compareActions);
}
