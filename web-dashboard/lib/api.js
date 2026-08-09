import {
  authorizedFetch,
  captureAccessTokenFromLocation,
  clearAccessToken
} from "./auth.js";

async function body(response) {
  if (response.ok) {
    return response.status === 204 ? null : response.json();
  }
  const error = new Error(`Request failed (${response.status})`);
  error.status = response.status;
  try {
    // D-16: 응답 본문 전체를 보존한다. display mismatch 스냅샷(serverQuantity/serverMaxLoss/currency)
    // 처럼 복구 판단에 필요한 값을 승인 화면이 다시 쓸 수 있어야 한다.
    const payload = await response.json();
    error.body = payload;
    if (payload?.code) {
      error.code = payload.code;
      error.message = payload.code;
    }
  } catch {
    // An upstream HTML/error response still carries the useful HTTP status.
  }
  if (typeof error.code === "string" && /_STEP_UP_REQUIRED$/.test(error.code)) {
    // D-02: step-up 재인증 요구를 별도 예외로 승격해 승인 화면이 재인증을 유도할 수 있게 한다.
    error.stepUpRequired = true;
    error.message = "재인증이 필요합니다. 본인 확인 후 다시 승인해 주세요.";
  } else if (error.code === "PAPER_ORDER_PROPOSAL_EXPIRED") {
    // D-42: 만료된 제안 승인은 409 로 거부된다. 재시도해도 성공하지 않는다.
    error.message = "이 주문 제안은 만료되어 승인할 수 없습니다.";
  } else if (error.status === 401) {
    // D-14: 인증 소실은 한국어 재로그인 안내로 노출한다.
    error.message = "세션이 만료되었습니다. 다시 로그인해 주세요.";
  }
  throw error;
}

export async function loadSession(fetcher = fetch) {
  captureAccessTokenFromLocation();
  const response = await authorizedFetch("/api/v1/session", {}, fetcher);
  return response.status === 401 ? null : body(response);
}

export async function loadDashboard(connectionId, fetcher = fetch) {
  const response = await authorizedFetch(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/dashboard`,
    {}, fetcher);
  return body(response);
}

function stockPath(prefix, symbol, suffix = "") {
  return `/api/v1/${prefix}/${encodeURIComponent(symbol)}${suffix}`;
}

export function loadStockAnalysis(symbol, fetcher = fetch) {
  return readEvent(stockPath("stock-analyses", symbol), fetcher);
}

export function loadStockAnalysisHistory(symbol, limit = 20, fetcher = fetch) {
  const query = limit ? `?limit=${encodeURIComponent(limit)}` : "";
  return readEvent(stockPath("stock-analyses", symbol, `/history${query}`), fetcher);
}

export function loadStockAnalysisRun(symbol, runId, fetcher = fetch) {
  return readEvent(
    stockPath("stock-analyses", symbol, `/runs/${encodeURIComponent(runId)}`), fetcher);
}

export function createStockAnalysis(symbol, command, fetcher = fetch) {
  return brokerCommand(stockPath("stock-analyses", symbol), "POST", command, fetcher);
}

export function loadStockForecast(symbol, runId = "", fetcher = fetch) {
  if (typeof runId === "function") {
    fetcher = runId;
    runId = "";
  }
  const suffix = runId ? `?runId=${encodeURIComponent(runId)}` : "";
  return readEvent(stockPath("stock-forecasts", symbol, suffix), fetcher);
}

export function createStockForecast(symbol, command, fetcher = fetch) {
  return brokerCommand(stockPath("stock-forecasts", symbol), "POST", command, fetcher);
}

export function loadStockAnalysisExplanation(symbol, runId = "", fetcher = fetch) {
  if (typeof runId === "function") {
    fetcher = runId;
    runId = "";
  }
  const suffix = runId ? `?runId=${encodeURIComponent(runId)}` : "";
  return readEvent(stockPath("stock-analysis-explanations", symbol, suffix), fetcher);
}

export function createStockAnalysisExplanation(symbol, fetcher = fetch) {
  return brokerCommand(stockPath("stock-analysis-explanations", symbol), "POST", null, fetcher);
}

export function loadOrderApprovalPreview(orderId, fetcher = fetch) {
  return readEvent(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/approval-preview`, fetcher);
}

export function loadPaperOrder(orderId, fetcher = fetch) {
  return readEvent(`/api/v1/paper-orders/${encodeURIComponent(orderId)}`, fetcher);
}

export function issueOrderStepUp(orderId, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/step-up`, "POST", null, fetcher);
}

// D-13: 같은 주문·동작이면 재시도마다 동일한 키를 재사용해 서버의 중복 감지가 동작하게 한다.
// 클릭마다 새 UUID 를 만들면 중복 제출이 서버에서 걸러지지 않는다.
export function orderActionKey(orderId, action) {
  return `paper-order:${action}:${orderId}`;
}

function normalizeActOptions(options) {
  // 하위 호환: 과거 시그니처는 3번째 인자로 idempotencyKey 문자열을 넘겼다.
  if (typeof options === "string") {
    return { idempotencyKey: options };
  }
  return options ?? {};
}

function isCompleteDisplay(displayed) {
  return Boolean(displayed)
    && displayed.quantity != null
    && displayed.maxLoss != null
    && displayed.currency != null
    && displayed.currency !== "";
}

// D-01: 승인은 반드시 사용자가 화면에서 확인한 값으로만 전송한다.
// 이 함수는 preview 를 스스로 조회하지 않으며, `displayed` 가 없으면 요청조차 보내지 않는다.
//   options = {
//     idempotencyKey?,                         // 없으면 orderActionKey(orderId, action)
//     displayed: { quantity, maxLoss, currency, proposalVersion }, // approve 필수
//     stepUpToken?                             // 없으면 여기서 step-up 을 발급
//   }
// 하위 호환: options 가 문자열이면 idempotencyKey 로 해석한다(cancel 경로에서만 유효).
export async function actOnProposal(orderId, action, options = {}, fetcher = fetch) {
  if (action !== "approve" && action !== "cancel") {
    throw new Error("Unsupported proposal action");
  }
  const opts = normalizeActOptions(options);
  const idempotencyKey = opts.idempotencyKey ?? orderActionKey(orderId, action);
  const headers = {
    "content-type": "application/json",
    "Idempotency-Key": idempotencyKey
  };
  let requestBody = { channel: "WEB" };
  if (action === "approve") {
    const displayed = opts.displayed;
    if (!isCompleteDisplay(displayed)) {
      // 화면에서 확인한 값이 없으면 승인 게이트를 우회하게 되므로 요청을 보내지 않는다.
      throw new Error("APPROVAL_DISPLAY_REQUIRED");
    }
    const stepUpToken = opts.stepUpToken
      ?? (await issueOrderStepUp(orderId, fetcher)).stepUpToken;
    if (stepUpToken) {
      headers["X-Step-Up-Token"] = stepUpToken;
    }
    requestBody = {
      channel: "WEB",
      displayedQuantity: displayed.quantity,
      displayedMaxLoss: displayed.maxLoss,
      displayedCurrency: displayed.currency,
      proposalVersion: displayed.proposalVersion ?? null
    };
  }
  const response = await authorizedFetch(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/${action}`,
    { method: "POST", headers, body: JSON.stringify(requestBody) },
    fetcher);
  return body(response);
}

export async function logout(fetcher = fetch) {
  const response = await authorizedFetch("/api/v1/auth/logout", {
    method: "POST",
    headers: {}
  }, fetcher);
  const result = await body(response);
  clearAccessToken();
  return result;
}

export async function logoutAll(fetcher = fetch) {
  const response = await authorizedFetch("/api/v1/auth/logout-all", {
    method: "POST",
    headers: {}
  }, fetcher);
  const result = await body(response);
  clearAccessToken();
  return result;
}

async function brokerCommand(
  path,
  method,
  payload,
  fetcher,
  extraHeaders = {}
) {
  const headers = {
    ...extraHeaders
  };
  if (payload) {
    headers["content-type"] = "application/json";
  }
  const response = await authorizedFetch(path, {
    method,
    headers,
    ...(payload ? { body: JSON.stringify(payload) } : {})
  }, fetcher);
  return body(response);
}

export function createBrokerConnection(credentials, fetcher = fetch) {
  return brokerCommand(
    "/api/v1/broker-connections/toss", "POST", credentials, fetcher);
}

export function replaceBrokerCredentials(
  connectionId,
  credentials,
  fetcher = fetch
) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/credentials`,
    "PUT",
    credentials,
    fetcher);
}

export function verifyBrokerConnection(connectionId, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/verify`,
    "POST",
    null,
    fetcher);
}

export function syncPortfolio(connectionId, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/portfolio-syncs`,
    "POST",
    null,
    fetcher);
}

export function analyzePortfolio(connectionId, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/portfolio-analyses`,
    "POST",
    null,
    fetcher);
}

export function deleteBrokerConnection(connectionId, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}`,
    "DELETE",
    null,
    fetcher);
}

export function createSingleFlight() {
  let active;
  return task => {
    if (active) {
      return active;
    }
    active = Promise.resolve(task()).finally(() => {
      active = undefined;
    });
    return active;
  };
}

function eventPath(connectionId, suffix = "") {
  return `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/events${suffix}`;
}

async function readEvent(path, fetcher) {
  return body(await authorizedFetch(path, {}, fetcher));
}

export function createEvent(connectionId, command, fetcher = fetch) {
  return brokerCommand(
    eventPath(connectionId), "POST", command, fetcher);
}

export function listEvents(connectionId, fetcher = fetch) {
  return readEvent(eventPath(connectionId), fetcher);
}

export function loadEvent(connectionId, eventId, fetcher = fetch) {
  return readEvent(
    eventPath(connectionId, `/${encodeURIComponent(eventId)}`), fetcher);
}

export function reanalyzeEvent(connectionId, eventId, fetcher = fetch) {
  return brokerCommand(
    eventPath(connectionId, `/${encodeURIComponent(eventId)}/reanalyze`),
    "POST",
    null,
    fetcher);
}

export function reviewEvent(
  connectionId,
  eventId,
  status,
  expectedVersion,
  idempotencyKey,
  fetcher = fetch
) {
  return brokerCommand(
    eventPath(connectionId, `/${encodeURIComponent(eventId)}/review`),
    "POST",
    { status, expectedVersion },
    fetcher,
    { "Idempotency-Key": idempotencyKey });
}

export function loadPortfolioHistory(
  connectionId,
  { from, to, maxPoints } = {},
  fetcher = fetch
) {
  const params = new URLSearchParams();
  if (from) {
    params.set("from", from);
  }
  if (to) {
    params.set("to", to);
  }
  if (maxPoints) {
    params.set("maxPoints", String(maxPoints));
  }
  const query = params.toString();
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/portfolio-history`
      + (query ? `?${query}` : ""),
    fetcher);
}

export function loadPaperPerformance(
  connectionId,
  { from, to, maxPoints } = {},
  fetcher = fetch
) {
  const params = new URLSearchParams();
  if (from) {
    params.set("from", from);
  }
  if (to) {
    params.set("to", to);
  }
  if (maxPoints) {
    params.set("maxPoints", String(maxPoints));
  }
  const query = params.toString();
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/paper-performance`
      + (query ? `?${query}` : ""),
    fetcher);
}

export function createAnalysisPrediction(connectionId, command, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/analysis-predictions`,
    "POST",
    command,
    fetcher);
}

export function loadAnalysisPredictions(
  connectionId,
  { from, to, modelVersion, contractVersion, symbol } = {},
  fetcher = fetch
) {
  const params = new URLSearchParams();
  if (from) {
    params.set("from", from);
  }
  if (to) {
    params.set("to", to);
  }
  if (modelVersion) {
    params.set("modelVersion", modelVersion);
  }
  if (contractVersion) {
    params.set("contractVersion", contractVersion);
  }
  if (symbol) {
    params.set("symbol", symbol);
  }
  const query = params.toString();
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/analysis-predictions`
      + (query ? `?${query}` : ""),
    fetcher);
}

export function loadPredictionModelVersions(fetcher = fetch) {
  return readEvent("/api/v1/prediction-model-versions", fetcher);
}

export function registerPredictionModelVersion(command, fetcher = fetch) {
  return brokerCommand(
    "/api/v1/prediction-model-versions", "POST", command, fetcher);
}

export function deprecatePredictionModelVersion(id, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/prediction-model-versions/${encodeURIComponent(id)}/deprecate`,
    "POST",
    null,
    fetcher);
}

export function deletePredictionModelVersion(id, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/prediction-model-versions/${encodeURIComponent(id)}`,
    "DELETE",
    null,
    fetcher);
}

export function loadPredictionIngestionApiKeys(fetcher = fetch) {
  return readEvent("/api/v1/prediction-ingestion-api-keys", fetcher);
}

export function issuePredictionIngestionApiKey(command, fetcher = fetch) {
  return brokerCommand(
    "/api/v1/prediction-ingestion-api-keys", "POST", command, fetcher);
}

export function rotatePredictionIngestionApiKey(id, command, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/prediction-ingestion-api-keys/${encodeURIComponent(id)}/rotate`,
    "POST",
    command,
    fetcher);
}

export function revokePredictionIngestionApiKey(id, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/prediction-ingestion-api-keys/${encodeURIComponent(id)}`,
    "DELETE",
    null,
    fetcher);
}

export function loadPredictionOperations(fetcher = fetch) {
  return readEvent("/api/v1/prediction-operations", fetcher);
}

export function loadOperationalReadiness(fetcher = fetch) {
  return readEvent("/api/v1/operations/readiness", fetcher);
}

export function runProviderReadinessCheck(symbol, fetcher = fetch) {
  return brokerCommand(
    "/api/v1/operations/readiness/provider-check", "POST", { symbol }, fetcher);
}

function notificationPath(suffix = "") {
  return `/api/v1/notifications${suffix}`;
}

export async function listNotifications(unreadOnly, limit, fetcher = fetch) {
  const params = new URLSearchParams();
  if (unreadOnly) {
    params.set("unreadOnly", "true");
  }
  if (limit) {
    params.set("limit", String(limit));
  }
  const query = params.toString();
  return readEvent(notificationPath(query ? `?${query}` : ""), fetcher);
}

export function loadUnreadCount(fetcher = fetch) {
  return readEvent(notificationPath("/unread-count"), fetcher);
}

export function markNotificationRead(notificationId, fetcher = fetch) {
  return brokerCommand(
    notificationPath(`/${encodeURIComponent(notificationId)}/read`),
    "POST",
    null,
    fetcher);
}

function riskPolicyPath(suffix = "") {
  return `/api/v1/risk-policy${suffix}`;
}

export function loadRiskPolicy(fetcher = fetch) {
  return readEvent(riskPolicyPath(), fetcher);
}

export function loadRiskPolicyHistory(limit, fetcher = fetch) {
  const params = new URLSearchParams();
  if (limit) {
    params.set("limit", String(limit));
  }
  const query = params.toString();
  return readEvent(riskPolicyPath(`/history${query ? `?${query}` : ""}`), fetcher);
}

export function updateRiskPolicy(input, fetcher = fetch) {
  return brokerCommand(riskPolicyPath(), "PUT", input, fetcher);
}

export function issueLiveOrderStepUp(orderId, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/live-orders/${encodeURIComponent(orderId)}/step-up`, "POST", null, fetcher);
}

export function liveOrderActionKey(orderId, action, value = "") {
  return `live-order:${action}:${orderId}:${value}`;
}

export async function modifyLiveOrder(orderId, newLimitPrice, stepUpToken, fetcher = fetch) {
  const headers = {
    "content-type": "application/json",
    "Idempotency-Key": liveOrderActionKey(orderId, "modify", newLimitPrice)
  };
  if (stepUpToken) {
    headers["X-Step-Up-Token"] = stepUpToken;
  }
  const response = await authorizedFetch(
    `/api/v1/live-orders/${encodeURIComponent(orderId)}/modify`,
    { method: "POST", headers, body: JSON.stringify({ newLimitPrice }) },
    fetcher);
  return body(response);
}

export function cancelPaperOrder(orderId, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/cancel`,
    "POST",
    { channel: "WEB" },
    fetcher
  );
}

export function loadOrderDetail(orderId, fetcher = fetch) {
  return readEvent(`/api/v1/paper-orders/${encodeURIComponent(orderId)}`, fetcher);
}

export function loadAccountBuyingPower(connectionId, currency = "USD", fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/buying-power?currency=${encodeURIComponent(currency)}`,
    fetcher
  );
}

export function loadRealtimePrices(connectionId, symbols = "AAPL,MSFT,NVDA,GOOGL,AMZN,TSLA", fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/prices?symbols=${encodeURIComponent(symbols)}`,
    fetcher
  );
}

export function loadOrderbook(connectionId, symbol, fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/orderbook?symbol=${encodeURIComponent(symbol)}`,
    fetcher
  );
}

export function loadCandles(connectionId, symbol, timeframe = "1d", fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/candles?symbol=${encodeURIComponent(symbol)}&timeframe=${encodeURIComponent(timeframe)}`,
    fetcher
  );
}

export function loadExchangeRate(connectionId, fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/exchange-rate`,
    fetcher
  );
}

export function loadMarketCalendar(connectionId, market = "US", fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/market-calendar/${encodeURIComponent(market)}`,
    fetcher
  );
}

export function loadStockWarnings(connectionId, symbol, fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/stocks/${encodeURIComponent(symbol)}/warnings`,
    fetcher
  );
}

export function loadInvestorTrading(connectionId, symbol, fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/stocks/${encodeURIComponent(symbol)}/investor-trading`,
    fetcher
  );
}

export function loadRankings(connectionId, category = "VOLUME", fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/rankings?category=${encodeURIComponent(category)}`,
    fetcher
  );
}

export function loadSellableQuantity(connectionId, symbol, fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/sellable-quantity?symbol=${encodeURIComponent(symbol)}`,
    fetcher
  );
}

export function loadCommissions(connectionId, symbol, side = "BUY", fetcher = fetch) {
  return readEvent(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/commissions?symbol=${encodeURIComponent(symbol)}&side=${encodeURIComponent(side)}`,
    fetcher
  );
}
