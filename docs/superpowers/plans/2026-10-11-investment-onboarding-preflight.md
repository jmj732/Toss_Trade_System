# Investment onboarding preflight

## Target

Expose readiness and read-only risk preflight through connector MCP, plus an
idempotent candidate registration tool. Preserve strict argument validation,
existing constructor compatibility, and the distinction between read scope and
write scope. Preflight computes a risk view only; it never submits an order.

## Architecture boundary

MCP delegates readiness and risk calculation to the investment context services;
it validates and forwards inputs, then returns each service DTO unchanged. The
readiness view includes source timestamps, thesis receipt/eligibility fields,
risk-mark value/provenance, and full candidate records with their trigger and
formula inputs.
Candidate registration inserts into the authenticated user's existing
`monitoring_watchlist` and records the first `WATCHLIST` state in the existing
monitoring state history. Existing `InvestmentDataScheduler.captureAll` flows
use `captureSymbols` to include eligible watchlist statuses; MCP registration
does not start data collection or alpha research. No provider refresh, order
submission, or database schema migration is introduced.

## Scope

- `trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java`
- `trading-backend/src/main/java/com/jmj/trade/investment/InvestmentOnboardingService.java`
- `trading-backend/src/main/java/com/jmj/trade/investment/InvestmentOnboardingController.java`
- `trading-backend/src/main/java/com/jmj/trade/investment/InvestmentRiskPreflightService.java`
- `trading-backend/src/main/java/com/jmj/trade/investment/InvestmentRiskPreflightController.java`
- `trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringWatchlistService.java`
- `trading-backend/src/test/java/com/jmj/trade/connector/ConnectorMcpInvestmentOnboardingTest.java`
- `trading-backend/src/test/java/com/jmj/trade/security/InvestmentContextMcpSecurityIntegrationTest.java`
- `trading-backend/src/test/java/com/jmj/trade/investment/InvestmentOnboardingServiceTest.java`
- `trading-backend/src/test/java/com/jmj/trade/investment/InvestmentOnboardingControllerTest.java`
- `trading-backend/src/test/java/com/jmj/trade/investment/InvestmentOnboardingRegistrationIntegrationTest.java`
- `trading-backend/src/test/java/com/jmj/trade/investment/InvestmentRiskPreflightServiceTest.java`
- `trading-backend/src/test/java/com/jmj/trade/investment/InvestmentRiskPreflightControllerTest.java`
- Any narrow service-specific integration test added by the parallel lanes.
- `README.md`
- `docs/ops/investment-context-contract.md`
- This plan.

## Task checklist

- [x] Confirm API signatures and storage/scheduler path with service owners.
- [x] Write focused MCP protocol/security tests and capture expected red results.
- [x] Wire optional services without removing existing public constructors.
- [x] Publish readiness and risk preflight under read scope; publish candidate
  registration under existing write scope.
- [x] Enforce closed object schemas and validate ticker and positive decimal
  quantity before service dispatch; do not recompute service outputs.
- [ ] Verify exact structured/text serialization, null/status preservation,
  read/write scope separation, idempotent response pass-through, and zero order
  execution interactions.
- [x] Update README and contract docs with bounded operational claims.
- [ ] Run targeted protocol/security tests, backend suite if feasible, and
  `git diff --check` under Java 21 with the configured Colima Docker context.

## Acceptance

- `get_investment_onboarding_readiness(ticker)` and
  `preflight_investment_risk(ticker, quantity)` require read scope and carry
  read-only annotations; quantity meets service bounds (scale -18..10,
  at most 18 integer digits and 28 significant digits).
- `register_investment_candidate(ticker)` requires existing write scope, is
  idempotent, and does not trigger immediate alpha research or order activity.
- Unknown fields, missing required arguments, wrong JSON types, and invalid
  ticker/quantity values return sanitized `INVALID_ARGUMENT` errors before any
  service call.
- Existing constructors and tool discovery remain compatible; service absence
  returns the documented unavailable status.
- Documentation does not characterize readiness as approval or a buy
  recommendation, and does not claim that GET triggers provider activity.

## Validation evidence

Initial combined RED run: `65 tests, 28 failures, 0 errors, 0 skipped`; MCP
failures were the expected missing tool discovery/dispatch assertions. Log:
`/private/tmp/investment-preflight-targeted.log`. Record the green targeted run,
relevant suite results, Java version, Docker context, and `git diff --check`
before completion. No migrations are planned; verify that the final diff
contains none.
