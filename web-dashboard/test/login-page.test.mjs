import assert from "node:assert/strict";
import test from "node:test";
import { renderToStaticMarkup } from "react-dom/server";

import { LoginPage } from "../app/login/page.js";
import { loginHref } from "../app/route-workspace.js";

test("renders access-denied state and retries through backend OAuth", () => {
  const html = renderToStaticMarkup(LoginPage({
    searchParams: { error: "access_denied", returnTo: "/portfolio" }
  }));

  assert.match(html, /로그인이 취소되었습니다/);
  assert.match(html, /href="\/oauth2\/authorization\/oidc\?returnTo=%2Fportfolio"/);
});

test("renders invalid or expired state as a retryable login error", () => {
  const html = renderToStaticMarkup(LoginPage({
    searchParams: { error: "state", returnTo: "/settings" }
  }));

  assert.match(html, /로그인 세션이 만료되었거나 잘못된 요청입니다/);
  assert.match(html, /href="\/oauth2\/authorization\/oidc\?returnTo=%2Fsettings"/);
});

test("session-expiry login links preserve the current dashboard route", () => {
  assert.equal(loginHref("portfolio"), "/login?returnTo=%2Fportfolio");
  assert.equal(loginHref("stock", "AAPL"), "/login?returnTo=%2Fstocks%2FAAPL");
});
