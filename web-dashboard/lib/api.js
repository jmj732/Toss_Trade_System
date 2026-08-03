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
  const response = await fetcher("/api/v1/session", { credentials: "same-origin" });
  return response.status === 401 ? null : body(response);
}

export async function loadDashboard(connectionId, fetcher = fetch) {
  const response = await fetcher(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/dashboard`,
    { credentials: "same-origin" });
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

export function createStockAnalysis(symbol, command, session, fetcher = fetch) {
  return brokerCommand(stockPath("stock-analyses", symbol), "POST", session, command, fetcher);
}

export function loadStockForecast(symbol, runId = "", fetcher = fetch) {
  if (typeof runId === "function") {
    fetcher = runId;
    runId = "";
  }
  const suffix = runId ? `?runId=${encodeURIComponent(runId)}` : "";
  return readEvent(stockPath("stock-forecasts", symbol, suffix), fetcher);
}

export function createStockForecast(symbol, command, session, fetcher = fetch) {
  return brokerCommand(stockPath("stock-forecasts", symbol), "POST", session, command, fetcher);
}

export function loadStockAnalysisExplanation(symbol, runId = "", fetcher = fetch) {
  if (typeof runId === "function") {
    fetcher = runId;
    runId = "";
  }
  const suffix = runId ? `?runId=${encodeURIComponent(runId)}` : "";
  return readEvent(stockPath("stock-analysis-explanations", symbol, suffix), fetcher);
}

export function createStockAnalysisExplanation(symbol, session, fetcher = fetch) {
  return brokerCommand(
    stockPath("stock-analysis-explanations", symbol), "POST", session, null, fetcher);
}

export function loadOrderApprovalPreview(orderId, fetcher = fetch) {
  return readEvent(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/approval-preview`, fetcher);
}

export function loadPaperOrder(orderId, fetcher = fetch) {
  return readEvent(`/api/v1/paper-orders/${encodeURIComponent(orderId)}`, fetcher);
}

export function issueOrderStepUp(orderId, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/step-up`, "POST", session, null, fetcher);
}

export async function actOnProposal(
  orderId,
  action,
  session,
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
        ...(await issueOrderStepUp(orderId, session, fetcher)) };
  }
  const headers = {
    "content-type": "application/json",
    "Idempotency-Key": idempotencyKey,
    [session.csrfHeaderName]: session.csrfToken
  };
  if (approval?.stepUpToken) {
    headers["X-Step-Up-Token"] = approval.stepUpToken;
  }
  const response = await fetcher(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/${action}`,
    {
      method: "POST",
      credentials: "same-origin",
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
    });
  return body(response);
}

export async function logout(session, fetcher = fetch) {
  const response = await fetcher("/logout", {
    method: "POST",
    credentials: "same-origin",
    headers: { [session.csrfHeaderName]: session.csrfToken }
  });
  return body(response);
}

async function brokerCommand(
  path,
  method,
  session,
  payload,
  fetcher,
  extraHeaders = {}
) {
  const headers = {
    ...extraHeaders,
    [session.csrfHeaderName]: session.csrfToken
  };
  if (payload) {
    headers["content-type"] = "application/json";
  }
  const response = await fetcher(path, {
    method,
    credentials: "same-origin",
    headers,
    ...(payload ? { body: JSON.stringify(payload) } : {})
  });
  return body(response);
}

export function createBrokerConnection(credentials, session, fetcher = fetch) {
  return brokerCommand(
    "/api/v1/broker-connections/toss", "POST", session, credentials, fetcher);
}

export function replaceBrokerCredentials(
  connectionId,
  credentials,
  session,
  fetcher = fetch
) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/credentials`,
    "PUT",
    session,
    credentials,
    fetcher);
}

export function verifyBrokerConnection(connectionId, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/verify`,
    "POST",
    session,
    null,
    fetcher);
}

export function syncPortfolio(connectionId, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/portfolio-syncs`,
    "POST",
    session,
    null,
    fetcher);
}

export function analyzePortfolio(connectionId, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/portfolio-analyses`,
    "POST",
    session,
    null,
    fetcher);
}

export function deleteBrokerConnection(connectionId, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}`,
    "DELETE",
    session,
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
  return body(await fetcher(path, { credentials: "same-origin" }));
}

export function createEvent(connectionId, command, session, fetcher = fetch) {
  return brokerCommand(
    eventPath(connectionId), "POST", session, command, fetcher);
}

export function listEvents(connectionId, fetcher = fetch) {
  return readEvent(eventPath(connectionId), fetcher);
}

export function loadEvent(connectionId, eventId, fetcher = fetch) {
  return readEvent(
    eventPath(connectionId, `/${encodeURIComponent(eventId)}`), fetcher);
}

export function reanalyzeEvent(connectionId, eventId, session, fetcher = fetch) {
  return brokerCommand(
    eventPath(connectionId, `/${encodeURIComponent(eventId)}/reanalyze`),
    "POST",
    session,
    null,
    fetcher);
}

export function reviewEvent(
  connectionId,
  eventId,
  status,
  expectedVersion,
  session,
  idempotencyKey,
  fetcher = fetch
) {
  return brokerCommand(
    eventPath(connectionId, `/${encodeURIComponent(eventId)}/review`),
    "POST",
    session,
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

export function createAnalysisPrediction(connectionId, command, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/broker-connections/${encodeURIComponent(connectionId)}/analysis-predictions`,
    "POST",
    session,
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

export function registerPredictionModelVersion(command, session, fetcher = fetch) {
  return brokerCommand(
    "/api/v1/prediction-model-versions", "POST", session, command, fetcher);
}

export function deprecatePredictionModelVersion(id, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/prediction-model-versions/${encodeURIComponent(id)}/deprecate`,
    "POST",
    session,
    null,
    fetcher);
}

export function deletePredictionModelVersion(id, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/prediction-model-versions/${encodeURIComponent(id)}`,
    "DELETE",
    session,
    null,
    fetcher);
}

export function loadPredictionIngestionApiKeys(fetcher = fetch) {
  return readEvent("/api/v1/prediction-ingestion-api-keys", fetcher);
}

export function issuePredictionIngestionApiKey(command, session, fetcher = fetch) {
  return brokerCommand(
    "/api/v1/prediction-ingestion-api-keys", "POST", session, command, fetcher);
}

export function rotatePredictionIngestionApiKey(id, command, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/prediction-ingestion-api-keys/${encodeURIComponent(id)}/rotate`,
    "POST",
    session,
    command,
    fetcher);
}

export function revokePredictionIngestionApiKey(id, session, fetcher = fetch) {
  return brokerCommand(
    `/api/v1/prediction-ingestion-api-keys/${encodeURIComponent(id)}`,
    "DELETE",
    session,
    null,
    fetcher);
}

export function loadPredictionOperations(fetcher = fetch) {
  return readEvent("/api/v1/prediction-operations", fetcher);
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

export function markNotificationRead(notificationId, session, fetcher = fetch) {
  return brokerCommand(
    notificationPath(`/${encodeURIComponent(notificationId)}/read`),
    "POST",
    session,
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

export function updateRiskPolicy(input, session, fetcher = fetch) {
  return brokerCommand(riskPolicyPath(), "PUT", session, input, fetcher);
}
