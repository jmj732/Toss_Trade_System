import { createElement as h } from "react";

import { RouteWorkspace } from "../route-workspace.js";

export default function PortfolioPage() {
  return h(RouteWorkspace, { route: "portfolio" });
}
