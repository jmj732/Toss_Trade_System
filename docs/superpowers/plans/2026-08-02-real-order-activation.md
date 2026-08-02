# Real Order Activation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Toss OpenAPI 1.2.5 공식 주문 계약에 맞춘 fail-closed 실주문 activation 경로를 연결하고, 승인·재검증·계좌 매핑·멱등·reconciliation 안전 조건을 테스트로 고정한다.

**Architecture:** 기존 `BrokerOrderPort`와 `OrderSubmissionService`를 재사용한다. Toss client는 주문 write에 토큰 자동 재시도를 하지 않는다. live dispatch는 승인된 `OrderIntent` 전용 서비스로 두고 paper workflow와 분리한다. 계좌 매핑과 일일 한도는 작은 PostgreSQL ledger로 둔다.

**Tech Stack:** Java 21, Spring Boot 4.1, RestClient, PostgreSQL/Flyway, JUnit 5, WireMock, Testcontainers.

---

- [ ] Contract pin: 1.2.5 manifest, version test, exact request/response/status/client ID assertions.
- [ ] TDD: write failing adapter tests for create/detail/list pagination/modify/cancel and no-retry UNKNOWN behavior.
- [ ] Adapter: implement exact Toss 1.2.5 DTOs, headers, mappings, pagination, and no write retry.
- [ ] TDD: write failing live dispatch tests for approval, step-up, allowlist/account mapping, final gates, daily limits, mismatch/manual review, and paper isolation.
- [ ] Safety ledger: add allowlist and daily submitted amount constraints; wire max_age authorization parameter.
- [ ] Dispatch: connect approved intent to `BrokerOrderPort` and existing submission/reconciliation ledger without automatic resend.
- [ ] E2E: add opt-in WireMock order mappings and fault injection; run small allowlist happy path and UNKNOWN/manual-review path.
- [ ] Review: perform one code-review pass, fix findings, run targeted tests then `./mvnw clean verify` and local-stack checks.
- [ ] Delivery: squash merge into `design/modular-monolith-architecture`, push, and record CI/remote evidence.
