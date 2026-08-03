import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const root = new URL("../app/", import.meta.url);

test("publishes independent App Router surfaces", async () => {
  for (const route of ["portfolio", "events", "orders", "predictions", "settings"]) {
    const source = await readFile(new URL(`${route}/page.js`, root), "utf8");
    assert.match(source, /RouteWorkspace/);
  }
  const stock = await readFile(new URL("stocks/[symbol]/page.js", root), "utf8");
  assert.match(stock, /RouteWorkspace/);
  assert.match(stock, /symbol/);
});
