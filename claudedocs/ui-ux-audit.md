# UI/UX 감사 보고서 — web-dashboard

- 브랜치: `chore/ui-ux-audit-and-recovery`
- 감사일: 2026-08-05
- 기준선: `DESIGN.md` (WCAG 2.2 AA, spacing 8/12/24, radius 12/16–20, reduced-motion, 6상태 variants)
- 대상: Next.js 16 App Router / React 19 / `createElement` (JSX 없음) / 단일 `app/globals.css` 450줄
- 감사 범위: 전 route 8개 × 상태 6종(loading·empty·partial·stale·error·unauthorized) × 뷰포트 4종(360·768·1280·1440)

## 심각도 정의

| 등급 | 정의 |
|---|---|
| P0 | 화면 불능, 콘텐츠 접근 불가, 잘못된 거래 판단·금전 손실 유발 |
| P1 | 핵심 여정(로그인·분석·주문·오류복구) 차단, 접근성 차단(대비 미달·키보드 불가), 심각한 오인 소지 |
| P2 | 일관성·이해도 저하 |
| P3 | 다듬기 |

---

## 1. 시각·레이아웃·접근성 (정적 감사)

### P0

| # | route/component | 뷰포트 | file:line | 문제 | 재현 | 수정 방향 |
|---|---|---|---|---|---|---|
| V-01 | `/` NotificationCenter | 360, 768 | `app/globals.css:446` + `:279` | `@media` 가 `left:0;right:0;width:auto` 를 주지만 containing block 이 `.notification-center{position:relative}` = 토글 버튼(≈103px). viewport 가 아니라 **버튼 폭**으로 축소 → `.panel` padding 24×2 제외 시 콘텐츠 폭 **≈55px**. 알림 목록 판독 불가 | 360px → 로그인 → `/` → "알림" 클릭 | `.notification-center` `position:static`, ≤760 에서 `position:fixed; inset-inline:16px` 또는 전체폭 시트 |
| V-02 | `/` RiskPolicyPanel | 360, 768 | `app/globals.css:446` + `:315` | 동일 원인. 토글 버튼 ≈134px → 폼 콘텐츠 폭 ≈86px. label 4개("최대 종목 집중도 (0–1)") + `input[type=number]` 진입 불가 | 360px → `/` → "리스크 정책" 클릭 | 동일 |

### P1

| # | route/component | 뷰포트 | file:line | 문제 | 재현 | 수정 방향 |
|---|---|---|---|---|---|---|
| V-03 | 전 route (dashboard hero) | 전부 | `globals.css:349` vs `:173`/`:174` | `.metric-value` **이중 선언**. 특이도 동일(0-1-0) → 소스 순서상 349(`1rem`)가 174 `.metric-value-large{clamp(2rem,5vw,2.75rem)}` 를 덮음. `dashboard-view.js:40` 총 평가금액이 44px 대신 **16px** 렌더 | `/` → "총 평가금액" 크기 확인 | 349행 삭제, override 블록 병합 |
| V-04 | 전 route | 전부 | `globals.css:348` vs `:172` | `.metric-label` 이중 선언 → 348(`.72rem`=11.5px)이 172(`.875rem`=14px) 덮음. 같은 규칙의 `.portfolio-hero-secondary span` 은 14px 유지 → **주 지표 라벨 11.5px, 부 지표 라벨 14px** 위계 역전 | `/` → "총 평가금액" vs "총 손익" | 348행 삭제 |
| V-05 | 전 route 모든 인터랙티브 | 전부 | `globals.css:13`, `:38-41` | `outline:0` 으로 기본 포커스링 제거 후 `--focus: 0 0 0 3px rgba(49,130,246,.18)` 대체. 흰 패널 위 **1.24:1**, `--bg` 위 **1.23:1** → WCAG 2.4.11 (3:1) 미달. 키보드 포커스 위치 식별 사실상 불가 | Tab 으로 topbar → route-nav → connection form | `--focus: 0 0 0 2px var(--surface-card), 0 0 0 4px #1b4fb8` 불투명 2겹. 알파 링 금지 |
| V-06 | 전 route 라벨 | 전부 | `globals.css:131`, `:124`, `:140` | `--muted #6b7684` on `#f7f8fa` = **4.34:1** (4.5 미달). `dashboard-view.js:46,48,53,88,99,112` 등 전 라벨 해당 | `/` → 요약 타일 라벨 | `--text-muted #5a6472` (흰 5.6:1 / sunken 5.3:1) |
| V-07 | `/` 랜딩 | 전부 | `globals.css:216` | `.landing-copy p:last-child` `--muted` on `--bg` = **4.31:1** | 로그아웃 상태 `/` | 동일 |
| V-08 | `/` 랜딩 | 전부 | `globals.css:214` | `.landing-kicker` `--accent #3182f6` on `#f6f7f9` = **3.46:1** | 로그아웃 상태 `/` | `--accent-text #1b4fb8` (6.4:1) 분리 |
| V-09 | 모든 패널 (14곳) | 전부 | `globals.css:60` | `.eyebrow` `--accent` 11px, 흰 패널 위 **3.71:1** | 아무 패널 헤더 | `--accent-text` |
| V-10 | BrokerOnboarding / PortfolioHistory | 전부 | `globals.css:221` | `.busy{color:var(--warning)}` `#f59f00` on 흰색 = **2.13:1**. 비동기 진행 문구 판독 불가 | `/` → "연결 확인" → `broker-onboarding.js:56` | `--warning-text #8a5200` (5.6:1) |
| V-11 | `/settings` OperationsReadiness | 전부 | `globals.css:406` | `.readiness-alerts` `--warning` = **2.13:1**. 경보 텍스트 | `/settings` alerts 발생 시 | 동일 |
| V-12 | `/stocks/[symbol]` | 전부 | `globals.css:393`, `:395` | `.surface-state.progress` / `.degraded` `--warning` 11px = **2.13:1**. 추가로 두 상태 **스타일 완전 동일** → 구분 불가 | `/stocks/AAPL` PROGRESS → DEGRADED | `--warning-text` + degraded 는 배경 채움/아이콘 형태 차이 |
| V-13 | BrokerOnboarding 삭제 | 전부 | `globals.css:44` | `button.danger` = **3.36:1**. 파괴적 액션이 최저 대비 | `/` → "연결 삭제" | `color:#b3202c` (6.0:1) |
| V-14 | `/` quality 배지 | 전부 | `globals.css:114` | `.quality .stale,.unknown{color:#b26a00}` 11px = **4.24:1**. stale/unknown **동일 스타일** | `dashboard-view.js:5-7` | `#8a5200` + 두 상태 시각 구분 |
| V-15 | `/` connection-picker | 전부 | `globals.css:241` | `summary` `--muted` on `--bg` **4.31:1**. 터치타깃 높이 14px×1.68 ≈ **24px** (44px 미달) | 랜딩 "기존 연결 불러오기" | `padding:12px 16px; min-height:44px` + 색 상향 |
| V-16 | `/portfolio` 추세 차트 | 전부 | `globals.css:308-314`, `portfolio-history-view.js:49-59` | KRW/USD 두 선이 **색만으로** 구분. 선 상호 대비 **1.74:1**, warning 선 vs 배경 **1.94:1** (1.4.11 미달). SVG 에 `role="img"`/`aria-label`/`<title>` 없음 | `/portfolio` 추세 차트 | `stroke-dasharray` 형태 구분 + `<title>`. warning 을 선 색으로 사용 금지 |
| V-17 | 모든 표 (9~11열) | 360, 768, 1280 | `globals.css:133`, `:135` | `.table-wrap{overflow-x:auto}` + `th,td{white-space:nowrap}` 인데 `tabindex="0"`·`role="region"`·`aria-label` 없음 → **키보드로 가로 스크롤 불가** (WCAG 2.1.1). `event-workflow.js:19,44`(11열), `analysis-outcome-view.js:87,107`(9·11열), `prediction-operations-view.js:99`(7열) | 768px `/events` → Tab 만으로 우측 열 도달 시도 | `.table-wrap` 에 `tabindex="0" role="region" aria-label` |
| V-18 | `/predictions` | 전부 | `analysis-outcome-view.js:135`, `:248` | 다른 6곳은 `role:"alert"` 인데 이 두 곳만 없음 → 실패가 스크린리더에 미고지 | `/predictions` 중복 버전 등록 | `role:"alert"` 추가 |
| V-19 | 전 route | 전부 | `route-workspace.js:501-532`, `page.js:212-270` | 비동기 갱신용 `aria-live` 영역 **전무**. 주문 승인/취소 결과, stock surface 상태 전이, quality 배지 변경 무음 | 스크린리더 켜고 주문 "승인" | 최상위 `role="status" aria-live="polite"` 1개 + 액션 완료 문구 주입 |
| V-20 | `/` topbar | 360 | `globals.css:49-55`, `:278` | `.topbar`/`.topbar-actions` `flex-wrap` 없음. h1(32px) + 버튼 3개(각 min-height 48px, padding 14/22) min-content ≈292px vs 가용 320px → 한글 단위 줄바꿈으로 **3줄 버튼 벽** | 360px `/` | `flex-wrap:wrap; row-gap:16px`, 아이콘 버튼화 |

### P2

| # | route/component | 뷰포트 | file:line | 문제 | 수정 방향 |
|---|---|---|---|---|---|
| V-21 | 전 route | 전부 | `globals.css:23`, `:365` | `select` 가 `font:inherit` 대상 누락 → 기본 13.33px. iOS 16px 미만 포커스 시 **뷰포트 자동 확대**. radius/padding/border 미지정으로 input 과 이질 | `select` 를 23행 및 input 규칙에 추가 |
| V-22 | 전 route | 전부 | `globals.css:38` | `:focus-visible` 에 `select`,`summary`,`details` 미포함 → 포커스 스타일 3종 혼재 | 셀렉터 확장 |
| V-23 | `/` NotificationCenter | 전부 | `globals.css:301` | `li.read{opacity:.6}` → muted 실효 **2.27:1**, 본문 **4.40:1**. 읽음을 투명도로만 전달 | 배경/좌측 마커로 전환, 텍스트 색 유지 |
| V-24 | 전 route | 전부 | `globals.css:42` | `button:disabled{cursor:wait}` 가 **모든** 비활성 버튼에. 실제로는 조건 미충족이 다수(`broker-onboarding.js:81`, `analysis-outcome-view.js:147`, `event-workflow.js:170,181`) → 잘못된 어포던스 | 기본 `not-allowed`, `[aria-busy=true]` 하위만 `wait` |
| V-25 | `/orders`, `/stocks/[symbol]` | 전부 | `orders-view.js:7`, `stock-analysis-product-surface.js:169` | topbar 가 이미 h1 렌더하는데 뷰 내부 **두 번째 h1** | h2 로 강등 |
| V-26 | `/orders`, `/events` | 전부 | `route-workspace.js:415-430` | `OrdersView`/`EventWorkflow` 를 그대로 반환 → 해당 route 에 **`<main>` 랜드마크 부재**. 다른 route 는 `main.route-stack` 사용 | `main.route-stack` 으로 감싸 통일 |
| V-27 | 전 route | 전부 | `route-workspace.js:510-532` | `connection-form`·`ErrorMessage` 가 `<div>` 직계 — 어떤 랜드마크에도 미소속. `/portfolio` 의 `PortfolioHistoryView`(`:405`)도 `<main>` 밖 | `main` 하위 재배치 |
| V-28 | 전 route RouteNav | 전부 | `route-workspace.js:61-70` | `aria-current="page"` 없음 + 활성 링크 CSS 없음 → **현재 위치 표시 전무**, 7탭 동일 | `usePathname()` 기반 `aria-current` + 활성 스타일 |
| V-29 | `/` 드롭다운 2종 | 전부 | `notification-center.js:39-48`, `risk-policy-view.js:42-47` | `aria-controls` 없음, **Esc 닫기 없음**, 외부 클릭 닫기 없음, 열림 시 포커스 이동 없음 | `aria-controls` + Esc 핸들러 |
| V-30 | `/predictions` | 전부 | `prediction-operations-view.js:114` | `aria-label:"New expiry for ${prefix}"` — `lang="ko"` 인데 **영어 레이블** → 한국어 TTS 오독 | 한국어로 |
| V-31 | 랜딩 | 761–900 | `globals.css:209`, `:211` | `grid-template-columns:minmax(0,.9fr) minmax(420px,1.1fr); gap:72px` 인데 미디어쿼리는 760 에서 끊김. 768px 에서 좌측 컬럼 = 768−48−72−420 = **228px**, h2 clamp 하한 40px → 2~3자마다 줄바꿈. `calc(100vh - 150px)` 의 150 은 매직넘버 | 브레이크포인트 900px 또는 `repeat(auto-fit,minmax(min(420px,100%),1fr))` |
| V-32 | 전 route | 360 | `globals.css:183`, `:211` | `min-height:100vh` — 모바일 동적 툴바에서 세로 오버플로·점프 | `100dvh` |
| V-33 | `/` 대시보드 | 360 | `globals.css:117-122` | `.summary,.buying-power{grid-template-columns:repeat(2,1fr)}` 가 **760 미디어쿼리에서 리셋 안 됨**. 360px 셀 내부 폭 ≈94px → `dashboard-view.js:81` 의 `"1,234,567.89 · P/L -12,345.67"` 4~5줄 붕괴 | 미디어쿼리에 `grid-template-columns:1fr` |
| V-34 | `/` BrokerOnboarding | 1280, 1440 | `globals.css:222-226` | `.credential-grid` 2열인데 자식 `CredentialForm` **항상 1개** → 연결 상태에서 우측 절반 영구 공백 | `1fr` 또는 `auto-fit` |
| V-35 | 전 route | 전부 | `globals.css:370-406` vs `:408-428` | 파일 끝 **override 블록**이 13개 셀렉터 이중 선언. 앞선 선언 대부분 죽은 코드이며 V-03/V-04 의 근본 원인 | 원본 규칙에 병합 후 삭제 |
| V-36 | 전 route | 전부 | `globals.css:104-116`, `:392-396`, `:327-336` | **배지 3계열 중복**: `.quality span` / `.surface-state` / `.customized·.default`. 역할 동일, 값만 미세 차이 | 단일 `.badge-pill` + 상태 modifier |
| V-37 | 전 route | 전부 | `dashboard-view.js:5-8` vs `portfolio-history-view.js:6-10` | 같은 quality 개념을 **다른 언어로 표기**: "지연/확인 필요/불러오기 실패/최신" vs "STALE/UNKNOWN/UNAVAILABLE/AVAILABLE" | 한국어 통일 (커밋 e6bca8a 방침) |
| V-38 | 전 route | 전부 | `globals.css:2` | `color-scheme:light` 고정, `prefers-color-scheme` **전무** | `light dark` + 다크 토큰 세트 |
| V-39 | 전 route | 전부 | `globals.css:34,36,37,418,420` | transition 2건 + `translateY`/`scale` transform. `prefers-reduced-motion` **전무** | reduce 미디어쿼리 추가 |

### P3

| # | file:line | 문제 | 수정 방향 |
|---|---|---|---|
| V-40 | `globals.css:242-244` | `.connection-picker summary::before{content:"+"}` 가 `[open]` 에서도 그대로 → 토글 피드백 없음 | `[open] summary::before{content:"−"}` |
| V-41 | `globals.css:42` | 비활성 primary `opacity:.55` → 흰 텍스트 **1.99:1** | disabled 전용 토큰(`--surface-sunken` + `--text-muted`) |
| V-42 | `globals.css:254-262` | `.symbol-picker` fieldset padding 0, 체크박스 라벨 터치타깃 ≈27px | `padding:12px`, `label{min-height:44px}` |
| V-43 | `event-workflow.js:15-16` | `.quality`(패널 헤더 전용 유틸)를 `<p>` 에 사용 → `justify-content:flex-end` 로 BASELINE 배지만 우측 정렬 | 전용 클래스 또는 `.badge-pill` |
| V-44 | `globals.css:336` | `.customized{border-color:#2d7458(초록); color:var(--accent)(파랑)}` — 팔레트 밖 고아 색 + 테두리/텍스트 색상군 불일치 | accent 계열 통일 |
| V-45 | `globals.css:150` | `.error{max-width:1180px; padding-inline:1rem}` vs 나머지 `1280px/32px` → 오류 메시지만 콘텐츠 컬럼 미정렬 | 1280/32 통일 |
| V-46 | `globals.css:342-344` vs `:427` | `.disclaimer` 가 컨텍스트별 12.5px / 14px | 단일화 |
| V-47 | `globals.css:5,186`, `broker-onboarding.js:49` | 죽은 코드: `--panel-raised` 0회 사용(대신 `#f2f4f6` 하드코딩 2회), `.center.compact` JS 사용처 0, `.onboarding-connected` CSS 규칙 0 | 실사용 전환 또는 삭제 |
| V-48 | `app/paper-performance-view.js` | `PaperPerformanceView` 가 **어디서도 import 되지 않음**. `.paper-performance*`(`:341-345`), `.trend-pnl`(`:354`) 사문화 | route 연결 또는 컴포넌트+CSS 동시 제거 |
| V-49 | `route-workspace.js:459-481` | `issuedKey` prop 미전달 → `.issued-api-key`(1회성 키 표시) 절대 렌더 안 됨. 발급 후 키 확인 경로 없음 | 발급 결과를 state 로 전달 |
| V-50 | `globals.css:441` vs `:372` | 미디어쿼리가 `.route-nav` 에 `overflow-x:auto` 를 주지만 372행 `flex-wrap:wrap` 미리셋 → 줄바꿈 지속, `overflow-x` 무효 | `flex-wrap:nowrap` 추가 |
| V-51 | `globals.css:57,173,174` | `font-weight:750` — 비가변 폰트 환경에서 700 으로 라운딩되어 700 과 구분 불가 | 700/800 2단계 |

### 근본 원인 요약

1. **P0 2건 동일 원인** — `globals.css:446` 이 팝오버 containing block 을 viewport 로 잘못 가정.
2. **P1 대비 미달 12건 중 과반** — 토큰 3개(`--muted` 4.31–4.34:1, `--accent` 3.46–3.71:1, `--warning` **2.13:1**)를 텍스트에 직접 사용. 채움용/텍스트용 색 분리 시 일괄 해소.
3. **`--focus` 1.24:1** — `outline:0` 과 결합해 키보드 포커스를 사실상 소거. 단일 최대 접근성 결함.
4. **`globals.css:408-428` override 블록** — 13개 셀렉터 이중 선언, V-03/V-04 직접 원인.
5. **`prefers-color-scheme` · `prefers-reduced-motion` · `aria-live` 코드베이스 전체 0건.**

---

## 2. 거래 도메인·상태 커버리지 (정적 감사)

### 총평

승인 흐름이 가장 위험하다. 서버는 "사용자가 본 값"(`displayedQuantity`/`displayedMaxLoss`)을 검증하는 게이트를 두었는데, 대시보드는 그 값을 화면에 보여주지 않고 서버에서 받아 그대로 되돌려보내 **게이트를 무력화**한다. 주문 화면은 가격조차 없다.

상태 6종 중 **partial·stale·unauthorized 는 사실상 미구현**, loading 은 세션 로드에만 존재. dashboard 미로딩을 "주문 없음"으로 단언하는 거짓 empty 가 orders/events 에 있다.

통화 혼합 합산은 **발견되지 않음**(모두 통화별 분리 렌더). 대신 금액 포맷 규칙이 전무하고, 시각은 9개 파일 전체에 `Intl`/`toLocaleString`/`timeZone` 사용 **0건** — 원시 ISO 문자열 그대로.

백엔드는 `completedAt`/`staleReason`/`partial`/`asOf`/`status` 를 이미 내려주는데 프론트가 전부 버린다. **신규 API 없이 즉시 고칠 수 있는 항목이 많다.**

### P0

| # | route/component | 상태 | file:line | 문제 | 재현 | 수정 방향 |
|---|---|---|---|---|---|---|
| D-01 | orders·dashboard / 주문 승인 | error | `lib/api.js:113-116,130-137` + `orders-view.js:13-25` + `dashboard-view.js:107-125` | `actOnProposal` 이 approval-preview 를 내부에서 fetch 해 `displayedQuantity`/`displayedMaxLoss`/`displayedCurrency` 로 **그대로 되돌려보냄**. 사용자는 가격·최대손실을 **본 적이 없음** (orders-view 는 limitPrice 조차 미표시, 확인 대화상자 없음). 서버 게이트(`PaperOrderWorkflowService.java:530,551-563`, 최대손실=실시간 호가×수량 `:301-314`)가 양쪽 모두 서버값이라 **항상 통과** | `/orders` 승인 클릭 → DevTools: GET approval-preview 의 maxLoss 가 POST approve body 에 그대로 들어가는데 DOM 어디에도 렌더된 적 없음 | 승인 2단계화: preview 를 먼저 화면에 렌더(수량·통화·추정체결가·최대손실·preview 시각) → 사용자가 확인한 뒤에만 동일 값을 `displayed*` 로 전송. preview fetch 를 `api.js` 에서 분리해 UI 가 소유 |
| D-02 | 주문 승인 | unauthorized | `lib/api.js:116,11-18` + `lib/auth.js:66-74` | step-up 실패(백엔드 401 `PAPER_ORDER_STEP_UP_REQUIRED`, `PaperOrderWorkflowController.java:181,69-72`) 분기가 프론트에 전무. `auth.js:66` 이 auth 경로가 아니라는 이유로 **액세스토큰 만료로 오판** → refresh 소모 후 재시도 → 재차 401 → 원시 코드가 배너 노출. 재인증 CTA 없음 | 세션 생성 후 시간 경과 상태에서 승인 클릭 | 401 + `*_STEP_UP_REQUIRED` 를 별도 예외로 승격, 재인증 모달/리디렉트 제공. `auth.js:66` 에 step-up 경로를 refresh 제외 목록 추가 |
| D-03 | orders | 상태 표현 부재 | `orders-view.js:3-26` + `route-workspace.js:415-421` | `OrderIntentStatus` 13종인데 유일한 데이터원 `pendingOrderProposals` 는 서버 SQL 이 `WHERE status='PROPOSED'` 고정 → **나머지 12종은 화면 도달 경로 없음**. `PendingProposalView.status` 가 payload 에 있는데도 미렌더 | 승인 → 목록에서 행이 그냥 사라짐, 결과 확인 불가 | 상태 배지 + 미지 상태 fallback("알 수 없는 상태: X"). 최소 조치로 승인 성공 시 결과 상태 노출. 라이프사이클 조회는 백엔드 status 필터 선행 |
| D-04 | orders·events | loading/empty 혼동 | `route-workspace.js:131-137,416-420,425-426` + `orders-view.js:26` + `event-workflow.js:70` | localStorage 에 연결 ID 없거나 로드 실패 시 `dashboard=null` → `section?.data ?? []` → **"대기 중인 주문이 없습니다"로 단언**. 로딩 상태도 없음(`openWorkspace:208-252` 가 busy 플래그 미설정 → "열기" 버튼도 비활성화 안 됨). 같은 파일 `:413` 은 portfolio 에서만 올바르게 처리 | 새 브라우저에서 `/orders` 직접 진입 | 미로딩·로딩중·실패를 각각 별도 prop 으로 전달. 미로딩 시 empty 문구 금지, `:413` 문안 패턴 재사용 |

### P1

| # | route/component | 상태 | file:line | 문제 | 수정 방향 |
|---|---|---|---|---|---|
| D-05 | dashboard / Portfolio·Analysis | stale/partial | `dashboard-view.js:3-14,32-69,71-90` | 백엔드가 `completedAt`·`staleReason`·`partial`·`missingSections`(`PortfolioReadService.java:57-68,233-243`)와 `result.asOf`·`status` 를 내려주는데 **전부 미렌더**. 총 평가금액에 기준 시각 없음, "지연" 칩만 있고 근거 없음, `status:"DEGRADED"` 분석이 정상과 동일하게 보임 | `Quality` 에 `completedAt` 절대시각(타임존)+상대시간, `staleReason` 보조문구. Analysis 헤더에 `asOf`·`status` 배지 |
| D-06 | dashboard / Quality | partial | `dashboard-view.js:5-8` | `stale/unknown/unavailable` 셋 다 false 면 **"최신"으로 단언**. `data.partial===true`, `missingSections:["BUYING_POWER_USD"]` 여도 "최신" | `section.data?.partial` 을 읽어 "일부 누락" 칩 추가, partial 일 때 "최신" 억제. `missingSections` 한국어 매핑 |
| D-07 | predictions | partial→거짓 empty | `route-workspace.js:239-247` | `Promise.all([예측성과, API키, 운영지표])` 단일 catch → 하나만 실패해도 "채점된 결과가 아직 없습니다" | `Promise.allSettled` + 섹션별 error/empty 분리 |
| D-08 | portfolio·events | partial→전체 손실 | `route-workspace.js:218-227` | `Promise.all([loadDashboard, listEvents])` 실패 시 `:226 return` 으로 **dashboard 전체 폐기**. 이벤트 조회 실패만으로 포트폴리오가 안 보임 | `allSettled` + 부분 성공 반영 |
| D-09 | stock | 거짓 empty | `route-workspace.js:224-227,249-251` + `stock-analysis-product-surface.js:73,172` | `openWorkspace` 조기 return 시 `loadStockSurface()` 미실행 → 기본 `"READY"` 로 남아 "분석 결과가 아직 없습니다" 표시. **조회조차 안 했는데 "없음"으로 단언** | 종목 로드를 워크스페이스 성공 여부와 분리. 기본 상태 `"IDLE"` + 전용 문구 |
| D-10 | stock / 상태 배지 | 미지 상태 은폐 | `route-workspace.js:140-145` | `statusFrom` 이 DEGRADED/FAILED/RUNNING 외 **모든 값을 "READY" 로 폴백**. CANCELED·QUEUED·TIMEOUT 이 정상으로 표시 | 화이트리스트 매핑 + 미지 값은 `"UNKNOWN"` 명시, 원본 값 보조 표기 |
| D-11 | predictions | 미채점 vs 실패 혼동 | `analysis-outcome-view.js:76-81` | `outcome` 객체만 있으면 `directionCorrect` 가 truthy 아닐 때 무조건 **"MISS"**. `:6` 이 null 에 "—" 를 반환하므로 **"— MISS" 조합이 실제 렌더 가능** → 모델 성과를 실제보다 나쁘게 오인 | `directionCorrect == null` 이면 "채점 대기". HIT/MISS 는 boolean 확정 시에만 |
| D-12 | stock / 분석·예측·설명 생성 | 중복 제출 | `stock-analysis-product-surface.js:58,85,109` + `route-workspace.js:269-301,386-394` | 버튼 비활성화 조건이 `state==="PROGRESS"` 뿐인데 PROGRESS 는 **POST 완료 이후** 세워짐 → POST 진행 중 버튼 계속 활성 → 연타 시 run 중복 생성. `stockSurface()` 는 `busy` prop 전달조차 안 함 | mutation 시작 즉시 PROGRESS 설정 또는 `busy` prop 전달. `createSingleFlight` 를 route-workspace 에도 도입 |
| D-13 | 전 route / 주문 승인 | 중복 제출 | `page.js:70-81,44-45` + `route-workspace.js:254-267` + `api.js:96-110,233-244` | 클릭마다 `crypto.randomUUID()` 로 **새 idempotency key** → 서버 중복 감지 불가, `api.js:108-110` replay 분기는 **죽은 코드**. `createSingleFlight` 는 `page.js` 의 `runMutation` 에만 적용되고 `orderAction` 에는 미적용, route-workspace 에는 아예 없음. 가드가 `busyOrderId` **스칼라**라 두 주문 동시 실행 시 먼저 끝난 쪽의 `finally` 가 진행 중 버튼을 재활성화. 서버 CAS 가 이중 체결은 막으므로 손실이 아닌 **오인**(두 번째 요청이 `PAPER_ORDER_CONFLICT` 로 실패 표시) | idempotency key 를 `orderId+action` 기준 생성해 재시도 간 재사용. `orderAction` 을 singleFlight 로 감싸고 `busyOrderId` 를 **Set** 으로 |
| D-14 | 전 route | unauthorized | `lib/auth.js:71-73` + `api.js:11-18,24` + `page.js:66,76,91,194` | refresh 실패 시 원본 401 을 그대로 반환 → 배너에 **영문 원문**. `session` 은 여전히 truthy 라 로그인 화면 복귀 안 되고 재로그인 CTA 도 없음. 401→null 처리는 `loadSession` 에만 존재 | `authorizedFetch` 에 인증 소실 콜백 → `session=null` 전환. 401 메시지를 한국어 재로그인 안내로 |
| D-15 | 전 route / 로그아웃 | error 무반응 | `route-workspace.js:518` | `logout().then(...)` — **catch 없음**. 실패 시 unhandled rejection, 화면 무변화·무피드백. `auth.js:66` 이 `/api/v1/auth/` refresh 를 건너뛰므로 세션 만료 시 로그아웃도 실패 가능 → **탈출구가 죽음**. `page.js:187-196` 은 catch 는 있으나 실패 시 `setSession(null)` 미실행 | 실패해도 클라이언트 토큰 폐기 + `setSession(null)` 로 로컬 로그아웃 보장 |
| D-16 | 주문 승인 | error 복구불가 | `lib/api.js:14` | 오류 본문에서 `.code` 만 추출하고 나머지 폐기. 서버는 mismatch 시 **`serverQuantity`/`serverMaxLoss`/`currency`** 를 함께 반환(`PaperOrderWorkflowController.java:190-207`)하는데 재승인 판단에 필요한 그 값이 전부 소실 | `error.body` 로 응답 전체 보존. 서버 스냅샷 vs 사용자가 본 값을 나란히 제시 + 재확인 버튼 |
| D-17 | events / 이벤트 등록 | error 데이터 소실 | `event-workflow.js:108-122` | `onCreate(...)` 를 await 하지 않고 `:120 form.reset()` → **생성 실패 시 입력값 전부 소실**, 복구 경로 없음. `analysis-outcome-view.js:124,174` 는 올바르게 처리 | `Promise.resolve(onCreate(...)).then(() => reset)` |
| D-18 | events / 발생 시각 | 표기 불일치 | `event-workflow.js:139,118` + `stock-analysis-product-surface.js:134` | 입력은 `datetime-local`(타임존 없는 로컬시각) → `toISOString()` UTC 변환 → 표시는 UTC 원문. **KST 09:00 입력이 "2026-08-05T00:00:00Z" 로 보임(9시간 차)** | 입력 라벨에 타임존 명시, 표시는 공통 포맷터로 `YYYY-MM-DD HH:mm KST` 통일 |
| D-19 | dashboard·orders·events·stock | error 재시도 없음 | `route-workspace.js:531,84` + `page.js:239` | 실패 시 공유 배너 문자열 하나만, **재시도 버튼 없음**. 재시도 경로는 readiness(`:486`)·prediction-operations(`:475`) 뿐. 배너 dismiss 불가, 새 오류가 이전 오류를 덮어씀 | 섹션 단위 오류 + 해당 로더 재호출 "다시 시도" 버튼. 전역 배너는 보조로 강등 |
| D-20 | dashboard / 현금 | unknown 노출 | `dashboard-view.js:48-49` | "현금" 라벨 아래에 `cashBalanceStatus` **enum 원문**(`KNOWN`/`UNKNOWN`)을 값으로 렌더. 한국어 UI 의 금액 자리에 영문 enum | enum→한국어 매핑. 라벨을 "현금 잔고 확인 상태" 로 바꾸거나 금액과 상태 분리 |

### P2

| # | file:line | 문제 | 수정 방향 |
|---|---|---|---|
| D-21 | `dashboard-view.js:41,44,54,66-67,81` + `event-workflow.js:32-40,55-64` + `analysis-outcome-view.js:94` | 9개 파일 전체에 금액 포맷 함수 없음(`Intl`/`toLocaleString` **0건**) → BigDecimal 원값 그대로, 자릿수 구분·고정 소수 없음. 서버는 승인 검증 시 **KRW 0자리 / USD 2자리 HALF_UP** 정규화(`PaperOrderWorkflowService.java:565-567`) → **표시 기준과 판정 기준 불일치** | `lib/format.js` 신설: `formatAmount(currency, value)` KRW 0자리·USD 2자리 + 천단위. 전 금액 렌더를 경유 |
| D-22 | `analysis-outcome-view.js:94` vs `dashboard-view.js:66` | 통화가 한쪽은 **접미**, 다른 쪽은 **접두** | 접두 통일, 포맷터에 흡수 |
| D-23 | `event-workflow.js:38-40,62-64` vs `dashboard-view.js:88` | 같은 weight 를 이벤트 비교표는 **원시 소수**(0.3421), 대시보드는 **%**(34.2%). 금액 열 옆 원시 소수는 금액으로 오독 가능 | 비중 전용 포맷터(%) 통일 |
| D-24 | `analysis-outcome-view.js:219-220,227-228` | 로컬 달력 날짜를 `${from}T00:00:00.000Z` 로 UTC 경계 변환. 라벨은 "시작일" 뿐 → KST 기준 9시간 어긋남 | 라벨에 기준 타임존 명시 또는 로컬 자정→UTC 변환 |
| D-25 | `stock-analysis-product-surface.js:36,63,90,113,134,145` + `analysis-outcome-view.js:91` | 모든 시각이 원시 ISO 문자열(타임존 라벨 없음). 저장소 내 유일한 포맷은 `notification-center.js:7` 의 `toLocaleString()` → **앱 전체 포맷이 두 갈래** | 공통 `formatInstant(iso)` 도입, 타임존 접미 고정 |
| D-26 | `dashboard-view.js:11-13` | `unknownFields` 를 `join(", ")` 그대로 출력 → 백엔드 **내부 필드 경로**(`"account.cashBalance"`) 노출 | 필드→한국어 라벨 사전, 미등록 키는 "기타 항목 N건" |
| D-27 | `route-workspace.js:174,183,279,284` + `stock-analysis-product-surface.js:14` + `orders-view.js:11` | `SNAPSHOT_NOT_READY`·`CONNECTION_REQUIRED`·`ORDERS_UNAVAILABLE` 등 **영문 코드 그대로** 렌더. `api.js:14` 가 모든 백엔드 code 를 message 로 승격 | 코드→한국어 메시지+다음 행동 사전. 미등록 코드만 원문 fallback |
| D-28 | `dashboard-view.js:29,47,49,55` vs `stock-analysis-product-surface.js:36,63,...` | 값 없음 표기가 **"확인 필요"** 와 **"—"** 두 갈래 혼용 | "확인 필요" 통일, 진짜 N/A 와 미조회 구분 |
| D-29 | `orders-view.js:14,16` vs `dashboard-view.js:110,113` | orders 는 `BUY`/`SELL` 영문 + limitPrice 미표시, dashboard 는 한국어 + limitPrice 표시. **전용 주문 화면이 요약보다 정보가 적음** | 주문 행 렌더를 공용 컴포넌트로 추출 |
| D-30 | `dashboard-view.js:110` | `order.side === "BUY" ? "매수" : "매도"` — 미지 값이 전부 **"매도"** 로 표시되는 unsafe fallback (방어 목적) | 명시적 매핑 + 미지 값은 원문 노출 후 액션 비활성화 |
| D-31 | `route-workspace.js:436-440` + `analysis-outcome-view.js:232,258` | `busy: false` **하드코딩** → `aria-busy` 항상 false, "적용" 버튼 절대 비활성화 안 됨 | `outcomeBusy` state 도입 |
| D-32 | `analysis-outcome-view.js:150-153` | 파괴적 모델 삭제에 확인 대화상자 없음 (브로커 연결 삭제는 confirm 있음) | confirm 또는 2단계 확인 |
| D-33 | `route-workspace.js:475-479` | `onRefresh` 의 `Promise.all` 에 **catch 없음** → unhandled rejection, 화면 무반응 | catch + `predictionError` 반영 |
| D-34 | `page.js:55` | `.catch(() => {})` 로 완전 무시 → `unreadCount` 가 0 으로 남아 "읽지 않음 없음" 오인 | 실패 시 미확정 표시 |
| D-35 | `route-workspace.js:208-252,530` | `openWorkspace` 가 `busy` 미설정 → 연타 시 dashboard/events 동시 요청 경쟁(마지막 응답 승) | `mutation()` 경유 또는 전용 로딩 플래그 |
| D-36 | `page.js` 전체 vs `route-workspace.js` 전체 | **같은 워크스페이스가 두 벌로 구현**되고 동작이 다름: singleFlight(page.js only), localStorage(route-workspace only), 삭제 confirm(page.js only) → **진입 경로에 따라 안전장치가 달라짐** | 공통 훅으로 단일화 |
| D-37 | `lib/auth.js:65,74` | 401 시 **모든 메서드를 무조건 1회 재시도**. `/step-up` 은 idempotency key 없는 POST 라 토큰이 두 번 발급 | GET 또는 idempotency key 있는 요청에만 자동 재시도 |

### P3

| # | file:line | 문제 |
|---|---|---|
| D-38 | `route-workspace.js:64` | 보유 종목 없으면 **하드코딩 `/stocks/AAPL`** 링크 |
| D-39 | `page.js:116` | 한국어 UI 에 영문 confirm ("Delete this broker connection…") |
| D-40 | `stock-analysis-product-surface.js:6,130,141` | 상태 영문 원문 노출, `:130`·`:141` 은 실제 상태와 무관하게 `"READY"` **하드코딩** |
| D-41 | `analysis-outcome-view.js:13-31` | `hit`/`MAE`/`cal`/`DEGRADED` 영문 축약 조합 출력 |
| D-42 | `dashboard-view.js:107-125` + `orders-view.js:13-25` | 주문 제안에 생성 시각·만료 미표시 — `PendingProposalView` 에 시각 필드가 없어 **백엔드 변경 선행 필요** |

### 미검증 항목

1. step-up 401 의 정확한 발생 조건 — `OrderApprovalStepUpService` 미독, 컨트롤러 문서주석에만 근거.
2. `analysis-outcome-view.js:80` 의 미채점 outcome 이 백엔드에서 실제 생성되는지 미검증 — 코드상 도달 가능하다는 점만 근거.
3. Jackson BigDecimal 직렬화 형식 미확인. 단 클라이언트에 포맷 로직이 전무하다는 사실은 grep 검증됨.

---

## 3. 런타임 감사 (Playwright 상태 매트릭스)

하네스: `playwright.config.mjs` + `e2e/` — `/api/v1/**` 를 전량 가로채 6상태 고정, 4뷰포트.
실행 규모: **8 route × 6 state × 4 viewport = 192 조합** + axe 192 + 여정 4.
결과 원본: `e2e/__reports__/records/`, `e2e/__reports__/axe.json`.

> 스크린샷은 **baseline 으로 승인하지 않았다** — 현재 화면이 망가진 상태이므로 비교 baseline 은 P0/P1 수정 후에 생성한다.

### R-01 가로 오버플로 13건 (P1)

| route/state | 뷰포트 | 초과폭 |
|---|---|---|
| predictions / partial·stale | 360 | **+857px** |
| predictions / partial·stale | 768 | **+461px** |
| portfolio / partial·stale | 360 | +64px |
| settings / partial | 360 | +54px |
| settings / empty·error·loading·stale | 360 | +8px |
| stocks-AAPL / partial | 360 | 발생 |

`/predictions` 는 360px 에서 뷰포트의 **3.4배** 폭. 정적 감사 V-17(9~11열 표 + `white-space:nowrap`)의 실측 확인.

### R-02 `undefined` 화면 노출 8건 (P0)

`/stocks/AAPL` **partial·stale** 상태에서 4개 뷰포트 전부 `undefined` 문자열이 사용자에게 렌더. 거래 판단 화면에 원시 JS 값이 노출됨.
→ D-09/D-10 의 폴백 누락과 동일 계열. 옵셔널 필드에 대한 표시 폴백 부재.

### R-03 axe color-contrast **192/192 조합 위반** (P1)

| rule | impact | 위반 조합 | 노드 누계 | 대표 셀렉터 |
|---|---|---|---|---|
| `color-contrast` | serious | **192 / 192 (100%)** | **2,068** | `.topbar .eyebrow`, `a[href$="portfolio"]`, `a[href$="AAPL"]`, `p`, `a` |
| `scrollable-region-focusable` | serious | 8 / 192 | 8 | `div > .table-wrap`, `.table-wrap` |

정적 감사의 대비 미달 12건이 실측으로 확인됨. **모든 화면, 모든 상태에서 예외 없이 발생.**
`scrollable-region-focusable` 은 V-17 과 동일 원인.

### R-04 login `error` 상태에 읽을 수 있는 오류 메시지 없음 (P1) — 4뷰포트 전부

`error-has-readable-message` 어서션 실패: `/login` error 상태에 `role="alert"`/`role="status"` 를 가진 오류 요소가 없음.

### R-05 핵심 여정 4건

| 여정 | 결과 | 근거 |
|---|---|---|
| 로그인 | **통과** | 비로그인 `/` → 로그인 유도 노출 → `/oauth2/authorization/oidc?returnTo=%2F` 리디렉트 확인 |
| 오류 복구 | **통과 (문구 결함)** | 에러 노출 → 재시도 컨트롤 존재 → 재시도 후 복구(dashboard 요청 2회). 단 노출 문구가 **`INTERNAL_ERROR` 영문 원문** → D-27 실측 확인 |
| 주문 승인 중복 제출 | **통과** | 승인 버튼 2회 연타 시 실제 POST **1회**. `busyOrderId` 가드가 단일 주문 연타는 차단 (D-13 의 동시 다중 주문 시나리오는 이 테스트로 미검증) |
| 분석 | **차단** | `analyze-button-present` 실패 — `/stocks/AAPL` 에 분석 실행 버튼이 렌더되지 않음. D-09(`openWorkspace` 조기 return → `loadStockSurface()` 미실행)의 실측 확인 |

### R-06 콘솔 에러 56 조합

전부 의도된 목 응답(500/401)의 리소스 로드 실패. **페이지 예외(`pageErrors`)는 0건** — 런타임 크래시는 없음.

---

## 4. P2/P3 클로즈아웃

- 브랜치: `chore/ui-ux-p2-p3-closeout` (base: `design/modular-monolith-architecture`, `52b53c8` 이후)
- 클로즈아웃일: 2026-08-07
- 범위: 위 감사의 미해결 P2/P3 전량 재검증 + 조치. P0/P1 은 `52b53c8`에서 이미 복구됨(재검증만 수행).

### 재검증 방법

`52b53c8`가 `globals.css`·모든 컴포넌트를 대폭 고쳐 원 감사의 file:line 참조가 대부분 무효화됐다. 각 P2/P3 항목을 현재 코드 기준으로 다시 대조해 **완료 / 부분완료 / 잔존 / 사문화**로 재분류한 뒤, 잔존 항목만 조치했다.

### 1절(시각·접근성) 재검증 결과

`52b53c8`가 이미 해소: V-21~28, V-30~35, V-38, V-39, V-41, V-42, V-44~46, V-50, V-51 (다크 스킴 토큰 세트와 `prefers-reduced-motion` 포함).

이번에 조치한 잔존 7건:

| # | 문제 | 조치 |
|---|---|---|
| V-36 | 배지 4계열 중복(`.quality span`/`.surface-state`/`.customized`·`.default`) | 단일 `.badge-pill` + `--ok`/`--info`/`--warn`/`--danger`/`--neutral` modifier 로 통합. 기존 텍스트 전용 토큰만 재사용(신규 hex 없음), light/dark 대비 ≥4.5:1 유지. `DashboardView`가 아직 내보내는 `.quality .stale/.unknown/.unavailable/.available/.partial` 5개 클래스는 특이도 누수 방지를 위해 그 5개로만 스코프해 존치 |
| V-37 | quality 어휘가 화면마다 영/한 혼용 | `portfolio-history-view.js` 를 `dashboard-view.js` 와 동일한 한국어(지연/확인 필요/불러오기 실패/최신)로 통일 |
| V-29 | 알림·리스크 정책 드롭다운에 `aria-controls` 없음, Esc 닫기 없음 | 패널에 `useId()` id 부여 + `aria-controls` 연결, 열려 있을 때만 Escape 리스너 부착(정리 포함) |
| V-40 | `<details>` 열림 상태에 `+`→`−` 전환 없음 | `[open] summary::before` 규칙 추가 |
| V-43 | 패널 헤더 정렬용 `.quality` 를 `<p>` 오용(BASELINE 배지가 우측 정렬로 밀림) | `.badge-pill--neutral` 로 교체 |
| V-47 | `onboarding-connected` 클래스가 대응 CSS 규칙 없이 JS 에만 존재 | 규칙 신설 대신 JS 에서 클래스 제거(연결 상태에 필요한 실제 스타일이 없어 신설 시 또 다른 미사용 표면이 생김) |
| V-48 | `PaperPerformanceView`·`loadPaperPerformance` 완성됐지만 어디서도 마운트 안 됨 | `/predictions` 라우트에 배선, 기존 3개 로드와 동일한 `Promise.allSettled` 부분 성공 패턴 적용 |

### 2절(거래 도메인) 재검증 결과

`52b53c8`가 이미 해소: D-21~34, D-37, D-39, D-40.

이번에 조치한 잔존 6건:

| # | 문제 | 조치 |
|---|---|---|
| D-03 | `pendingProposals` 조회가 `WHERE status='PROPOSED'` 로 고정돼 13종 중 12종이 화면에 도달 불가 | 백엔드: `status = ANY(?)` 바운드 배열 필터(SQL 삽입 없음) + `orderStatus` 쿼리 파라미터(미인식 값은 400 `INVALID_ORDER_STATUS`, 기본은 종결 4종을 뺀 OPEN 9종). 프론트: 13종 전부 한국어 라벨 + `badge-pill` 상태 매핑, 미등록 값은 `알 수 없는 상태: X` 노출. **승인·취소 버튼은 `status==="PROPOSED"` 일 때만 활성** — 새로 노출된 상태는 전부 표시 전용(1차 구현에서 이 게이트가 빠져 있던 걸 재검토 중 발견해 직접 수정) |
| D-42 | 제안 생성·만료 시각이 계약에 없어 화면에 기준 시각을 못 보여줌 | 백엔드: `order_intents` 에 `created_at`/`expires_at` 컬럼 추가(V39, 기존 행은 NULL 유지·조작 없음), 제안 생성 시 `order.proposal.ttl`(기본 `PT15M`)로 스탬프, 승인 시 만료면 409 `PAPER_ORDER_PROPOSAL_EXPIRED` 로 거절(취소는 계속 허용). 프론트: 주문 3개 화면(목록 2곳 + 승인 패널)에 기준/만료 시각과 fresh/expiring/expired/unknown 배지 표시, 만료 시 승인 버튼을 서버 응답 전에 클라이언트에서 먼저 비활성화 |
| D-36 | `/` 와 나머지 6개 라우트가 서로 다른 두 SPA(`page.js`/`route-workspace.js`)로 구현돼 안전장치가 라우트마다 달랐음 | `RouteWorkspace` 에 `route:"home"` 분기를 추가해 `page.js` 를 9줄 얇은 마운트로 축소. 단일 실행(mutation) 래핑·삭제 확인·`aria-live`·부분 성공 로딩을 전 라우트로 통일. `/` 는 저장된 연결을 자동 복구하지 않는 기존 진입 동작(랜딩 우선)을 그대로 보존 |
| D-35 | `openWorkspace` 로딩 중에도 "열기" 버튼이 계속 활성 | `workspaceStatus==="loading"` 을 disabled 조건에 포함 |
| D-38 | 심볼 미상 시 로그인 후 이동 경로가 보유하지 않을 수 있는 `/stocks/AAPL` 로 고정 | 심볼 없으면 `/` 로 이동(종목 네비 항목은 기존대로 비활성 표시 유지) |
| — | V-49 발급 API 키를 상태로 보관해 한 번 노출 후 저장소에 남기지 않음 | (거래 도메인 절 항목은 아니나 같은 D-36 작업의 연장선) |

### 조치하지 않은 항목

- V-48/D-42 의 백엔드 스키마 변경 외 계약 추가는 없음 — 요청 범위(D-03/D-42) 밖.
- `.quality` 유틸을 `DashboardView` 자체에서 `.badge-pill` 로 완전히 옮기는 작업은 하지 않음: `DashboardView` 는 다른 작업 스트림 소유였고, V-36 의 요구는 "중복 계열 통합"이지 전체 재작성이 아니었음. 현재 CSS 는 두 표기를 스코프로 분리해 충돌 없이 공존.

### 다크 스킴 e2e 커버리지 신설

`52b53c8` 가 `prefers-color-scheme: dark` 토큰 세트를 이미 출하했지만 e2e 매트릭스는 light 만 검증했다. `playwright.config.mjs` 뷰포트 4종을 `colorScheme: light|dark` 축과 곱해 프로젝트 4→8개로 확장(`vp-360`~`vp-1440` 는 기존 이름 유지해 이미 승인된 192개 light baseline 을 보존, `-dark` 접미사가 새 파일로 분리). `e2e/report.mjs` 에 scheme 별 집계 절을 추가.

### 클로즈아웃 중 발견한 결함 2건 (자체 재검증)

1. **D-03 승인·취소 게이트 누락** — 상태 필터를 넓히면서 1차 구현이 승인·취소 버튼을 `status==="PROPOSED"` 로 되짚어 잠그는 걸 빠뜨렸다. 이 감사 문서(§2 총평)가 지적한 것과 같은 계열의 결함("서버가 검증하는 값을 클라이언트가 스스로 만들어 되돌려보내면 게이트가 무력화된다")이 여기서도 재현될 뻔했다: 화면에 APPROVED/ACTIVE/COMPLETED 등 이미 종결·확정된 주문까지 도달하게 됐는데, 승인·취소 버튼이 상태와 무관하게 활성 상태였다. 재검증 과정에서 발견해 `orders-view.js`/`dashboard-view.js` 양쪽에 `actionable = order.status==="PROPOSED"` 게이트를 추가하고 회귀 테스트를 넣었다.
2. **`OrderTiming` 표기 붙음** — 기준·만료 두 시각을 별도 `<span>` 두 개로 렌더했는데 `.order-timing` 에 구분 스타일이 없어 "5분 전만료 2026-08-06…" 처럼 텍스트가 그대로 이어졌다. baseline 스크린샷 육안 대조 중 발견. 두 조각을 `" · "` 로 이은 단일 문자열로 합쳐 다른 요약 행과 동일한 구분자 관례를 따르게 했다.

두 건 모두 baseline 재생성·육안 대조 단계에서 잡혔다 — 자동 게이트(단위 테스트·axe·오버플로 어서션)는 통과했지만 실제 화면은 틀렸던 사례로, `claudedocs/ui-review-criteria.md`의 "테스트가 전부 통과했다는 안전의 증거가 아니다" 원칙을 다시 확인시켰다.

### 코드 리뷰 1패스 (병합 전 게이트)

전체 diff에 대해 독립 리뷰를 수행하고, 지적된 특이도 주장은 브라우저에서 `getComputedStyle` 로 실측 대조했다(스크린샷 육안 확인만으로는 border-color 차이에 가려 text-color 결함을 놓쳤다).

**수정함**

| 등급 | 문제 | 조치 |
|---|---|---|
| HIGH | `LiveOrderActivationService` 가 propose 시 `expiresAt` 을 찍으면서도 approve 에서 전혀 검증하지 않음 — LIVE 주문 화면은 "만료됨" 배지로 승인을 막지만 서버는 직접 호출 시 그대로 승인. Phase 0 에서 `real-order.enabled=false` 로 도달 불가하나, PAPER 경로(D-42)와 대칭이 깨진 채 방치하면 향후 활성화 시점의 잠재 결함 | PAPER 와 동일한 지점(재조회·락 이후, step-up 소비 이전)에 만료 검증 추가, 새 코드 `PROPOSAL_EXPIRED`→409, NULL 만료는 그대로 승인 가능 유지. 회귀 테스트 3건(만료 후 거절+상태 불변+부작용 없음 / 만료 전 성공 / legacy NULL 승인 가능) |
| MEDIUM | `.list span{color:var(--muted)}`(0,1,1) 이 `.badge-pill--*{color:...}`(0,1,0) 를 특이도로 눌러, orders/dashboard 목록 안 배지 텍스트가 상태와 무관하게 전부 회색으로 렌더 — V-36 이 만든 색 구분이 이 두 화면에서 무효화됨. 실측: 수정 전 전 상태 `rgb(90,100,114)` 동일값, 수정 후 `--ok/--info`→accent-text, `--warn`→warning-text, `--danger`→danger-text 로 분리 확인 | `.list .badge-pill--*`(0,2,0) 로 상태색 복원. baseline 재생성 |
| MEDIUM | `OrderApprovalPanel` 확인 버튼이 만료는 막지만 `status==="PROPOSED"` 는 확인하지 않음 — 열려 있던 패널의 스냅샷이 다른 상태로 전이돼도 버튼이 막히지 않는 경로. 서버 재조회 락이 최종 방어선이라 실제 오발주로는 못 이어지지만 명시된 불변식과 불일치 | `isStatusActionable(order)`(`status==null \|\| status==="PROPOSED"`, `{id}` 폴백은 그대로 승인 가능) 추가, 회귀 테스트 2건 |
| LOW | D-36 통합 과정에서 로그아웃 실패 사유가 조용히 삼켜짐(`page.js` 는 원래 노출) | `.catch(() => {})` → `.catch(value => setError(describeError(value.message)))`, 로컬 세션 폐기 보장은 그대로 |
| LOW | 홈이 렌더하지 않는 `events` 조회 실패가 홈에도 페이지 오류 배너로 노출 | `route !== "home"` 일 때만 `listEvents` 조회·오류 반영 |

**후속 과제로 남김** (병합 차단 아님, 발견됐지만 이번 범위 밖으로 판단):
- `orderStatus` 기본 필터 확대 + `LIMIT` 불변 + `created_at DESC NULLS LAST` 조합이 legacy(NULL) 행과 비활성 상태 행에 밀려 실제 PROPOSED 행을 잘라낼 수 있음(정렬에 `status='PROPOSED'` 우선순위 또는 truncation 플래그 필요).
- `classifyProposalExpiry(null)` 은 구조분해 시 TypeError(현재 모든 호출부가 non-null 이라 잠재적).
- `RouteWorkspace` 와 `RiskPolicyPanel` 이 Escape 를 각각 처리해 이중 바인딩(현재는 우연히 안전).
- 여러 개의 서로 다른 mutation 이 동시에 걸리면 단일 실행(single-flight)이 뒤의 것을 조용히 병합(버튼이 `busy` 로 막혀 있어 현재는 도달 어려움).

### 최종 게이트 결과 (2026-08-07)

| 게이트 | 결과 |
|---|---|
| `npm run lint:css` | 통과, 위반 0 |
| `npm test` | **136 / 136** 통과 |
| `npm run build` | 성공 |
| `npm run e2e` (a11y, 8 프로젝트 × 8 route × 6 상태 = 384) | **384 / 384**, WCAG 2.2 AA 위반 **0** |
| `npm run e2e` (state-matrix, 동일 384 조합) | **384 / 384**, 가로 오버플로·금지 토큰(`undefined`/`NaN`/`[object Object]`/`Invalid Date`)·에러 상태 재시도·미인증 재로그인 어서션 전부 통과, baseline 재생성(리뷰 수정 반영본으로 재생성) 완료 |
| `npm run e2e` (journeys, vp-1280 전용 4종) | **4 / 4** 통과(로그인·분석·주문 승인 중복제출·오류 복구). 다른 7 프로젝트의 28건은 설계상 skip |
| `npm run verify` (리뷰 수정 반영 후 최종 재실행) | **772 / 772** 통과, 0 실패 |
| `trading-backend`: `./mvnw clean verify` (리뷰 수정 반영 후 최종) | **662 / 662** 통과 (`queryCountDoesNotGrowWithEventOrProposalRows` 불변식 유지 확인) |
