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
  const response = await fetcher(
    `/api/v1/paper-orders/${encodeURIComponent(orderId)}/${action}`,
    {
      method: "POST",
      credentials: "same-origin",
      headers: {
        "content-type": "application/json",
        "Idempotency-Key": idempotencyKey,
        [session.csrfHeaderName]: session.csrfToken
      },
      body: JSON.stringify({ channel: "WEB" })
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
