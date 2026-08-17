# fixdesign.md 프론트 재구성 실행 계획

> **For the implementer:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task.

**목표:** `web-dashboard/fixdesign.md`의 P0→P1→P2 UX를 현재 API와 안전 불변조건을 보존한 최소 diff로 구현한다.

**전략:** `RouteWorkspace`를 orchestration owner로 유지하고, 새 adapter/view는 실제 envelope만 소비한다. 주문은 기존 paper-order proposal contract를 호출한 뒤 기존 approval panel에 연결한다. 계좌 switcher에 필요한 connection list만 backend에 추가한다.

## Task 1: 계약 고정과 실패 테스트

**Files:** `web-dashboard/test/api.test.mjs`, `web-dashboard/test/route-surface.test.mjs`, `web-dashboard/test/decision-center.test.mjs`, `web-dashboard/test/order-creation-panel.test.mjs`, `trading-backend/src/test/java/com/jmj/trade/broker/connection/BrokerConnectionControllerIntegrationTest.java`

- connection list API client, action adapter, global shell, order creation, P2 nav 이동 테스트를 먼저 추가한다.
- 각 새 테스트를 단독 실행해 기대 실패를 확인한다.
- 기존 approval/order tests를 수정하지 않고 safety gate를 고정한다.

## Task 2: P0 API adapter와 shared shell

**Files:** `web-dashboard/lib/api.js`, `trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionRepository.java`, `BrokerConnectionService.java`, `BrokerConnectionController.java`, `web-dashboard/app/route-workspace.js`, `web-dashboard/app/globals.css`

- user-owned non-deleted connection list read contract를 추가한다.
- account ID input을 일반 shell에서 제거하고 label-based switcher로 바꾼다.
- stock search와 market/data status를 공통 shell에 추가한다.
- P0 tests를 통과시킨다.

## Task 3: P0 Decision Center, stock decision, order creation

**Files:** `web-dashboard/app/decision-center.js`, `web-dashboard/app/order-creation-panel.js`, `web-dashboard/app/stock-analysis-product-surface.js`, `web-dashboard/app/route-workspace.js`, `web-dashboard/app/globals.css`

- dashboard pending proposals/events만 Action Queue에 adapter로 넣는다.
- stock surface 상단을 Decision Header/Position Plan으로 재배치한다.
- order form은 서버 proposal API에 idempotency key를 붙이고, 성공 후 existing approval flow를 연다.
- unknown values는 명시적으로 유지한다.

## Task 4: P1 Portfolio/Event

**Files:** `web-dashboard/app/decision-center.js`, `web-dashboard/app/event-workflow.js`, `web-dashboard/app/route-workspace.js`, `web-dashboard/app/globals.css`, 관련 tests

- existing position snapshot/weight를 table로 표현하고 symbol/order links를 제공한다.
- existing event list/detail workflow를 feed/detail layout으로 재배치한다.
- risk/thesis/catalyst는 server-provided 값만 표시하고 없으면 unavailable state를 표시한다.

## Task 5: P2 navigation/settings/home cleanup

**Files:** `web-dashboard/app/route-workspace.js`, `web-dashboard/app/prediction-operations-view.js`, `web-dashboard/app/globals.css`, 관련 tests

- predictions를 main nav에서 제거한다.
- 기존 prediction/model/API/provider 운영 surface를 Settings/System secondary area에 둔다.
- market context를 collapsible secondary region으로 둔다.

## Task 6: 검증과 독립 review

- unit/lint/build 실행
- Playwright E2E + axe + visual matrix 실행
- 360/768/1280/1440 screenshot에서 overflow/순서/포커스 점검
- backend `./mvnw clean verify`, analysis pytest, local smoke 가능한 범위 실행
- `code-reviewer` 독립 review 후 지적 수정 및 재검증
- feature branch에 범위만 Korean squash commit하고 origin branch push; main 직접 push는 하지 않는다.
