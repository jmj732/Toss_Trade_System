# fixdesign.md 프론트 재구성 delta

## 우선순위와 불변조건

`web-dashboard/fixdesign.md`를 이 작업의 최우선 UX 요구사항으로 적용한다. 기존 안전·주문·인증·데이터 provenance 계약은 유지한다.

- 주문 승인 전 서버 미리보기, 표시값 대조, step-up, idempotency, 상태 게이트를 유지한다.
- 브로커 연결 ID와 credential은 일반 사용자 UI에 노출하지 않는다. 내부 상태·저장소·API 경로에는 계속 사용한다.
- `data`, `asOf`, `stale`, `unknown`, `unavailable`, `provenance`가 없는 값은 임의 생성하지 않고 `미확인`/부분 상태로 표시한다.
- 기존 API 호출은 `web-dashboard/lib/api.js`를 통과시킨다.

## 현재 구현과의 delta

현재 `RouteWorkspace`가 모든 라우트의 상태와 shell을 소유하고, `DashboardView`, `StockAnalysisProductSurface`, `EventWorkflow`, `OrdersView`, `OrderApprovalPanel`이 이미 대부분의 데이터 표현을 제공한다. 따라서 새 페이지 체계나 API 전면 교체 대신 다음 최소 delta를 적용한다.

1. 공통 shell을 `GlobalAccountSwitcher`, stock search, market/data status, notifications, settings, logout로 재배치한다.
2. 기존 dashboard의 portfolio/proposal/event payload를 adapter로 합쳐 Home Decision Center와 Action Queue를 만든다.
3. 기존 stock surface의 summary/analysis/events/provenance를 Decision Header와 Position Plan으로 재배치하고, 실제 `POST /api/v1/paper-orders` 계약을 사용해 주문 초안을 서버 제안으로 전환한다.
4. 계좌 전환 UI가 실제 연결 목록을 필요로 하므로 `GET /api/v1/broker-connections`를 사용자 소유·삭제되지 않은 연결만 반환하는 최소 read contract로 추가한다. credential은 응답하지 않는다.
5. Portfolio는 기존 portfolio snapshot의 positions와 analysis weight를 사용한다. 서버 risk/decision/thesis/catalyst 값이 없으면 판정하지 않고 provenance 상태로 표시한다.
6. Events는 기존 event list/detail/review/reanalyze 계약을 Intelligence Feed/Impact Detail로 재배치한다. 수동 생성은 secondary workflow로 남긴다.
7. Predictions를 주 nav에서 제거하고 기존 운영 화면은 Settings/System의 secondary 영역으로 이동한다. Home의 chart/ranking은 핵심 decision 뒤의 보조 영역으로 둔다.

## 단계별 범위

### P0

- shared shell/account switcher/search/market-data state
- Home Decision Center + Action Queue: 실제 order proposal/event만 표시
- Stock Decision Header + Position Plan
- Orders Order Creation Panel: `POST /api/v1/paper-orders` → 기존 preview/step-up/approval 흐름
- stock/action queue에서 order route로 symbol/side/type/quantity/limitPrice를 전달

### P1

- Portfolio position table와 stock/order links
- available analysis weights와 server-provided risk fields만 사용하는 Portfolio Risk Panel
- Event Intelligence Feed, filter/detail/impact/stock/order/reanalyze/review links

### P2

- predictions main nav 제거
- model operations/API key/provider state를 Settings/System secondary section으로 이동
- Home low-priority market context를 접을 수 있는 보조 영역으로 정리

## 검증 기준

- 360/768/1280/1440에서 가로 overflow 없음
- loading/stale/partial/unavailable/empty/error/auth 상태와 provenance 유지
- unit, E2E, axe, visual, build, backend full verify 통과
- 독립 review에서 안전·계약·접근성 회귀 없음 확인
