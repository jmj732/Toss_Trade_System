const ERROR_MESSAGES = {
  SNAPSHOT_NOT_READY: "스냅샷이 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.",
  CONNECTION_REQUIRED: "먼저 계좌를 연결해 주세요.",
  ACTIVE_MODEL_VERSION_REQUIRED: "먼저 활성 모델 버전을 등록해 주세요.",
  ORDERS_UNAVAILABLE: "지금은 주문 정보를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.",
  ORDER_PROPOSALS_UNAVAILABLE: "주문 검토 정보를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.",
  ANALYSIS_UNAVAILABLE: "분석 정보를 불러올 수 없습니다. 잠시 후 다시 시도해 주세요.",
  PORTFOLIO_HISTORY_NOT_FOUND: "아직 기록된 포트폴리오 이력이 없습니다.",
  INTERNAL_ERROR: "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.",
  PAPER_ORDER_STEP_UP_REQUIRED: "보안 확인이 필요합니다. 다시 로그인한 뒤 시도해 주세요.",
  PAPER_ORDER_CONFLICT: "이미 처리 중인 주문입니다. 잠시 후 상태를 확인해 주세요.",
  RISK_POLICY_VERSION_CONFLICT: "다른 변경이 먼저 반영됐습니다. 최신 정책을 불러온 뒤 다시 시도해 주세요."
};

export function describeError(code) {
  if (!code) {
    return code;
  }
  return ERROR_MESSAGES[code] ?? code;
}
