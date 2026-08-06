let accessToken = null;
let refreshPromise;
let authLostHandler = null;

// step-up 재인증 요구 코드. 액세스 토큰 만료와 구분해 refresh 를 소모하지 않는다.
const STEP_UP_CODE = /_STEP_UP_REQUIRED$/;

// D-14: refresh 로도 복구할 수 없는 인증 소실을 앱에 알린다.
// 앱은 이 신호로 세션을 비우고 재로그인 화면으로 복귀할 수 있다.
export function setAuthLostHandler(handler) {
  authLostHandler = typeof handler === "function" ? handler : null;
}

function notifyAuthLost() {
  try {
    authLostHandler?.();
  } catch {
    // 인증 소실 핸들러의 오류가 fetch 파이프라인을 깨뜨리지 않게 한다.
  }
  if (typeof globalThis.dispatchEvent === "function" && typeof Event === "function") {
    globalThis.dispatchEvent(new Event("trade:auth-lost"));
  }
}

// D-02: 401 응답 본문이 step-up 재인증 요구인지 확인한다. 본문을 소비하지 않도록 복제해 읽는다.
async function isStepUpChallenge(response) {
  try {
    const payload = await response.clone().json();
    return typeof payload?.code === "string" && STEP_UP_CODE.test(payload.code);
  } catch {
    return false;
  }
}

// D-37: 안전하게 재전송할 수 있는 요청만 자동 재시도한다.
// GET/HEAD, 또는 Idempotency-Key 를 실은 요청(서버가 중복을 흡수)만 해당한다.
function isReplaySafe(init) {
  const method = (init.method ?? "GET").toUpperCase();
  if (method === "GET" || method === "HEAD") {
    return true;
  }
  const headers = init.headers ?? {};
  return Object.keys(headers).some(name => name.toLowerCase() === "idempotency-key");
}

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
  // D-02: step-up 재인증 요구는 토큰 만료가 아니다. refresh 를 소모하지 않고 그대로 올려 보낸다.
  if (await isStepUpChallenge(first)) {
    return first;
  }
  try {
    await refreshAccessToken(fetcher);
  } catch {
    // D-14: refresh 실패는 인증 소실이다. 앱에 알리고 원본 401 을 돌려준다.
    notifyAuthLost();
    return first;
  }
  // D-37: 재전송이 안전한 요청만 재시도한다. 그 외에는 토큰만 갱신하고 원본 401 을 반환한다.
  return isReplaySafe(init) ? request(path, init) : first;

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
  authLostHandler = null;
}
