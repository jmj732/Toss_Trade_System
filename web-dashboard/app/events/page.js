import { createElement as h } from "react";

import { RouteWorkspace } from "../route-workspace.js";

export default function EventsPage() {
  return h(RouteWorkspace, { route: "events" });
}
