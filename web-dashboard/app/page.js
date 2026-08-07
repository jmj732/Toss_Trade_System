import { createElement as h } from "react";

import { RouteWorkspace } from "./route-workspace.js";

// D-36: 홈(/)도 다른 6개 라우트처럼 공유 RouteWorkspace 를 얇게 마운트한다.
// 안전 게이트(단일 실행 mutation, 삭제 확인, aria-live, 부분 성공 로딩)는 모두 공유 구현에 있다.
export default function Home() {
  return h(RouteWorkspace, { route: "home" });
}
