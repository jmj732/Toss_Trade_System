# Continuous Monitoring Implementation Plan

> **For agentic workers:** Use test-driven development task by task.

**Goal:** Run quiet, persistent, read-only monitoring for market, portfolio, material events and watchlist.

**Architecture:** FastAPI calculates deterministic risk from source-stamped input. Spring gathers existing owned state, persists transitions, schedules due checks and sends deduplicated notifications. PostgreSQL is the state and history store.

**Tech Stack:** Python 3.11/FastAPI, Java 21/Spring Boot, PostgreSQL/Flyway, existing HTTP clients; no new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-27-continuous-monitoring-delta.md`

## Global constraints

- Never call order prepare/submit/cancel or recurring LLM APIs.
- Do not coerce unknown or stale evidence to zero or declare price-only thesis invalidation.
- Persist evidence and observation time with state transitions; alert once per transition/source event.
- Use existing account, risk, event and notification infrastructure; keep changes reviewable.

## Review focus

- Partial axes: missing credit/funding inputs must not silently count as normal.
- A vulnerability spike without contagion must not emit market risk-off.
- Repeated runs and restart must not duplicate alerts.
- Untrusted news must not become a material event.
- Closed-market intraday ticks must not fetch prices.

## Tasks

### 1. FastAPI market engine

- [ ] Add fake-data tests for vulnerability-only, 1-axis, 2-axis with credit/funding, strong risk-off and P0; run red.
- [ ] Implement typed observations, velocity and four-axis contagion; run green.
- [ ] Add unknown/staleness and nominal-yield decomposition tests; run green.

### 2. FastAPI position, event and watchlist decisions

- [ ] Add tests for policy/combined exposure, explicit thesis evidence, official material event and joint price/volume/relative-strength watchlist gates; run red.
- [ ] Implement pure calculations and an internal monitoring evaluation endpoint; run green.

### 3. Spring state ownership

- [ ] Add Flyway schema and integration tests for timestamped evidence, unique source IDs, current state and history; run red then green.
- [ ] Add read-only monitoring coordinator using existing snapshots, risk policy and event feeds; implement due scheduling and closed-market skip; run green.

### 4. Alerts and delivery

- [ ] Test transition-only emission, restart deduplication and Telegram disabled/enabled behavior; run red.
- [ ] Reuse notification outbox, implement concise monitor rendering and configured Telegram delivery; run green.

### 5. Verification and docs

- [ ] Run focused and full relevant suites, lint/typecheck/build where present.
- [ ] Document provider credentials, schedules, UNKNOWN behavior and dry-run/fake-data operation.
