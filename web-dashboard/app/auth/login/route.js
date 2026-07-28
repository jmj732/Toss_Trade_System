import { NextResponse } from "next/server";

import { oidcAuthorizationPath } from "../../../lib/login.js";

export function GET(request) {
  const registrationId = process.env.OIDC_REGISTRATION_ID ?? "oidc";
  return NextResponse.redirect(new URL(oidcAuthorizationPath(registrationId), request.url));
}
