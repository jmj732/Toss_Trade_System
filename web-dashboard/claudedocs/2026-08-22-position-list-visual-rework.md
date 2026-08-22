# 포트폴리오 리스트·그래프 시각 디자인 재작업

- 날짜: 2026-08-22
- 브랜치: `feat/adaptive-decision-workspace`
- 범위: web-dashboard Home / Portfolio / Stock 의 보유 포지션 표현과 자체 제작 그래프
- 불변: backend / API / 주문 의미 계약은 변경하지 않음

---

## 1. 결정 사항

### 1.1 종목 이미지 — 항상 initials 폴백

리포에 종목 이미지 소스가 **존재하지 않는다**:

- backend `Position.java` 에 `logo` / `image` / `icon` 계열 필드 없음
- 리포 전체 `logoUrl|imageUrl` grep 결과 0건
- `web-dashboard/public/` 디렉터리 없음

따라서 `<img>` + `onError` 폴백 경로는 만들지 않았다(로드할 URL 자체가 없다). `PositionAvatar` 는 항상
ticker initials(1~2글자, 심볼 없으면 `?`) 원형 아바타만 렌더한다. 외부 URL·스크래핑·새 provider 추가 없음.

배경색은 심볼 문자 코드 합 → 고정 4색 팔레트(`--avatar-0..3`) 결정론적 선택. 흰 글자를 얹으므로
**WCAG 2.2 AA 4.5:1 이상**으로 맞췄다(현재 대비 6.67 / 7.67 / 6.01 / 6.33). 초기 팔레트는 axe 에서
`.position-avatar--2` 가 3.94:1 로 실패했고, 그 실패를 근거로 팔레트를 어둡게 재조정했다.
대비는 페이지 배경이 아니라 아바타 배경 대 글자색으로만 결정되므로 다크 전용 오버라이드는 제거했다.

### 1.2 손익 표기 — 통화 접두 유지, 부호는 금액에만

`+$31.19` 의 `$` 는 채택하지 않았다. `lib/format.js` 는 "통화를 모르면 금액을 단독 노출하지 않는다"를
불변조건으로 두고 KRW/USD 다통화가 같은 포맷터를 통과한다. 예시가 지정한 것은 **형태**(부호 + 금액 +
괄호 비율)이므로 통화 접두를 유지한 채 형태만 맞췄다.

```
formatSignedAmountWithRate("USD",  31.19,  0.0295) → "USD +31.19 (2.95%)"
formatSignedAmountWithRate("USD", -28.58, -0.0381) → "USD -28.58 (3.81%)"
```

- 괄호 안 비율에는 부호를 반복하지 않는다(사용자 예시 `-$28.58 (3.81%)` 와 동일).
- `formatRatio` 기본 자릿수가 1이라 `(2.9%)` 가 되므로 이 포맷터는 2자리를 넘긴다.
- 비율은 서버 `profitLossRate` 만 쓴다. 금액에서 역산하지 않는다.

### 1.3 ▲/▼ 제거와 접근성

방향 기호를 지우면 방향이 **색만으로** 전달되어 WCAG 위반이 된다(리포 CSS 주석도 "색이 단독으로
정보를 전달하면 안 된다"고 명시). 그래서:

- 시각 채널: 금액의 `+` / `-` 부호
- 접근성 채널: `aria-label` 에 방향 낱말(상승 / 하락 / 보합) 유지 — `directionOf()` 가 단일 출처
- 0 과 null 에는 색을 입히지 않는다(변동 없음 / 미확인)

---

## 2. 그래프 전수 감사 결과

기준: **mini chart 는 실제 추세·비교 의미가 있을 때만**, **meter 는 `현재값 / 서버가 제공한 한도` 일 때만**.

| 대상 | 위치 | 판정 | 근거 |
|---|---|---|---|
| `SummaryTrendSparkline` | `home-decision-center.js` | **삭제** | 72×22 안에 축·범례 없이 두 통화를 각자 다른 스케일로 정규화해 겹쳐 그림. 파일 자체 주석이 "서로 비교 가능한 값이 아니다"라고 자인 — 오독 위험만 있는 장식 |
| 비중 `Meter` | `decision-center.js` 포지션 표 | **삭제** | `weight / 1`. 전체 대비 몫이지 서버가 준 한도가 아니다. `formatRatio` 텍스트만 남김 |
| 신뢰도 `Meter` | `stock-analysis-product-surface.js` | **삭제** | `confidence / 1`. 0..1 점수이지 한도가 아니다 |
| 위험 `Meter` | `decision-center.js` `PortfolioRiskPanel` | **유지** | `usageRatio / limit` — 서버가 내려주는 실제 한도. 다만 트랙 두께 6px → 4px 로 낮춤 |
| `.trend-usd` 점선 | `globals.css` | **재설계** | `stroke-dasharray: 6 4` 제거. 통화 구분은 색과 범례가 이미 하고 있어 점선은 장식이고 작은 높이에서 조잡하다 |
| `Trend` 스파크라인 | `portfolio-history-view.js` | **유지 + 재설계** | 실제 추세 + 범례 있음. 채워진 차트 블록 배경 제거 → 투명 + 기준선 1px, stroke 2 → 1.5 round |
| `.trend-pnl` (equity) | `paper-performance-view.js` | **유지 + 재설계** | 실제 equity curve. 위와 동일하게 얇게 |
| `PositionPlanRange` | `stock-analysis-product-surface.js` | **유지 + 재설계** | 실측 가격 마커 + 텍스트 범례(위치·색만으로 정보 전달하지 않음). 트랙 8px → 4px, 틱 3px → 2px, 현재가 점 14px → 10px |
| `market-candle-chart` | Stock 화면 | **유지** | 큰 차트는 Stock 화면에 집중한다는 방향과 일치 |
| `.live-order-notice` 의 dashed border | `globals.css` | **유지** | 그래프가 아니라 경고 notice |

---

## 3. 범위 밖으로 둔 것

- `app/dashboard-view.js` 의 `Portfolio()` 안에 **두 번째 포지션 `<table>`** 이 있다(헤더 종목/종목명/수량/평가금액/P/L).
  `DashboardView` 는 어떤 라우트에서도 쓰이지 않고 `test/dashboard-view.test.mjs` 만 참조한다 — 사실상 죽은 코드다.
  건드리지 않았다. 정리 여부는 별도 판단 필요.

---

## 4. Stock 화면 — 마젠타 막대는 앱 결함이 아니었다

`분석` / `예측` / `Gemini 설명` 패널 헤더 아래의 형광 분홍 전폭 막대는 **Playwright 스크린샷 마스크**다.

- `e2e/state-matrix.spec.mjs` 가 시각 회귀 결정성 확보용으로 `.disclaimer` 등 타임스탬프 노드를 마스킹한다.
- Playwright 기본 `maskColor` 가 `#FF00FF` 이고, 마스킹된 요소의 bounding box 를 그 색으로 덮어 그린다.
- `.disclaimer` 를 렌더하는 패널이 정확히 그 3개 → 막대 3개와 일치.
- 통제 실험: 마스크 없이 raw `page.screenshot` 으로 렌더하면 마젠타가 전혀 없고 `.disclaimer` 텍스트가 정상 표시.
  `getComputedStyle` 전수 스캔에서 마젠타 배경 노드 0건. app/lib/CSS/git 이력 어디에도 마젠타 리터럴 없음.

**조치: 코드 변경 없음.** 커밋된 baseline 에도 원래 있던 것이며, 앱 CSS 를 고치는 것은 오진 대응이다.
정말 색을 죽이려면 spec 의 `maskColor` 를 지정해야 하는데 그러면 매트릭스 전체 베이스라인이 무효화된다 —
이번 범위 밖.

함께 고친 Stock 화면 결함:

- `R:R` 이 `1.7778268638` 원시 float 로 노출 → `lib/format.js` 에 `formatDecimal()` 추가, `1.78` 로 표기.
- 시세 차트 캔들이 패널 상하단에 붙고 볼륨 블록과 겹쳐 보임 → `.market-candle-svg` padding + 캔들 stroke
  `vector-effect: non-scaling-stroke`. **좌표 계산은 건드리지 않음.** 볼륨 블록은 실제 거래량 데이터라 유지.

---

## 5. 수정 파일

| 파일 | 내용 |
|---|---|
| `lib/format.js` | `formatSignedAmountWithRate()`, `directionOf()`, `formatDecimal()` 추가 |
| `app/decision-center.js` | `PositionAvatar` 신규, `PortfolioPositionTable` 을 `<table>` → `<ul class="position-list">` 로 교체, 밀도 2단계, 비중 Meter 삭제 |
| `app/home-decision-center.js` | `RateChange` 에서 ▲/▼ 제거, `SummaryTrendSparkline` 삭제 |
| `app/stock-analysis-product-surface.js` | 신뢰도 Meter 삭제, R:R 포맷 |
| `app/globals.css` | position list 스타일 신규, 아바타 팔레트(AA 대비), meter/sparkline/price-range 재설계, 미사용 표 CSS 정리 |
| `test/format.test.mjs`, `test/decision-center.test.mjs` | 신규 포맷터·row 구조·아바타·손익 표기 테스트, `scope="row"` → `data-position-row` 재앵커 |
| `e2e/state-matrix.spec.mjs-snapshots/*.png` | 120장 갱신(변경된 화면만. 신규·삭제 파일 없음, 총 488장 유지) |

### 밀도 파생 규칙

`PortfolioPositionTable` 의 `detail` 을 넘기지 않으면 `positionDecisions` 배열 존재 여부로 파생된다.

- Home: `positionDecisions` 미전달 → **compact**. 아바타 · 티커/종목명 · 수량/현재가/주문 링크 · 평가금액/손익.
  Risk/판단/비중은 렌더되지 않는다.
- `/portfolio`: `route-workspace.js` 가 섹션을 전달 → **full**. secondary line 에 비중 + BC-2 Risk/판단 추가.

`route-workspace.js` 는 수정하지 않았다(호출부 변경 없이 파생으로 처리).

### 지켜낸 불변조건

BC-2 3분기 문구는 서로 다른 원인이라 합치지 않았다:

- `확인 불가` — `riskLevel == null` (판정 근거 없음, "안전" 아님)
- `판단 보류 · 지표 부족` — `decisionRunId != null && decision == null` (분석했으나 판단 없음)
- `분석 없음` — `decisionRunId == null` (분석한 적 없음)

`data-risk-level` / `data-decision-state` / `data-position-risk` / `data-position-decision` 속성도 유지.

---

## 6. 실행 결과 (실제로 돌린 것만)

| 게이트 | 명령 | 결과 |
|---|---|---|
| unit | `npm test` | **329 pass / 0 fail** |
| CSS lint | `npx stylelint "app/**/*.css"` | exit 0 |
| build | `npm run build` | ✓ Compiled successfully |
| e2e 전체 | `TRADE_E2E_PORT=3177 npx playwright test` | **1030 passed / 0 failed** (242 skipped — project 스코프 지정 spec) |
| a11y (axe, WCAG 2.2 AA) | 위 실행에 포함 | **488 통과**, 위반 0 |
| visual matrix | `... --update-snapshots` 후 재실행 | **488 통과** |

### 눈으로 확인한 뷰포트

360 / 768 / 1280 / 1440 전부에서 실제 렌더 PNG 를 열어 확인했다(스냅샷 재생성만으로 검증했다고 보지 않음):

- 360 Home(decision-calm), 768 Home(decision-risk)
- 360 · 1280 · 1440 Portfolio
- 1280 Stock

### ⚠️ 실행 중 발견한 함정

**포트 3000 이 무관한 프로세스(`ieumai-proxy`)에 점유돼 있었다.** `playwright.config.mjs` 의
`webServer.reuseExistingServer: true` 때문에 Playwright 가 그 프록시를 앱으로 착각해 재사용했고,
첫 e2e 실행 결과가 전부 무효였다(axe 가 `document-title` / `html-has-lang` 위반을 보고 —
`app/layout.js` 는 둘 다 정상인데도).

**다음 세션에서도 반드시 확인할 것**: `lsof -ti:3000` 으로 점유 확인 후,
`npx next dev -p 3177` 로 띄우고 `TRADE_E2E_PORT=3177` 를 붙여 실행할 것.

### a11y 회귀 1건 — 잡아서 고침

초기 아바타 팔레트에서 `.position-avatar--2`(#2f8f7a)가 흰 글자 대비 3.94:1 로 WCAG AA 실패
(4 combination fail). 팔레트를 6.0:1 이상으로 재조정해 해결. 재실행 후 488/488 통과.

---

## 7. 상태 분류

분류 기준: 구현과 필요한 검증까지 끝난 것만 **완료**. 코드만 쓰였거나 E2E/visual/review/commit/push 가
남았으면 **부분 완료**.

| 항목 | 상태 | 완료된 것 | 남은 것 |
|---|---|---|---|
| 손익 표기 포맷터 (`+금액 (%)`) | 부분 완료 | 구현 + unit | 커밋, 코드 리뷰 |
| ▲/▼ 제거 + 접근성 방향 낱말 유지 | 부분 완료 | 구현 + unit + axe | 커밋, 코드 리뷰 |
| compact position list (Home/Portfolio) | 부분 완료 | 구현 + unit + e2e + visual + 4뷰포트 육안 확인 | 커밋, 코드 리뷰 |
| 종목 아바타(initials 폴백) + AA 대비 | 부분 완료 | 구현 + axe 488 통과(회귀 1건 잡아 수정) | 커밋, 코드 리뷰 |
| 그래프 전수 감사 및 삭제/재설계 | 부분 완료 | 감사표 + 삭제/재설계 + visual | 커밋, 코드 리뷰 |
| Stock 화면 R:R · 캔들 여백 | 부분 완료 | 구현 + unit + 렌더 확인 | 커밋, 코드 리뷰 |
| **커밋 · 푸시 · 코드 리뷰** | 미완료 | — | 아래 재개 지점 |

검증 게이트 자체(unit / stylelint / build / e2e / axe / visual)는 **현재 작업 트리 기준으로 전부 실행되어
통과했다** — 마지막 CSS 변경(`.position-row { align-items: flex-start }`) 이후 전체 e2e 를 다시 돌려
1030 passed / 0 failed 를 확인했다.

## 8. 재개 지점

작업 트리에 커밋되지 않은 변경이 남아 있다. `git status`:

```
 M app/decision-center.js
 M app/globals.css
 M app/home-decision-center.js
 M app/stock-analysis-product-surface.js
 M lib/format.js
 M test/decision-center.test.mjs
 M test/format.test.mjs
 M e2e/state-matrix.spec.mjs-snapshots/*.png   (120장)
?? claudedocs/2026-08-22-position-list-visual-rework.md
```

다음 세션에서 바로 할 일:

1. `git diff` 확인 후 커밋. 원자 단위로 나눈다면 이 순서가 자연스럽다:
   1. `lib/format.js` + `test/format.test.mjs` (표시 포맷터 계약)
   2. `app/decision-center.js` + `app/home-decision-center.js` + `app/globals.css` + `test/decision-center.test.mjs` (position list 재설계)
   3. `app/stock-analysis-product-surface.js` (+ 해당 CSS hunk) (Stock 화면 정리)
   4. `e2e/state-matrix.spec.mjs-snapshots/` (베이스라인 갱신) + `claudedocs/`
   - 나누지 않고 한 커밋으로 가도 무방하다. 검증은 이미 전부 통과한 상태다.
2. 커밋 자체는 코드를 바꾸지 않으므로 그 직후 재검증은 불필요하다. 다만 push / PR 전에는
   `npm run verify` 를 한 번 더 돌릴 것 — **단 `npm run verify` 는 `npm run e2e` 를 포트 3000 기본값으로
   부르므로 위 "포트 3000 함정"을 먼저 처리해야 한다**(안 하면 무관한 프록시를 앱으로 착각한 채 통과/실패한다).
3. 코드 리뷰 미실행. `/code-review` 로 브랜치 diff 리뷰를 한 번 받는 것을 권한다.

### 후속 후보 (이번 범위 밖, 별도 판단 필요)

- `app/dashboard-view.js` 의 죽은 두 번째 포지션 `<table>` 정리 (§3)
- `e2e/state-matrix.spec.mjs` 의 `maskColor` 지정 여부 — 하면 매트릭스 전체 베이스라인 재생성 필요 (§4)
- `/portfolio` 는 서버가 `positionDecisions` 섹션을 생략하면 compact 로 강등되어 비중이 사라진다.
  이는 요구된 파생 규칙 그대로의 결과이나, 원치 않으면 `route-workspace.js` 에서 `detail: "full"` 을
  명시적으로 넘기면 된다.
