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
