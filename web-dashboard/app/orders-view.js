import { createElement as h, useState } from "react";

import { formatAmount, formatQuantity, classifyProposalExpiry, UNKNOWN_TEXT } from "../lib/format.js";
import { describeError } from "../lib/error-messages.js";
import { OrderStatusBadge, OrderExpiryBadge, OrderTiming } from "./dashboard-view.js";

const SIDE_LABELS = { BUY: "매수", SELL: "매도" };

function knownSide(side) {
  return Object.prototype.hasOwnProperty.call(SIDE_LABELS, side);
}

// P2: 실행 컨텍스트는 PAPER/LIVE 두 값만 알려진 값이다. 그 외(미지·누락)는 조용히
// Paper 로 접지 않고 "구분 미확인" 으로 분리한다(오발주 방지).
export function knownExecutionMode(mode) {
  return mode === "PAPER" || mode === "LIVE";
}

const OPEN_ORDER_STATUSES = new Set([
  "PROPOSED", "APPROVED", "REVALIDATING", "SUBMISSION_PENDING", "RECONCILIATION_REQUIRED",
  "MANUAL_REVIEW_REQUIRED", "ACTIVE", "PARTIALLY_COMPLETED", "BLOCKED"
]);
const CLOSED_ORDER_STATUSES = new Set(["COMPLETED", "CANCELED", "REJECTED", "EXPIRED"]);

// D-13: busyOrderId 가 스칼라(현행)든 Set/배열(권장)이든 모두 처리한다.
function isOrderBusy(busy, id) {
  if (busy == null) {
    return false;
  }
  if (typeof busy === "string") {
    return busy === id;
  }
  if (busy instanceof Set) {
    return busy.has(id);
  }
  if (Array.isArray(busy)) {
    return busy.includes(id);
  }
  return false;
}

// P2: Paper/Live 실행 컨텍스트 탭. 큐·작성 폼 가용성을 이 선택이 함께 바꾼다.
// 상태는 상위(route-workspace)가 소유하고 여기서는 표시·선택만 한다(controlled).
export function OrderContextTabs({ context, orders = [], onSelect }) {
  const list = Array.isArray(orders) ? orders : [];
  const paperCount = list.filter(order => order.executionMode === "PAPER").length;
  const liveCount = list.filter(order => order.executionMode === "LIVE").length;
  const unknownCount = list.filter(order => !knownExecutionMode(order.executionMode)).length;
  const tab = (value, label, count, extraClass = "") => h("button", {
    type: "button",
    role: "tab",
    "aria-selected": context === value ? "true" : "false",
    className: `order-context-tab${extraClass ? ` ${extraClass}` : ""}${context === value ? " is-active" : ""}`,
    onClick: () => onSelect?.(value)
  }, `${label} (${count})`);
  return h("div", {
    className: `order-context-tabs order-context-tabs--${context === "LIVE" ? "live" : "paper"}`,
    role: "tablist",
    "aria-label": "실행 컨텍스트",
    "data-order-context": context
  },
    tab("PAPER", "모의(Paper)", paperCount),
    tab("LIVE", "실거래(Live)", liveCount, "order-context-tab--live"),
    unknownCount
      ? h("span", {
        className: "order-context-unknown-note",
        "data-order-unknown-count": String(unknownCount)
      }, `구분 미확인 ${unknownCount}건`)
      : null);
}

export function OrdersView({
  section,
  busyOrderId,
  onOrderAction,
  onModifyPrice,
  onDispatch,
  context = null,
  tradingHalted = false
}) {
  const [filterTab, setFilterTab] = useState("ALL");
  const [modifyingId, setModifyingId] = useState(null);
  const [newPriceInput, setNewPriceInput] = useState("");

  const allOrders = section?.data ?? [];
  // context==null 은 레거시(컨텍스트 미선택) 경로다: executionMode 로 나누지 않고 전부 보인다.
  // context 가 지정되면 해당 실행 컨텍스트의 주문만 큐에 담고, 미지 executionMode 는 분리한다.
  const contextOrders = context == null
    ? allOrders
    : allOrders.filter(order => order.executionMode === context);
  const unknownOrders = context == null
    ? []
    : allOrders.filter(order => !knownExecutionMode(order.executionMode));

  const filteredOrders = contextOrders.filter(order => {
    if (filterTab === "OPEN") {
      return OPEN_ORDER_STATUSES.has(order.status);
    }
    if (filterTab === "CLOSED") {
      return CLOSED_ORDER_STATUSES.has(order.status);
    }
    return true;
  });

  // 실거래는 기존 주문의 승인·전송·정정·취소가 연동돼 있다(주문 생성만 미지원). 이 플래그는
  // Live 전용 안내 문구를 노출할지에만 쓴다.
  const contextIsLive = context === "LIVE";

  // 한 행을 그린다. blockApprove/blockCancel/blockModify/blockDispatch 는 거래중지·구분미확인 같은
  // 행 외적 사유로 실행 버튼을 추가로 비활성화한다. 각 행의 busy·side·status·만료 게이트는 그대로다.
  function renderOrderRow(order, { blockApprove = false, blockCancel = false, blockModify = false, blockDispatch = false } = {}) {
    const sideKnown = knownSide(order.side);
    // D-29/D-30: 한국어 매수/매도, 미지 side 는 원문 노출.
    const sideText = sideKnown ? SIDE_LABELS[order.side] : (order.side ?? UNKNOWN_TEXT);
    // D-29: limitPrice 를 요약과 동일하게 표시한다.
    const priceText = order.limitPrice == null
      ? ""
      : ` @ ${formatAmount(order.currency, order.limitPrice)}`;
    const busy = isOrderBusy(busyOrderId, order.id);
    // D-42: 만료된 제안은 승인을 막는다. 서버 409 는 최후 방어선일 뿐이다.
    const expiryState = classifyProposalExpiry(order);
    const expired = expiryState === "expired";
    // D-03: 상태 필터가 넓어져 APPROVED/ACTIVE/COMPLETED 등도 이 목록에 도달한다. PROPOSED 만 액션 대상이고 나머지는 표시 전용이다.
    const actionable = order.status === "PROPOSED";
    const modifiable = order.executionMode === "LIVE"
      && order.status === "ACTIVE"
      && order.type === "LIMIT";
    // Live 는 승인(APPROVED) 이후에도 자동으로 브로커로 나가지 않는다. 승인 ≠ 전송이므로
    // 승인된 Live 주문에만 별도 "브로커로 전송" 을 노출한다.
    const dispatchable = order.executionMode === "LIVE" && order.status === "APPROVED";
    const isModifying = modifyingId === order.id;

    return h("li", {
      key: order.id,
      className: `order-row order-row--${sideKnown ? order.side.toLowerCase() : "unknown"}`,
      "data-order-row": order.id
    },
      h("div", { className: "order-row-main" },
        h("div", { className: "order-row-copy" },
          h("strong", { className: "order-title" }, `${sideText} ${order.symbol ?? UNKNOWN_TEXT}`),
          h("span", { className: "order-summary" },
            `${order.type ?? UNKNOWN_TEXT} · ${formatQuantity(order.quantity)}`
            + ` · ${order.currency ?? UNKNOWN_TEXT}${priceText}`),
          // D-03: 상태 배지. 미지 상태도 그대로 드러낸다.
          h(OrderStatusBadge, { status: order.status }),
          // D-42: 만료 배지와 생성/만료 시각.
          h(OrderExpiryBadge, { state: expiryState }),
          h(OrderTiming, { order }),
          // 승인 ≠ 전송: 승인된 Live 주문은 "승인됨" 상태여도 아직 브로커로 나가지 않았음을 알린다
          // (새 상태 어휘를 만들지 않고 정본 라벨 "승인됨" 을 그대로 쓰고 전송이 별도임을 부연한다).
          dispatchable
            ? h("span", {
              className: "order-dispatch-note",
              "data-order-dispatch-note": order.id
            }, "승인됨 — 브로커 전송은 별도 단계입니다")
            : null),
        h("div", { className: "actions order-row-actions" },
          h("button", {
            type: "button",
            // D-30: 미지 side, D-42: 만료 제안은 오발주 방지를 위해 승인을 비활성화한다.
            // P2/BC-7: 거래중지·구분미확인 컨텍스트에서도 승인을 비활성화한다.
            disabled: busy || !sideKnown || !actionable || expired || blockApprove,
            onClick: () => onOrderAction(order.id, "approve")
          }, "승인 검토"),
          // 승인된 Live 주문을 브로커로 실제 전송한다(승인과 절대 한 버튼으로 합치지 않는다).
          dispatchable && onDispatch
            ? h("button", {
              type: "button", className: "primary order-dispatch-action",
              disabled: busy || !sideKnown || blockDispatch,
              "data-order-dispatch": order.id,
              onClick: () => onDispatch(order.id)
            }, "브로커로 전송")
            : null,
          modifiable && order.limitPrice != null && onModifyPrice
            ? h("button", {
              type: "button", className: "secondary",
              disabled: busy || !sideKnown || blockModify,
              onClick: () => {
                setModifyingId(isModifying ? null : order.id);
                setNewPriceInput(String(order.limitPrice ?? ""));
              }
            }, "정정")
            : null,
          h("button", {
            type: "button", className: "danger",
            disabled: busy || !sideKnown || !actionable || blockCancel,
            onClick: () => onOrderAction(order.id, "cancel")
          }, "주문 취소"))
      ),
      isModifying
        ? h("div", { className: "modify-panel", style: { marginTop: "12px", padding: "12px", background: "var(--panel-raised)", borderRadius: "var(--r-md)", display: "flex", gap: "12px", alignItems: "center" } },
          h("label", { style: { fontSize: "var(--fs-sm)", fontWeight: "var(--fw-bold)" } }, "정정 단가:"),
          h("input", {
            type: "number",
            step: "0.01",
            value: newPriceInput,
            onChange: (e) => setNewPriceInput(e.target.value),
            style: { width: "120px", padding: "6px 10px", borderRadius: "var(--r-sm)", border: "1px solid var(--line)" }
          }),
          h("button", {
            type: "button",
            disabled: busy || !newPriceInput || blockModify,
            onClick: () => {
              if (onModifyPrice && newPriceInput) {
                onModifyPrice(order.id, parseFloat(newPriceInput));
                setModifyingId(null);
              }
            }
          }, "정정 전송"),
          h("button", {
            type: "button",
            className: "secondary",
            onClick: () => setModifyingId(null)
          }, "닫기")
        )
        : null
    );
  }

  return h("section", { className: "panel orders-surface" },
    h("header", null,
      h("div", null,
        h("p", { className: "eyebrow" }, "주문 관제 · Toss Invest OpenAPI"),
        h("h1", null, "주문 목록 및 관리")),
      h("p", { className: "disclaimer" },
        "승인·정정·취소를 이 화면에서 관제합니다. 모든 매매 명령은 사전 위험 통제 엔진 및 step-up 인증을 경유합니다.")),
    h("div", { className: "order-tabs", style: { display: "flex", flexWrap: "wrap", gap: "8px", marginBottom: "16px" } },
      h("button", {
        type: "button",
        className: filterTab === "ALL" ? "primary" : "secondary",
        onClick: () => setFilterTab("ALL")
      }, `전체 (${contextOrders.length})`),
      h("button", {
        type: "button",
        className: filterTab === "OPEN" ? "primary" : "secondary",
        onClick: () => setFilterTab("OPEN")
      }, `진행 중 (${contextOrders.filter(o => OPEN_ORDER_STATUSES.has(o.status)).length})`),
      h("button", {
        type: "button",
        className: filterTab === "CLOSED" ? "primary" : "secondary",
        onClick: () => setFilterTab("CLOSED")
      }, `종료/내역 (${contextOrders.filter(o => CLOSED_ORDER_STATUSES.has(o.status)).length})`)
    ),
    // P2/BC-7: killSwitch.engaged===true 면 실행 계열을 전부 비활성화하고 사유를 노출한다.
    // engaged===null(미설정)·조회 실패는 tradingHalted=false 로 내려와 차단하지 않는다.
    tradingHalted
      ? h("p", { className: "error", role: "alert", "data-orders-halted": "true" },
        "거래 중지됨 · 내 계정 기준")
      : null,
    // 실거래 컨텍스트: 승인·전송·정정·취소는 가능하고, 승인과 브로커 전송이 별개 단계임을,
    // 그리고 주문 생성만 미지원임을 명시한다(실제 동작과 일치).
    contextIsLive
      ? h("p", { className: "disclaimer live-order-hint", "data-live-order-hint": "" },
        "실거래는 기존 주문의 승인·전송·정정·취소가 가능합니다 · 승인과 브로커 전송은 별개 단계이며, 주문 생성은 아직 지원하지 않습니다.")
      : null,
    section?.unavailable
      // D-27: 백엔드 코드(예: ORDERS_UNAVAILABLE)를 한국어 안내로 옮긴다. 미등록 코드만 원문 노출.
      ? h("p", { className: "empty" }, describeError(section.unavailableReason) ?? "주문 정보를 불러오지 못했습니다")
      : filteredOrders.length
        ? h("ul", { className: "list proposals" }, ...filteredOrders.map(order => renderOrderRow(order, {
          blockApprove: tradingHalted,
          blockCancel: tradingHalted,
          blockModify: tradingHalted,
          blockDispatch: tradingHalted
        })))
        : h("p", { className: "empty" }, "대기 중인 주문이 없습니다"),
    // 구분 미확인: executionMode 를 알 수 없는 주문은 별도로 보이되 실행 액션은 전부 비활성화한다.
    unknownOrders.length
      ? h("div", { className: "order-unknown-group", "data-order-unknown-group": "" },
        h("p", { className: "disclaimer" },
          "구분 미확인 — 실행 컨텍스트를 확인할 수 없어 실행 액션을 비활성화했습니다."),
        h("ul", { className: "list proposals" }, ...unknownOrders.map(order => renderOrderRow(order, {
          blockApprove: true, blockCancel: true, blockModify: true, blockDispatch: true
        }))))
      : null);
}
