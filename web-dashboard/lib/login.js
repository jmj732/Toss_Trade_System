export function oidcAuthorizationPath(registrationId) {
  if (!/^[A-Za-z0-9._-]+$/.test(registrationId)) {
    throw new Error("OIDC registration ID is invalid");
  }
  return `/oauth2/authorization/${registrationId}`;
}

export function safeReturnPath(value) {
  if (typeof value !== "string" || !value.trim()) {
    return "/";
  }
  const candidate = value.trim();
  if (!candidate.startsWith("/") || candidate.startsWith("//") || candidate.includes("\\")
      || /[\u0000-\u001f\u007f]/u.test(candidate)) {
    return "/";
  }
  try {
    const decoded = decodeURIComponent(candidate);
    if (decoded.startsWith("//") || decoded.includes("\\")) {
      return "/";
    }
    const parsed = new URL(candidate, "https://dashboard.invalid");
    if (parsed.origin !== "https://dashboard.invalid") {
      return "/";
    }
    return `${parsed.pathname || "/"}${parsed.search}`;
  } catch {
    return "/";
  }
}

export function oidcAuthorizationUrl(registrationId, returnTo) {
  const path = oidcAuthorizationPath(registrationId);
  if (returnTo === undefined || returnTo === null) {
    return path;
  }
  return `${path}?returnTo=${encodeURIComponent(safeReturnPath(returnTo))}`;
}
