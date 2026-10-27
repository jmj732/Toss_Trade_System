# Position Mandate Policy Implementation Plan

> **For agentic workers:** Implement task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Persist user-confirmed ticker horizons, make decision records distinguish price-risk states from thesis health, and prepare prompt updates using the confirmed policy.

**Architecture:** Add a user-scoped current-state PostgreSQL table and expose it through `InvestmentContextService` and the existing trade-scoped MCP write path. Add a pure risk-response policy that emits review/evaluation states, never broker actions. Prepare copy-on-write prompt drafts because synced project sources and live Scheduled Task settings are outside this repository.

**Tech Stack:** Java 21, Spring Boot, PostgreSQL/Flyway, Jackson MCP protocol, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-10-27-position-mandate-policy.md`

## Global Constraints

- TACTICAL means a 20–60 trading-day analysis horizon; STRATEGIC means 6–18 months; neither is a forced sale deadline.
- `priceRiskTriggerPrice` remains separate from PositionMandate and is never lowered automatically.
- User identity comes from the authenticated request; stored source is server-set to `USER_CONFIRMED`.
- A price-risk event does not prepare or submit an order; existing approval gates remain.
- Do not edit synced `sources/` files or claim local drafts changed live ChatGPT task configuration.

## Review Focus

- Cross-user read/write isolation — test two authenticated user IDs cannot see or overwrite each other's mandates.
- Horizon/role mismatch — reject invalid pairings and derive the role server-side.
- Missing mandate — return no fabricated row and preserve the prompt fallback/UNKNOWN contract.
- Strategic price breach with intact thesis — assert revalidation, never automatic reduction.
- Hard risk — assert urgent review regardless of investment horizon.

---

### Task 1: Persist and read PositionMandate

**Files:**
- Create: `trading-backend/src/main/resources/db/migration/V65__create_investment_position_mandates.sql`
- Modify: `trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java`
- Modify: `trading-backend/src/test/java/com/jmj/trade/investment/InvestmentContextMcpIntegrationTest.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/connector/ConnectorMcpProtocolTest.java`

**Interfaces:**
- Produces: `PositionMandateView(ticker, investmentHorizon, riskPriceRole, source, effectiveDate, updatedAt)` and a user-scoped `upsertPositionMandate(userId, ticker, input)`.
- MCP write: `upsert_position_mandate` requires `connector:trade`; `get_investment_context` remains read-only and includes the authenticated user's mandate list.

- [x] Add `positionMandateMcpWritePersistsAndContextReturnsOnlyTheAuthenticatedUsersPolicy`: persist TACTICAL/STRATEGIC rows for two users, assert the authenticated user's context contains only that user's row, and assert source/role/effectiveDate/updatedAt.
- [x] Add `listsAndWritesPositionMandateOnlyWithTradeScope`: assert read scope cannot list/write the tool; trade scope can write; source and role are server-derived.
- [x] Run the new tests and confirm failure before implementation (RED reached compilation with missing mandate contract symbols).
- [x] Attempt integration validation; Docker/Testcontainers is unavailable in this environment, so the PostgreSQL test class cannot initialize.
- [x] Add the V65 current-state table keyed by `(user_id, ticker)` with horizon/role constraints.
- [x] Implement the service read/upsert contract; bind source and user ID server-side.
- [x] Add the context output and trade-scoped MCP tool schema/dispatch.
- [x] Run `./mvnw -Dtest=PositionMandatePolicyTest,ConnectorMcpProtocolTest test` from `trading-backend`; 55 tests pass after Task 2 changes.

### Task 2: Record decision context and resolve price-risk signals

**Files:**
- Create: `trading-backend/src/main/resources/db/migration/V66__add_position_context_to_decisions.sql`
- Create: `trading-backend/src/main/java/com/jmj/trade/investment/PositionMandatePolicy.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java`
- Modify: `trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/investment/PositionMandatePolicyTest.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/investment/InvestmentDecisionPositionContextIntegrationTest.java`
- Test: `trading-backend/src/test/java/com/jmj/trade/connector/ConnectorMcpProtocolTest.java`

**Interfaces:**
- Decision fields: `investmentHorizon`, `priceRiskState`, `thesisHealth`, and `actionReason`.
- Risk response: `NO_PRICE_ACTION`, `EXIT_EVALUATION`, `THESIS_REVALIDATION`, or `URGENT_REVIEW`; no response creates a broker order.

- [x] Add `PositionMandatePolicyTest` cases: tactical breach → EXIT_EVALUATION; strategic intact breach → THESIS_REVALIDATION; strategic impaired breach → EXIT_EVALUATION; unknown mandate breach → MANDATE_REVIEW; hard risk → URGENT_REVIEW regardless of horizon; no confirmed breach → NO_PRICE_ACTION; invalidated thesis → URGENT_REVIEW.
- [x] Extend `mcpDecisionAppendPersistsTheSameUserRecordReturnedByContext` to assert that all four new fields survive persistence and idempotent replay.
- [x] Compile all 205 test sources and run the unit/protocol target: 55 tests pass.
- [x] Add V66 nullable columns for historical compatibility; persist caller-authored fields without inferring them.
- [x] Implement the pure response resolver and decision row/schema mapping.
- [ ] Run the PostgreSQL-backed integration class and the full module suite when Docker is available; integration attempt was blocked before tests ran.

### Task 3: Prepare common playbook and five schedule drafts

**Files:**
- Create: `docs/ops/position-mandate-prompts/README.md`
- Create: `docs/ops/position-mandate-prompts/00_PORTFOLIO_MANAGER.md`
- Create: `docs/ops/position-mandate-prompts/03_LONG_TERM.md`
- Create: `docs/ops/position-mandate-prompts/04_POSITION.md`
- Create: `docs/ops/position-mandate-prompts/05_SELL.md`
- Create: `docs/ops/position-mandate-prompts/09_PORTFOLIO_REVIEW.md`
- Create: five files for MORNING, PRE-TRADE, OPENING, ORDER MANAGER, and WEEKLY+MONTHLY schedule addenda.

- [x] Read current synced project sources and current reservation-version pages as read-only inputs.
- [x] Copy the five named Playbook sources into the repository draft folder; edit only the copies.
- [x] Add the common policy once in the Portfolio Manager and horizon-specific sections to MODEs 03/04/05/09.
- [x] Create schedule addenda that preserve current times and separate price-risk events from decisions.
- [x] Verify all six ticker mappings, fallback/UNKNOWN behavior, hard-risk override, and explicit no-auto-order boundary appear in the drafts.
- [x] Report that live Scheduled Task configuration and synced source files remain untouched; addenda are ready for manual application.

### Task 4: Update operating contracts

**Files:**
- Modify: `docs/ops/investment-context-mcp-contract.md`
- Modify: `docs/ops/connector-runbook.md`
- Modify: `docs/ops/investment-context-contract.md`

- [x] Document V65/V66, user scope, MCP write scope, missing-data behavior, and decision fields.
- [x] Document the review-only behavior for strategic price-risk breaches and unchanged approval boundary.
- [x] Verify whitespace and patch integrity with `git diff --check`.
