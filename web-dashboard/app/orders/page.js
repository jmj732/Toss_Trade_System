import { createElement as h } from "react";

import { RouteWorkspace } from "../route-workspace.js";

export default function OrdersPage() {
  return h(RouteWorkspace, { route: "orders" });
}
