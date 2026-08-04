import assert from "node:assert/strict";
import test from "node:test";

import { GET } from "../app/auth/login/route.js";
import { oidcAuthorizationPath, oidcAuthorizationUrl, safeReturnPath } from "../lib/login.js";

test("builds only a local OIDC authorization path", () => {
  assert.equal(oidcAuthorizationPath("mock-provider"),
    "/oauth2/authorization/mock-provider");
  assert.throws(() => oidcAuthorizationPath("https://evil.example"));
});

test("carries only a same-origin relative return path to backend authorization", () => {
  assert.equal(oidcAuthorizationUrl("oidc", "/portfolio?view=all"),
    "/oauth2/authorization/oidc?returnTo=%2Fportfolio%3Fview%3Dall");
  assert.equal(safeReturnPath("https://evil.example"), "/");
  assert.equal(safeReturnPath("//evil.example"), "/");
  assert.equal(safeReturnPath("/%2f%2fevil.example"), "/");
  assert.equal(safeReturnPath("/settings"), "/settings");
});

test("login entry redirects into the backend authorization endpoint", () => {
  const response = GET(new Request(
    "https://dashboard.example/auth/login?returnTo=%2Fportfolio"));

  assert.equal(response.status, 307);
  assert.equal(response.headers.get("location"),
    "https://dashboard.example/oauth2/authorization/oidc?returnTo=%2Fportfolio");
});
