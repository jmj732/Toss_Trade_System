import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

// The 2-step approval regression net.
//
// A prior build wired the "승인" button straight to
//   actOnProposal(orderId, action, crypto.randomUUID())
// which bypasses the display-confirmation gate (D-01): it submits an approval the
// user never saw the figures for. That defect sailed past the whole suite because
// nothing asserted the WIRING — only lib/api.js's own refusal was covered. These
// tests pin the surface (route-workspace.js) so the bypass cannot return.
//
// D-36 consolidated the two SPAs onto RouteWorkspace: app/page.js is now a thin mount
// of RouteWorkspace with route:"home" and holds no order logic of its own. The 2-step
// approval wiring therefore lives on a single surface, route-workspace.js, which is the
// only file that renders the order approval panel for every route (home included).

const root = new URL("../app/", import.meta.url);
const SURFACES = ["route-workspace.js"];

for (const file of SURFACES) {
  test(`${file}: approve opens the confirmation panel and never self-submits with a random key`, async () => {
    const source = await readFile(new URL(file, root), "utf8");

    // The exact bypass pattern must be gone: actOnProposal must never be fed a
    // freshly minted UUID (that path carries no user-confirmed `displayed`).
    assert.doesNotMatch(
      source,
      /actOnProposal\([^;]*crypto\.randomUUID\(\)/,
      "actOnProposal must not be called with a throwaway crypto.randomUUID() key");

    // The approve action routes through the preview panel instead of submitting.
    assert.match(source, /import \{ OrderApprovalPanel \}/);
    assert.match(source, /setApprovalOrder/);
    assert.match(source, /action === "approve"/);

    // The real submit path attaches the user-confirmed figures before calling the API.
    assert.match(source, /options\.displayed = displayed/);
    assert.match(source, /orderActionKey\(orderId, action\)/);
  });
}

test("neither surface passes a bare idempotency string to the approve path", async () => {
  // A string 3rd arg is the legacy cancel-only signature; approve needs the
  // { idempotencyKey, displayed } options object. runOrderCommand builds that object,
  // so the only actOnProposal call site must construct `options`, not a literal.
  for (const file of SURFACES) {
    const source = await readFile(new URL(file, root), "utf8");
    assert.match(
      source,
      /return actOnProposal\(orderId, action, options\)/,
      `${file} should submit through the shared options object`);
  }
});
