# Delta Spec — Adaptive Decision Workspace

- 작성일: 2026-08-18
- 브랜치: `feat/adaptive-decision-workspace`
- 근거: `web-dashboard/fixdesign.md`, `web-dashboard/claudedocs/decision-workspace-redesign-plan.md`(UX 감사 + 3안 비교), `DESIGN.md`
- 채택안: **C — Adaptive Decision Center**

## 1. 무엇을 바꾸는가

기능 중심으로 흩어진 화면을 `상황 인지 → 판단 → 행동 → 주문 → 사후 확인` 흐름의 개인용 Decision Workspace로 재구성한다.
UI의 최소 단위는 종목·뉴스·주문이 아니라 **Action**이다.

레이아웃은 고정하지 않는다. surface 상태(`BLOCKED / CRITICAL / RISK / ACTIVE / CALM`)에 따라 각 컴포넌트의
weight(0 HIDDEN ~ 4 HERO)와 first-viewport 배치가 바뀐다.

## 2. 불변조건 (모든 단계 공통)

1. **프론트에서 판단값을 만들지 않는다.** 가격·확률·위험도·비중 사용률·판단(BUY/HOLD/SELL)을 프론트가 계산하지 않는다.
   허용 연산: 서버 enum 비교, 서버 timestamp와 now 비교, 서버 배열 교집합·개수, 서버 boolean 읽기.
2. 백엔드 계약이 없는 값은 **정직하게 숨기거나 unavailable 처리**한다. `UNKNOWN` 나열 금지, 0 대체 금지.
3. 기존 기능은 삭제하지 않는다. 홈의 랭킹·실시간 시세·독립 캔들은 **제거가 아니라 이동**이다(종목 화면 / 보조 market context).
4. 안전 경계 유지: step-up 인증, 서버 사전 위험 검사, 승인 게이트, `Idempotency-Key`, 감사 로그, 낙관적 동시성,
   만료 제안 승인 차단, 단일 실행(single-flight) 중복 제출 방지.
5. 데이터 품질 어휘 6종(`stale/unknown/unavailable/empty/partial/available`)과 주문 상태 13종 한국어 매핑은 정본 유지.
6. 접근성 회귀 금지: 4개 뷰포트(360/768/1280/1440) × light/dark 에서 가로 스크롤 0, axe violation 0.

## 3. 단계

### P0 — 상태머신 · Action 모델 · Home/Shell 재구성 (프론트 단독)

신규
- `lib/surface-state.js` — `resolveSurfaceState()`, `SURFACE_STATES`, `URGENT_EXPIRY_WINDOW_MS`
- `lib/action-model.js` — `buildActions()`, `ACTION_PRIORITIES`, `ACTION_TYPES`
- `app/home-decision-center.js` — 상태별 조립기

변경
- `app/decision-center.js` — `ActionQueue` 재작성(우선순위 배지·기한 카운트다운·서버 사실만). 계약 없는 필드 렌더 제거
- `app/route-workspace.js` — 홈 분기 교체, Shell 단일화(라우트별 계좌 연결 섹션 제거), 중복 마운트 제거
- `app/dashboard-view.js` — `homeLayout:"operations"` 경로 제거. 공용 export(`Quality`, 주문 상태 배지류)는 정본 유지
- `app/globals.css` — `.panel--hero`, `.panel--collapsed`, tabular numerals, 상태별 그리드

이동(삭제 아님)
- 홈 캔들 → 종목 화면 메인 차트
- 랭킹 · 실시간 시세 · 환율 · 시장 캘린더 → 홈 보조 market context(`<details>`, weight 0~1) 및 종목 화면

완료 조건
- 홈에서 값이 항상 `UNKNOWN`인 필드 0개
- Action 0건이면 포지션 중심으로 접히고, URGENT 1건이면 최상단 HERO로 승격됨을 e2e로 증명
- 같은 컴포넌트가 두 라우트에 동시 마운트되지 않음

### P1 — 백엔드 계약 (BC-2/3/4/6/7)

| ID | 계약 | 소유 |
|---|---|---|
| BC-2 | 포지션별 `decision`·`confidence`·`riskLevel`·`nextCatalystAt`·`nextCatalystType` | analysis-service + backend |
| BC-3 | 종목 `decision`·`confidence`·`positionPlan`·`thesis` | analysis-service + backend |
| BC-4 | 포트폴리오 `riskEvaluation{policyVersion, items[{key,current,limit,usageRatio,breached}]}` — **판정은 서버** | backend(risk) |
| BC-6 | `POST /api/v1/paper-orders/preview` 비영속 사전 위험 검사 + 주문 후 상태 | backend(order) |
| BC-7 | `GET /api/v1/trading/kill-switch` 상태 조회 | backend(order) |

계약 픽스처는 `contracts/analysis/` 아래에 버전을 올려 추가한다. 기존 버전 응답은 계속 파싱 가능해야 한다(필드는 nullable 추가).

### P2 — Portfolio · Events · Orders · Predictions 이동

- Portfolio → Position Management (BC-2 열 활성화, 위험 포지션 즉시 식별)
- Events → Intelligence Feed → Impact → Action (기존 `ComparisonView` 승격, 수동 등록은 최하단 접기)
- Orders → 단일 workspace, **Paper / Live 실행 컨텍스트 분리 탭**. Live는 step-up·risk·approval·idempotency·audit 전부 적용
- `/predictions` → `/settings#analysis` 리다이렉트, 기능은 Settings/Analysis·Model Operations로 보존 이동

### P3 — 밀도 · 접근성 · 성능 마감

- 360px 표 → 카드 리스트, 키보드 전 구간 도달, `aria-live` 표준화, 지연 로드, 문서 동기화

## 4. 검증

| 계층 | 도구 |
|---|---|
| 단위 | `node --test` (`web-dashboard/test/*.test.mjs`) |
| 백엔드 | `./mvnw clean verify` (Testcontainers Postgres) |
| analysis-service | pytest |
| 계약 | `contracts/analysis/**` 픽스처 기반 왕복 테스트 |
| E2E · 시각 · axe | `web-dashboard/e2e/state-matrix.spec.mjs` (route × state × 4 viewport × 2 scheme) |

각 단계는 독립 PR로 리뷰 후 squash merge 한다. `main` 직접 푸시 금지.
