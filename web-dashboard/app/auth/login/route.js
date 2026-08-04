import { oidcAuthorizationUrl } from "../../../lib/login.js";

export function GET(request) {
  const registrationId = process.env.OIDC_REGISTRATION_ID ?? "oidc";
  const returnTo = new URL(request.url).searchParams.get("returnTo");
  return Response.redirect(new URL(oidcAuthorizationUrl(registrationId, returnTo), request.url), 307);
}
