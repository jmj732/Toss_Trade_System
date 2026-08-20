// Adaptive Decision Center P0: 대시보드 상태를 단일 표면 상태로 축약한다.
// 프론트는 값을 새로 계산하지 않는다 — 서버가 준 enum/boolean/집합만 비교한다.
// action-model 을 import 하지 않는다(순환 방지). 호출부가 actions 를 주입한다.

export const SURFACE_STATES = ["BLOCKED", "CRITICAL", "RISK", "ACTIVE", "CALM"];

// UI 정책 상수 — 여기가 정본이다. action-model 이 이 창을 재사용한다.
export const URGENT_EXPIRY_WINDOW_MS = 900000; // 15분

const LABELS = {
  BLOCKED: "연결 필요",
  CRITICAL: "즉시 확인",
  RISK: "한도 초과",
  ACTIVE: "확인 필요",
  CALM: "확인할 결정 없음"
};

// 섹션 unavailable 은 데이터 파손이다. 단, 분석 미생성(ANALYSIS_RESULT_NOT_FOUND)은
// "아직 없음"이지 파손이 아니다(dashboard-view 의 Analysis 가 empty 로 처리).
function isBroken(section) {
  if (section?.unavailable !== true) {
    return false;
  }
  return section.unavailableReason !== "ANALYSIS_RESULT_NOT_FOUND";
}

export function resolveSurfaceState(input = {}) {
  const { connectionId, dashboard, actions, killSwitch } = input ?? {};

  const actionList = Array.isArray(actions) ? actions : [];
  const actionCount = actionList.length;
  const urgentCount = actionList.filter(action => action?.priority === "URGENT").length;

  const killSwitchEngaged = killSwitch?.engaged === true;

  // BC-4: riskEvaluation 은 backend dashboard read model 의 최상위 섹션이다.
  // breached boolean 만 읽는다. current/limit/usageRatio 로 산술하지 않는다.
  // 섹션 부재는 P0 정상 상태이므로 false. dataBroken 에는 포함하지 않는다.
  const riskItems = dashboard?.riskEvaluation?.data?.items;
  const riskBreached = Array.isArray(riskItems)
    && riskItems.some(item => item?.breached === true);

  const dataBroken = isBroken(dashboard?.portfolio) || isBroken(dashboard?.analysis);

  let state;
  if (!connectionId || !dashboard || dataBroken) {
    state = "BLOCKED";
  } else if (urgentCount >= 1 || killSwitchEngaged) {
    state = "CRITICAL";
  } else if (riskBreached) {
    state = "RISK";
  } else if (actionCount >= 1) {
    state = "ACTIVE";
  } else {
    state = "CALM";
  }

  return {
    state,
    urgentCount,
    actionCount,
    riskBreached,
    killSwitchEngaged,
    dataBroken,
    label: LABELS[state]
  };
}
