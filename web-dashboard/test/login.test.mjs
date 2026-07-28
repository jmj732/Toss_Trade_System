import assert from "node:assert/strict";
import test from "node:test";

import { oidcAuthorizationPath } from "../lib/login.js";

test("builds only a local OIDC authorization path", () => {
  assert.equal(oidcAuthorizationPath("mock-provider"),
    "/oauth2/authorization/mock-provider");
  assert.throws(() => oidcAuthorizationPath("https://evil.example"));
});
