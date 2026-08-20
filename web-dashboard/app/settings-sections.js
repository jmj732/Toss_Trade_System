"use client";

import { createElement as h } from "react";

// Settings 를 5개 <details> 섹션으로 묶는 순수 프레젠테이션 컴포넌트. 상태·데이터 로딩은
// route-workspace.js 가 소유하며, 이 컴포넌트는 섹션을 펼칠 때 onExpand(key) 만 알린다.
// 접힌 섹션의 데이터는 펼치는 순간 한 번만 로드되게 하고(중복 로드는 route-workspace 가 방지),
// 진입 시 모든 섹션을 한꺼번에 요청하지 않는다.
function Section({ id, title, defaultOpen = false, onExpand, children }) {
  return h("details", {
    className: "settings-section",
    "data-settings-section": id,
    open: defaultOpen || undefined,
    // 펼칠 때만(닫힐 때는 무시) 로더를 호출한다. 이미 로드된 섹션은 route-workspace 가 재요청하지 않는다.
    onToggle: event => {
      if (event.currentTarget.open) {
        onExpand?.(id);
      }
    }
  },
    h("summary", null, title),
    h("div", { className: "settings-section-body" }, children));
}

export function SettingsSections({ account, risk, data, analysis, strategy, onExpand }) {
  return h("div", { className: "settings-sections", "data-route-region": "settings" },
    // 계좌만 기본 펼침. 나머지는 접힘 상태로 두고 펼칠 때 로드한다.
    h(Section, { id: "account", title: "계좌", defaultOpen: true, onExpand }, account),
    h(Section, { id: "risk", title: "위험", onExpand }, risk),
    h(Section, { id: "data", title: "데이터", onExpand }, data),
    h(Section, { id: "analysis", title: "분석·모델", onExpand }, analysis),
    h(Section, { id: "strategy", title: "전략", onExpand }, strategy));
}
