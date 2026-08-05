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
    error.message = (await response.json()).code ?? error.message;
  } catch {
    // An upstream HTML/error response still carries the useful HTTP status.
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

export async function actOnProposal(
  orderId,
  action,
  idempotencyKey,
  fetcher = fetch
) {
  if (action !== "approve" && action !== "cancel") {
    throw new Error("Unsupported proposal action");
  }
  let approval = null;
  if (action === "approve") {
    const current = await loadPaperOrder(orderId, fetcher);
    const replay = current.commands?.some(command =>
      command.action === "APPROVE" && command.idempotencyKey === idempotencyKey);
    approval = replay
      ? {
        displayedQuantity: current.quantity ?? 0, displayedMaxLoss: 0,
        displayedCurrency: current.currency, proposalVersion: null
      }
      : { ...await loadOrderApprovalPreview(orderId, fetcher),
        ...(await issueOrderStepUp(orderId, fetcher)) };
  }
  const headers = {
    "content-type": "application/json",
    "Idempotency-Key": idempotencyKey
  };
  if (approval?.stepUpToken) {
    headers["X-Step-Up-Token"] = approval.stepUpToken;
  }
  const response = await authorizedFetch(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/${action}`,
    {
      method: "POST",
      headers,
      body: JSON.stringify(approval
        ? {
          channel: "WEB",
          displayedQuantity: approval.displayedQuantity,
          displayedMaxLoss: approval.displayedMaxLoss,
          displayedCurrency: approval.displayedCurrency,
          proposalVersion: approval.proposalVersion ?? null
        }
        : { channel: "WEB" })
    }, fetcher);
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
