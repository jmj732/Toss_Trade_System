let accessToken = null;
let refreshPromise;

export function setAccessToken(value) {
  accessToken = typeof value === "string" && value ? value : null;
  return accessToken;
}

export function getAccessToken() {
  return accessToken;
}

export function clearAccessToken() {
  accessToken = null;
}

export function captureAccessTokenFromLocation(
  location = globalThis.location,
  history = globalThis.history
) {
  if (!location) {
    return null;
  }
  const params = new URLSearchParams((location.hash ?? "").replace(/^#/, ""));
  const token = params.get("access_token");
  if (!token) {
    return null;
  }
  setAccessToken(token);
  const expiresAt = params.get("expires_at");
  location.hash = "";
  history?.replaceState?.(null, "", `${location.pathname}${location.search}`);
  return { accessToken: token, expiresAt };
}

export async function refreshAccessToken(fetcher = fetch) {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      const response = await fetcher("/api/v1/auth/refresh", {
        method: "POST",
        credentials: "same-origin",
        headers: { accept: "application/json" }
      });
      if (!response.ok) {
        clearAccessToken();
        const error = new Error(`Request failed (${response.status})`);
        error.status = response.status;
        throw error;
      }
      const value = await response.json();
      if (!value.accessToken) {
        clearAccessToken();
        throw new Error("AUTH_REFRESH_INVALID");
      }
      setAccessToken(value.accessToken);
      return value;
    })().finally(() => {
      refreshPromise = undefined;
    });
  }
  return refreshPromise;
}

export async function authorizedFetch(path, init = {}, fetcher = fetch) {
  const first = await request(path, init);
  if (first.status !== 401 || path.startsWith("/api/v1/auth/")) {
    return first;
  }
  try {
    await refreshAccessToken(fetcher);
  } catch {
    return first;
  }
  return request(path, init);

  async function request(url, options) {
    const headers = { ...(options.headers ?? {}) };
    if (accessToken) {
      headers.Authorization = `Bearer ${accessToken}`;
    }
    const requestOptions = { ...options, credentials: "same-origin" };
    if (Object.keys(headers).length > 0) {
      requestOptions.headers = headers;
    }
    return fetcher(url, requestOptions);
  }
}

export function resetAuthForTest(token = null) {
  accessToken = token;
  refreshPromise = undefined;
}
