import { createElement as h } from "react";

import { RouteWorkspace } from "../../route-workspace.js";

export default async function StockPage({ params }) {
  const value = await params;
  return h(RouteWorkspace, { route: "stock", symbol: value.symbol });
}
