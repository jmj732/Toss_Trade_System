// BC-6: PreTradeRiskEngine.Reason enum(13종) → 한국어 라벨.
// 미등록 코드는 은폐하지 않고 원문을 함께 노출한다(orderStatusLabel 과 동일 규약).
const RISK_REASON_LABELS = {
  STALE_SNAPSHOT: "포트폴리오 스냅샷이 오래됐습니다",
  PARTIAL_SNAPSHOT: "포트폴리오 스냅샷이 일부만 확인됐습니다",
  BUYING_POWER_EXCEEDED: "주문 가능 현금을 초과했습니다",
  MAX_ORDER_AMOUNT_EXCEEDED: "최대 주문 금액 한도를 초과했습니다",
  MAX_QUANTITY_EXCEEDED: "최대 주문 수량 한도를 초과했습니다",
  CONCENTRATION_EXCEEDED: "종목 집중도 한도를 초과했습니다",
  ACCOUNT_OWNERSHIP_MISMATCH: "계좌 소유자 정보가 일치하지 않습니다",
  SELLABLE_QUANTITY_UNKNOWN: "매도 가능 수량을 확인하지 못했습니다",
  SELLABLE_QUANTITY_INSUFFICIENT: "매도 가능 수량이 부족합니다",
  OPEN_ORDER_EXISTS: "이미 진행 중인 주문이 있습니다",
  KILL_SWITCH_ENGAGED: "거래가 중지된 상태입니다",
  KILL_SWITCH_STATE_UNAVAILABLE: "거래 중지 상태를 확인하지 못했습니다"
};

export function describeRiskReason(code) {
  if (!code) {
    return code;
  }
  return RISK_REASON_LABELS[code] ?? `알 수 없는 사유: ${code}`;
}
