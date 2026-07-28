import assert from "node:assert/strict";
import test from "node:test";

import { GET } from "../app/health/route.js";

test("health route reports dashboard ready", async () => {
  const response = await GET();

  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: "READY" });
});
