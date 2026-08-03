import { createElement as h } from "react";

import { RouteWorkspace } from "../route-workspace.js";

export default function PredictionsPage() {
  return h(RouteWorkspace, { route: "predictions" });
}
