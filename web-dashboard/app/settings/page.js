import { createElement as h } from "react";

import { RouteWorkspace } from "../route-workspace.js";

export default function SettingsPage() {
  return h(RouteWorkspace, { route: "settings" });
}
