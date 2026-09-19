# MCP headless read-plane delta

## Decision deltas

- Keep the existing authenticated Toss connection as the sole account selector. Read tools take
  no account/link identifier and never request one interactively.
- Make `get_portfolio`, `get_orders`, `get_recent_fills`, and `get_order` available with
  `connector:read`; only `prepare_order`, `submit_order`, and `cancel_order` require
  `connector:trade`.
- Mark read tools as non-destructive, closed-world, read-only tools. Trade preparation remains
  non-read-only because it persists an auditable proposal; submit and cancel remain destructive.
- Return stable structured error values for broker, validation, safety-gate, and unexpected
  failures. Tool handlers never initiate user interaction.
- Support OAuth refresh-token rotation for the MCP connection. Refresh tokens are opaque,
  hashed at rest, bound to the registered MCP client, user, Toss connection, scope, and resource,
  and rejected after use or expiry. Access tokens remain short-lived.
- Preserve and validate the OAuth `resource` parameter when present. Missing `resource` remains
  backward-compatible by resolving to this MCP server's canonical resource.
- No change enables scheduled or autonomous broker orders. Read-only scheduled calls are the
  only target workflow.

## Verification boundary

- Unit tests lock tool visibility, annotations, headless structured errors, OAuth resource
  binding, refresh-token rotation, and refresh-client mismatch rejection.
- Backend clean verify, deployed MCP `tools/list`, authenticated `get_portfolio`, and a
  ChatGPT scheduled read-only run are required before completion.
