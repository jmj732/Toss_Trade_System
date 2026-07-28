# Broker Onboarding UI Delta Spec

- Extend the existing dashboard page; add no router, UI kit, or state library.
- Add internal `POST /api/v1/broker-connections/{id}/portfolio-syncs` for a
  user-triggered sync using the existing `AccountSyncService`.
- Keep analysis execution on the existing
  `POST /api/v1/broker-connections/{id}/portfolio-analyses`.
- Create, replace, verify, delete, sync, and analyze remain Spring-owned,
  CSRF-protected, ownership-checked commands.
- The UI uses the current connection UUID; a successful create selects its ID.
- Credential fields are uncontrolled password inputs with autocomplete off.
- Credentials exist only long enough to build one request, then the form resets.
- Never place credentials in React state, browser storage, URL, logs, errors,
  response views, or previously populated form values.
- One in-memory single-flight guard blocks duplicate mutations.
- While a mutation runs, all onboarding command buttons are disabled and the
  active action is displayed.
- UI errors display only backend public codes or HTTP status fallback.
- Delete clears the selected connection and dashboard after a 204 response.
- Successful sync or analysis reloads the existing dashboard read model.
- No credential read API, automatic sync, polling, or any order submission UI.
- Tests cover exact methods/paths/CSRF/bodies, duplicate blocking, secret-free
  rendering, sync ownership/error mapping, and production build.
