export function oidcAuthorizationPath(registrationId) {
  if (!/^[A-Za-z0-9._-]+$/.test(registrationId)) {
    throw new Error("OIDC registration ID is invalid");
  }
  return `/oauth2/authorization/${registrationId}`;
}
