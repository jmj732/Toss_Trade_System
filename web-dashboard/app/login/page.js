import { createElement as h } from "react";

import { oidcAuthorizationUrl, safeReturnPath } from "../../lib/login.js";

const ERROR_MESSAGES = {
  access_denied: "로그인이 취소되었습니다. 계속하려면 다시 시도해 주세요.",
  state: "로그인 세션이 만료되었거나 잘못된 요청입니다. 다시 시도해 주세요.",
  session_expired: "세션이 만료되었습니다. 다시 로그인해 주세요.",
  login: "로그인에 실패했습니다. 잠시 후 다시 시도해 주세요."
};

function searchParam(searchParams, name) {
  if (searchParams instanceof URLSearchParams) {
    return searchParams.get(name) ?? "";
  }
  const value = searchParams?.[name];
  return Array.isArray(value) ? value[0] ?? "" : value ?? "";
}

export function loginErrorMessage(error) {
  return ERROR_MESSAGES[error] ?? "";
}

export function LoginPage({ searchParams = {} }) {
  const error = searchParam(searchParams, "error");
  const returnTo = safeReturnPath(searchParam(searchParams, "returnTo"));
  const authorizationUrl = oidcAuthorizationUrl(
    process.env.OIDC_REGISTRATION_ID ?? "oidc", returnTo);
  const message = loginErrorMessage(error);

  return h("main", { className: "center" },
    h("div", { className: "login-card" },
      h("p", { className: "eyebrow" }, "TRADE CONTROL"),
      h("h1", null, "로그인"),
      message ? h("p", { className: "login-error", role: "alert" }, message) : null,
      h("p", null, "계속하려면 로그인해 주세요."),
      h("a", { className: "button-link", href: authorizationUrl }, "로그인 다시 시도")));
}

export default async function LoginRoute({ searchParams }) {
  return LoginPage({ searchParams: await searchParams });
}
