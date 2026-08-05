import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";

import { OperationsReadinessView } from "../app/operations-readiness-view.js";

test("renders safe provider and safety readiness without secrets or values", () => {
  const html = renderToStaticMarkup(OperationsReadinessView({
    readiness: {
      status: "DEGRADED",
      canary: { status: "DISABLED" },
      killSwitch: { status: "NOT_REQUIRED" },
      dataFreshness: { status: "STALE", maxLagMs: 301000 },
      alerts: ["PROVIDER_FMP_STALE"],
      providers: [{
        provider: "FMP", status: "STALE", credentialConfigured: true,
        lagMs: 301000, missingData: []
      }]
    },
    onRefresh() {},
    onProbe() {}
  }));

  assert.match(html, /운영 준비 상태/);
  assert.match(html, /FMP/);
  assert.match(html, /5m 1s/);
  assert.doesNotMatch(html, /provider-secret|189\.40|raw-response/);
});
