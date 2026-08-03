# Stock analysis product surface implementation plan

1. Add failing tests for stock API helpers, history endpoint contract, stock surface state
   rendering, route coverage, and order-surface safety boundary.
2. Add the minimal backend history, run-scoped forecast/explanation, and order approval-preview
   controller methods with frontend API helpers.
3. Add a shared route workspace shell with connection persistence/selection and independent
   route pages; leave the existing `/` SPA untouched.
4. Implement stock detail panels for analysis, forecast, Gemini explain, related events,
   provenance, missing data, generation/rerun, and snapshot history.
5. Run the targeted frontend/backend tests, perform one review pass, fix findings, then run
   the full required verification commands.
6. Commit with the repository Korean format, squash merge into
   `design/modular-monolith-architecture`, push, and report remote/CI evidence.
