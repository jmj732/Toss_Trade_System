# 홈 차트 우선 + 계좌 Rail Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 Toss candle 응답과 Portfolio만 사용해 홈을 차트 중심 main + desktop sticky account rail로 재배치하고, 종목 화면의 캔들 구간 계약과 접근성을 보존한다.

**Architecture:** 공유 `MarketCandleChart`가 raw candle envelope의 상태·provenance·asOf·unknownFields를 보존하며 실제 OHLCV만 SVG geometry로 변환한다. `RouteWorkspace`가 홈의 primary symbol과 `connectionId + symbol + interval` 요청 key를 관리하고, `DashboardView`의 기존 Portfolio를 rail에서 재사용한다. backend, provider adapter, cache, chart dependency는 변경하지 않는다.

**Tech Stack:** Next.js/React `createElement`, plain JavaScript, hand-written CSS, Node built-in test runner, Playwright E2E.

---

## Chunk 1: 공유 candle renderer와 계약 테스트

### Task 1: 실패 테스트로 interval/status/geometry 계약 고정

**Files:**
- Create: `web-dashboard/test/market-candle-chart.test.mjs`
- Modify: `web-dashboard/test/api.test.mjs` (기존 `loadCandles` 호출 테스트가 있으면 같은 그룹에 interval 의미 assertion 추가)

- [ ] **Step 1: `MarketCandleChart`의 실패 테스트 작성**
  - fixture는 Toss envelope `{ status, data: { candles }, provenance, asOf, unknownFields }`를 사용한다.
  - `1m`은 “1분봉”, `1d`는 “일봉”으로 표시하고 버튼 `aria-pressed`가 하나만 true인지 검사한다.
  - 최신순 input이 SVG에는 시간순으로 나타나고, OHLC null/malformed candle은 SVG geometry에서 제외되며 수치 표에는 `확인 필요`가 남는지 검사한다.
  - volume null은 가격 봉을 유지하되 volume bar와 `거래량 미제공` 상태를 표시하는지 검사한다.
  - `ERROR`/`UNAVAILABLE`, normal empty, `DEGRADED + usable candles` 각각 no-chart/chart+warning/EMPTY 상태를 검사한다.
  - SVG의 `role="img"`, `title`/`desc` 연결, 상승/하락 텍스트·부호·red/blue class를 검사한다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `cd web-dashboard && node --test test/market-candle-chart.test.mjs`

Expected: `FAIL` — 공유 renderer가 아직 존재하지 않는다.

### Task 2: 최소 공유 renderer 구현

**Files:**
- Create: `web-dashboard/app/market-candle-chart.js`

- [ ] **Step 1: interval 상수와 raw envelope 정규화 구현**
  - `CANDLE_INTERVALS = [{ key: "1m", label: "1분봉" }, { key: "1d", label: "일봉" }]`를 export한다.
  - raw status 우선순위는 `ERROR/UNAVAILABLE → no chart`, `DEGRADED + usable candles → chart + warning`, normal + candles → `READY`, normal + empty → `EMPTY`다. `READY`는 UI 내부 파생 상태일 뿐 raw `AVAILABLE/DEGRADED/UNAVAILABLE/ERROR`를 변환하거나 덮어쓰지 않는다.
  - provider payload의 `data.candles`, `provenance`, `asOf`, `unknownFields`를 그대로 읽고 새 값을 합성하지 않는다.

- [ ] **Step 2: OHLCV geometry와 접근 가능한 수치 표 구현**
  - candles를 provider 원본의 최신순에서 시간순으로 복사해 그린다.
  - open/high/low/close가 모두 finite number인 봉만 wick/body를 그린다.
  - volume은 finite number인 값만 bar로 그리고, null/malformed 값은 표와 경고로 명시한다.
  - scale 값은 유효한 provider 값의 min/max만 사용한다. fallback 가격·보간·추정값은 금지한다.
  - SVG `role="img"`, `title`/`desc`, `aria-labelledby`와 numeric table을 함께 제공한다.

- [ ] **Step 3: 실패 테스트 통과 확인**

Run: `cd web-dashboard && node --test test/market-candle-chart.test.mjs`

Expected: `PASS`.

- [ ] **Step 4: 커밋**

```bash
git add web-dashboard/app/market-candle-chart.js web-dashboard/test/market-candle-chart.test.mjs web-dashboard/test/api.test.mjs
git commit -m "기능 :: 공유 캔들 차트 renderer"
```

## Chunk 2: 홈 데이터 wiring과 2열 rail

### Task 3: 홈 candle 조회 key와 primary symbol 테스트

**Files:**
- Create: `web-dashboard/test/home-candle-wiring.test.mjs`
- Modify: `web-dashboard/test/route-surface.test.mjs`
- Modify: `web-dashboard/test/dashboard-view.test.mjs`

- [ ] **Step 1: 실패 테스트 작성**
  - 실제 경로 `dashboard.portfolio.data.positions`의 첫 번째 유효 symbol만 primary symbol로 선택한다. portfolio section이 unavailable/empty이거나 symbol이 없으면 no-chart 상태로 고정한다.
  - `selectHomeSymbol(dashboard)`와 `homeCandleRequestKey(connectionId, symbol, interval)` 순수 helper를 통해 동일한 `connectionId + symbol + interval` key 재요청은 candle을 다시 호출하지 않는다.
  - connectionId, symbol, interval 중 하나가 바뀌면 정확히 한 번 다시 호출한다.
  - 다른 symbol 자동 fallback은 하지 않는다.
  - DashboardView의 `includePortfolio=false`에서 기존 portfolio section이 main 안에 중복 렌더되지 않는다.

- [ ] **Step 2: 테스트 실행해 실패 확인**

Run: `cd web-dashboard && node --test test/home-candle-wiring.test.mjs test/route-surface.test.mjs test/dashboard-view.test.mjs`

Expected: 새 primary/key/rail assertion 일부 `FAIL`.

### Task 4: RouteWorkspace 홈 candle wiring 구현

**Files:**
- Modify: `web-dashboard/app/route-workspace.js`
- Modify: `web-dashboard/lib/api.js` only if existing `loadCandles` signature cannot express the already-supported interval; no new endpoint/helper unless required by current contract.

- [ ] **Step 0: 순수 request helper 구현**
  - `selectHomeSymbol(dashboard)`와 `homeCandleRequestKey(connectionId, symbol, interval)`를 `route-workspace.js`에서 export한다.
  - `homeCandleRequestKey` 비교를 실제 load 경로에서 사용해 effect/re-render가 같은 key를 다시 호출하지 않도록 한다. 테스트는 이 helper와 호출 경로를 함께 고정한다.

- [ ] **Step 1: 홈 상태 추가**
  - `homeCandles`와 `homeCandleTimeframe`을 둔다. 홈 기본값은 기존 contract의 `1m`이다.
  - `homeCandleRequestKeyRef`로 `connectionId + symbol + interval` exact key를 기억한다.

- [ ] **Step 2: dashboard 완료 후 primary candle 조회**
  - `loadDashboard(id)`의 반환 `dashboard.portfolio.data.positions`에서 첫 번째 유효 symbol을 선택한다.
  - key가 바뀐 경우에만 `loadCandles(id, symbol, homeCandleTimeframe)`를 호출한다.
  - 실패 시 `{ status: "ERROR", unavailableReason }`로 기존 surface 정책을 유지한다. 다른 symbol로 재조회하지 않는다.

- [ ] **Step 3: interval 변경 wiring**
  - `CANDLE_INTERVALS` key만 허용하고 UI label은 renderer가 담당한다.
  - 홈 interval 변경 handler는 key 변경 시에만 같은 `loadCandles`를 호출한다.
  - 종목 화면의 기존 handler와 `1m`/`1d` API 값을 그대로 유지한다.

### Task 5: Portfolio rail 재배치

**Files:**
- Modify: `web-dashboard/app/dashboard-view.js`
- Modify: `web-dashboard/app/route-workspace.js`
- Modify: `web-dashboard/app/market-overview-view.js`
- Modify: `web-dashboard/app/stock-analysis-product-surface.js`
- Modify: `web-dashboard/app/globals.css`

- [ ] **Step 1: DashboardView Portfolio 재사용 경로 추가**
  - 기존 `Portfolio`를 export하고 `includePortfolio` 기본값은 true로 둬 기존 route/test를 보존한다.
  - 홈에서는 `includePortfolio=false`로 main 중복을 막는다.

- [ ] **Step 2: 홈 DOM 순서와 desktop visual order 구현**
  - DOM은 `home-account-rail`을 먼저, `home-workspace-main`을 두 번째로 둔다.
  - desktop CSS grid에서 main은 왼쪽, rail은 오른쪽으로 배치하고 rail은 `position: sticky`/viewport 내부 scroll을 사용한다.
  - main에는 `MarketCandleChart`, 기존 `MarketOverviewView`, portfolio 제외 `DashboardView`를 둔다.
  - 계좌 rail은 기존 Portfolio의 현금·평가금액·P/L·주문 가능 금액·보유 종목을 그대로 렌더한다.

- [ ] **Step 3: 760px 이하 반응형 구현**
  - grid를 1열로 전환하고 DOM 순서 그대로 rail이 먼저 오도록 sticky를 해제한다.
  - focus order가 시각 순서와 일치하는지 CSS `order` 의존 없이 확인한다.

- [ ] **Step 4: 홈/stock chart placement**
  - 홈 main에 공유 `MarketCandleChart`를 첫 surface로 둔다.
  - 종목 화면의 기존 `CandleChartPanel`은 공유 renderer를 사용하되 기존 timeframe controls와 numeric table/provenance를 유지한다.

- [ ] **Step 5: targeted component tests 실행**

Run: `cd web-dashboard && node --test test/market-candle-chart.test.mjs test/home-candle-wiring.test.mjs test/market-overview-view.test.mjs test/dashboard-view.test.mjs test/stock-analysis-product-surface.test.mjs test/route-surface.test.mjs`

Expected: `PASS`.

- [ ] **Step 6: 커밋**

```bash
git add web-dashboard/app/dashboard-view.js web-dashboard/app/route-workspace.js web-dashboard/app/market-overview-view.js web-dashboard/app/stock-analysis-product-surface.js web-dashboard/app/market-candle-chart.js web-dashboard/app/globals.css web-dashboard/test/*.test.mjs
git commit -m "기능 :: 홈 차트 우선 계좌 rail"
```

## Chunk 3: E2E, axe, visual, build, review, publish

### Task 6: 실제 provider-shaped home/stock fixture 검증

**Files:**
- Modify: `web-dashboard/e2e/fixtures/states.mjs`
- Modify: `web-dashboard/e2e/state-matrix.spec.mjs`
- Modify: `web-dashboard/e2e/*.spec.mjs` only where existing home/stock assertions own the fixture

- [ ] **Step 1: 1m/1d Toss-shaped candle fixture 추가**
  - `data.candles` 최신순, provenance, asOf, normal/DEGRADED/error fixtures를 사용한다.
  - 임의 tick/price/volume을 browser-side에서 생성하지 않는다.

- [ ] **Step 2: home/stock E2E 실행**

Run: `cd web-dashboard && npm run e2e -- e2e/journeys.spec.mjs --grep "home|stock"`

Expected: home chart and stock timeframe selection render actual success/degraded states.

- [ ] **Step 3: axe/accessibility 실행**

Run: `cd web-dashboard && npm run e2e -- e2e/a11y.spec.mjs --grep "home|stock"`

Expected: no new violations; SVG/table/button semantics pass.

### Task 7: full verification and independent UI review

- [ ] **Step 1: unit suite**

Run: `cd web-dashboard && npm run lint:css && npm test`

- [ ] **Step 2: build**

Run: `cd web-dashboard && npm run build`

- [ ] **Step 3: visual review**

Run: `cd web-dashboard && npm run e2e -- e2e/state-matrix.spec.mjs --grep "home|stocks-AAPL"`

Review the generated 360/768/1280/1440 artifacts for rail position, chart prominence, red/blue semantics, and mobile DOM/focus order. Do not overwrite the currently dirty user-owned snapshots; if a baseline update is required, isolate only feature-owned snapshots and report the unrelated dirty baseline separately.

- [ ] **Step 4: independent UI review**

Dispatch a fresh `designer`/`critic` agent with the changed files and screenshots. Resolve only issues within this feature; preserve existing unrelated dirty files.

- [ ] **Step 5: final status check**

Run `git diff --check`, `git status --short`, and targeted/full tests again. Confirm no backend/API/provider/cache/chart dependency was added.

- [ ] **Step 6: user-requested publish stage — squash merge + push**

After all local checks, independent review, and CI are green, use `github:yeet` flow: stage only feature files, commit with Korean format/trailer, push `feature/home-candle-account-rail`, open/update PR, wait for CI, squash merge, then verify remote and local dirty user files are preserved. This is explicitly requested integration work, not a substitute for local verification.
