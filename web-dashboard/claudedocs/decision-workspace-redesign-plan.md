# 투자 의사결정 워크스페이스 프론트 재설계 계획서

- 작성일: 2026-08-18
- 상태: **계획 · 구현 전 · 사용자 승인 필요**
- 기준 문서: `web-dashboard/fixdesign.md`, `DESIGN.md`, `web-dashboard/AGENTS.md`, `web-dashboard/app/AGENTS.md`
- 기준 코드: `main` @ `0fc2b88`
- 기준 계약: `contracts/analysis/v1·v2·v3·v4`, `trading-backend/src/main/java/com/jmj/trade/**`
- 기준 검증자산: `web-dashboard/test/*.test.mjs` (28개), `web-dashboard/e2e/state-matrix.spec.mjs` (route × state × 4 viewport × 2 scheme = 520 조합)

> 이 문서는 **계획서**다. 코드 변경은 포함하지 않는다. 섹션 13의 P0 착수는 사용자 승인 이후에 한다.

---

## 1. 현재 문제

### 1.1 구조 문제

| # | 문제 | 근거 |
|---|---|---|
| P-1 | **Action이 UI 단위가 아니다.** `ActionQueue`는 존재하지만 홈·포트폴리오 두 곳에서 중복 렌더되고, 항목은 "주문 제안"과 "이벤트" 두 소스만 단순 concat한다. 우선순위·기한·상태 개념이 없다 | `app/decision-center.js:16-49` (`buildActionItems`), `app/route-workspace.js:928`(portfolio), `:1149`(home) |
| P-2 | **홈이 Decision Center가 아니라 운영 대시보드다.** `homeLayout: "operations"` 경로는 데이터 상태 → 핵심 지표 4칸 → 추세 → 보유 → 검토 대기 → 이벤트/분석 → 시장 정보(캔들·환율·랭킹) 순으로 **고정** 배치된다. 긴급 항목이 있어도 레이아웃이 바뀌지 않는다 | `app/dashboard-view.js:438-476` |
| P-3 | **판단(Decision)이 화면 어디에도 실재하지 않는다.** UI는 `decision` / `confidence` / `positionPlan` / `portfolioRisk` / `nextCatalyst` / `portfolioImpact` 를 읽지만 **백엔드 계약에 해당 필드가 없다.** 전부 `UNKNOWN_TEXT`로 렌더된다 | 아래 1.2 표 |
| P-4 | **주문 흐름이 판단과 끊겨 있다.** Stock → `/orders?symbol=&side=` 쿼리로 심볼·side만 넘긴다. 참조가·제안수량·손절·출처 판단 ID는 전달되지 않는다 | `app/route-workspace.js:849-853`, `:289-298` |
| P-5 | **위험 검사 시점이 주문 생성 이후다.** `PreTradeRiskEngine`는 `approve()` 안에서만 돈다. 사용자는 "제안 생성"을 눌러 주문 의도를 만든 **뒤에야** 한도 초과를 안다 | `order/PaperOrderWorkflowService.java:110-160` |
| P-6 | **위험 정책이 "값"으로만 노출되고 "영향"이 없다.** 홈 핵심 지표의 리스크 칸은 `제한 N건`을 표시하는데, 스냅샷에 `limits` 맵 자체가 없어 **항상 `제한 0건`** 이다 | `app/dashboard-view.js:414-415` vs `risk/RiskPolicyService.java:219-227` |
| P-7 | **운영/모델 지표가 투자 화면과 같은 레벨에 있다.** `/predictions` 라우트가 살아 있고, `PredictionOperationsView`는 `/predictions`와 `/settings` **양쪽에서 렌더**된다 | `app/route-workspace.js:984-1024`, `:1032-1034` |
| P-8 | **비-홈 라우트마다 계좌 연결 섹션이 상단을 먹는다.** 판단 정보가 첫 스크롤 밖으로 밀린다 | `app/route-workspace.js:1192` |
| P-9 | **Stock 화면이 11개 패널 균등 나열이다.** 판단·계획·근거·원자료가 같은 `panel` 시각 무게를 갖는다 | `app/stock-analysis-product-surface.js:484-527` |
| P-10 | **Events가 수동 등록 워크플로 중심이다.** 백엔드에는 자동 수집(`intelligence/ingestion/MarketEventIngestionService`)이 이미 있는데 화면은 등록 폼이 먼저다 | `app/event-workflow.js:141-260` |

### 1.2 프론트가 읽지만 백엔드가 주지 않는 필드 (전부 `UNKNOWN` 렌더)

| 프론트 참조 | 위치 | 백엔드 실제 계약 | 판정 |
|---|---|---|---|
| `order.portfolioImpact` / `.decision` / `.reason` / `.source` | `decision-center.js:19-31` | `PendingProposalView(id, executionMode, side, type, symbol, quantity, limitPrice, currency, status, createdAt, expiresAt)` | 없음 |
| `event.portfolioImpact` / `.decision` / `.reason` / `.deadline` | `decision-center.js:34-46` | `ReviewSummary(... type, summary, affectedSymbols, macroScope, occurredAt, collectedAt, reviewStatus, reviewVersion, reviewedAt, comparisonAvailable)` | 없음 |
| `position.risk` / `.riskLevel` / `.nextCatalyst` / `.decision` | `decision-center.js:158-160` | `PositionView(symbol … sellableQuantity, observedAt)` — 판단 필드 없음 | 없음 |
| `analysis.result.portfolioRisk` / `.risk` | `decision-center.js:122` | 포트폴리오 분석 v1 응답 = `positions[]`, `currencyTotals[]`, `quality` | 없음 |
| `analysis.result.decision` / `.confidence` | `stock-analysis-product-surface.js:252-253` | 종목 분석 v3 = `analyzers[].metrics[]`, `missingData`, `observations`. `confidence`는 analyzer 단위만 존재 | 없음(부분) |
| `analysis.result.positionPlan.{entryPrice,stopPrice,targetPrice,riskReward,maxLoss}` | `stock-analysis-product-surface.js:219-230` | v3/v4 어디에도 없음 | 없음 |
| `riskPolicy.limits` | `dashboard-view.js:414-415` | `RiskPolicySnapshot(version, maxOrderAmountKrw, maxOrderAmountUsd, maxQuantity, maxConcentration, customized)` | 없음 |
| Kill switch 상태 조회 | UI 미연결 | `KillSwitchController`는 `POST`만 존재(상태 GET 없음) | 없음 |

**결론:** 지금 프론트는 이미 "Decision 중심 UI의 껍데기"를 갖고 있으나 **판단 데이터가 백엔드에 없어 빈 껍데기로 렌더된다.** 재설계의 절반은 레이아웃이 아니라 **계약 정의**다. fixdesign 42-10항("없는 데이터를 프론트에서 추정 금지")을 지키려면 이 표가 P0 설계의 제약 조건이다.

---

## 2. Surface별 핵심 결정 질문 (5~10초 내 답)

각 화면은 아래 질문에 **첫 화면(스크롤 없이)** 에서 답해야 한다. 답에 필요한 최소 데이터와 현재 가용성을 함께 적는다.

### 2.1 Global Shell
| Q | 필요 데이터 | 현재 |
|---|---|---|
| Q-S1 지금 이 화면 숫자를 믿어도 되는가? | `portfolio.stale/staleReason/partial`, `observedAt` | ✅ 있음 (`DataFreshnessIndicator`) |
| Q-S2 지금 시장이 열려 있는가? | `market-calendar.today.regularMarket` | ✅ 있음 |
| Q-S3 어느 계좌를 보고 있는가? | `GET /broker-connections` | ⚠️ 있으나 라벨이 `토스증권 · 계좌 1` (식별 불가) |
| Q-S4 지금 나를 기다리는 게 몇 건인가? | urgent/total action count | ❌ 없음 (알림 unread만 있음) |
| Q-S5 주문이 지금 가능한 상태인가? | kill switch 상태 | ❌ 조회 API 없음 |

### 2.2 Home
| Q | 필요 데이터 | 현재 |
|---|---|---|
| Q-H1 **지금 무엇을 해야 하는가 (1건)** | 우선순위 최상위 Action | ⚠️ 우선순위 개념 없음 |
| Q-H2 오늘 내 돈은 얼마 움직였는가 | `account.dailyProfitLossAmounts`, `profitLossAmounts` | ⚠️ 필드는 있으나 홈은 총평가·총손익만 표시 |
| Q-H3 내가 승인해야 할 주문이 있는가 | `pendingOrderProposals[status=PROPOSED]` | ✅ 있음 |
| Q-H4 위험한 포지션이 있는가 | 포지션별 위험 판정 | ❌ 없음 (BC-2) |
| Q-H5 오늘/내일 무슨 일이 있는가 | 이벤트 `occurredAt` + 예정 catalyst | ⚠️ 과거 이벤트만, 예정 일정 없음 |
| Q-H6 새 진입/청산 후보가 있는가 | Decision = BUY/SELL 후보 | ❌ 없음 (BC-3) |

### 2.3 Portfolio
| Q | 필요 데이터 | 현재 |
|---|---|---|
| Q-P1 어떤 포지션이 문제인가 | 포지션별 risk + 손익률 | ⚠️ 손익만 |
| Q-P2 무엇이 과도한가 (집중도) | `weight` vs `maxConcentration` | ⚠️ 두 값 존재, **판정 없음** (BC-4) |
| Q-P3 각 포지션을 유지/축소/청산 중 무엇을 해야 하는가 | 포지션별 Decision | ❌ 없음 (BC-2) |
| Q-P4 현금은 얼마 남았고 얼마 더 살 수 있는가 | `buyingPower` | ✅ 있음 |
| Q-P5 다음 이벤트는 언제인가 | 포지션별 next catalyst | ❌ 없음 (BC-2) |

### 2.4 Stock
| Q | 필요 데이터 | 현재 |
|---|---|---|
| Q-T1 지금 사야 하나/팔아야 하나/기다려야 하나 | `decision` + `confidence` | ❌ 없음 (BC-3) |
| Q-T2 얼마에 사고 어디서 자르나 | `positionPlan` | ❌ 없음 (BC-3) |
| Q-T3 얼마까지 잃을 수 있나 | `maxLoss` (수량 × (진입−손절)) | ❌ 없음 (BC-3) |
| Q-T4 이 판단이 언제 틀렸다고 인정하나 | `invalidation` | ❌ 없음 (BC-3) |
| Q-T5 내가 이 종목을 얼마나 갖고 있나 | position + weight | ✅ 있음 |
| Q-T6 이 화면 데이터는 믿을 만한가 | `missingData`, `provenance`, `status` | ✅ 있음(강점) |

### 2.5 Events
| Q | 필요 데이터 | 현재 |
|---|---|---|
| Q-E1 무슨 일이 일어났는가 | `type`, `summary`, `occurredAt` | ✅ 있음 |
| Q-E2 내 종목인가 | `affectedSymbols ∩ positions` | ⚠️ 조인 미구현(순수 집합 연산, 추정 아님) |
| Q-E3 내 포트폴리오가 얼마 움직였는가 | `ComparisonView.positions[].profitLossChange/weightChange` | ✅ 있음(강점, 저평가됨) |
| Q-E4 판단이 바뀌었는가 | previous/new decision | ❌ 없음 (BC-5) |
| Q-E5 그래서 뭘 해야 하는가 | required action | ❌ 없음 |

### 2.6 Orders
| Q | 필요 데이터 | 현재 |
|---|---|---|
| Q-O1 이 주문을 넣으면 한도에 걸리는가 | 사전 risk preview | ❌ 없음 (BC-6) |
| Q-O2 주문 후 내 포지션/현금/비중은 어떻게 되는가 | 사전 시뮬레이션 | ❌ 없음 (BC-6) |
| Q-O3 지금 승인 대기 주문이 뭔가 | `pendingOrderProposals` | ✅ 있음 |
| Q-O4 이 주문 지금 승인해도 되는가 | `approval-preview` + 만료 | ✅ 있음(강점) |
| Q-O5 넣은 주문이 어디까지 갔나 | `OrderIntentStatus` 13종 | ✅ 있음(강점) |

### 2.7 Settings
| Q | 필요 데이터 | 현재 |
|---|---|---|
| Q-G1 계좌가 정상 연결됐는가 | verify 결과 | ✅ 있음 |
| Q-G2 내 한도는 얼마인가 | risk policy | ✅ 있음 |
| Q-G3 데이터 공급자에 문제가 있는가 | readiness | ✅ 있음 |
| Q-G4 지금 거래를 멈출 수 있는가 | kill switch | ❌ UI 없음 (BC-7) |

---

## 3. 정보 가치 평가

### 3.1 평가 기준

각 컴포넌트/정보를 5개 축으로 1~5점 채점한다.

- **J 판단영향도**: 이 정보가 없으면 매수/매도/보유 판단이 달라지는가
- **U 긴급성**: 시간이 지나면 가치가 소멸하는가
- **M 금전영향**: 잘못 보면 돈을 잃는가
- **A 행동가능성**: 이 화면에서 바로 행동으로 이어지는가
- **D 데이터신뢰도**: 백엔드가 실제로 값을 주는가 (5=완전, 1=필드 부재)

**Verdict 규칙**
- `D ≤ 2` → 계약 확보 전까지 **표시 금지**(HOLD) 또는 명시적 "데이터 없음" 상태로만 노출
- `J+M ≥ 8` 그리고 `A ≥ 4` → **KEEP (Primary)**
- `J+M ≥ 6` 그리고 `A ≤ 2` → **COLLAPSE** (접기, 필요 시 펼침)
- 동일 질문에 답하는 컴포넌트가 2개 이상 → **MERGE**
- 투자 판단과 무관(운영/모델) → **MOVE** (Settings 하위)
- 어떤 결정 질문에도 매핑되지 않음 → **REMOVE**

### 3.2 컴포넌트 평가표

| 컴포넌트 (파일) | 현재 위치 | J | U | M | A | D | Verdict | 근거 |
|---|---|---|---|---|---|---|---|---|
| `ActionQueue` (`decision-center.js`) | Home + Portfolio | 5 | 5 | 5 | 5 | 2 | **KEEP + 재구축** | UI 단위. 단 우선순위·상태·기한 필드 부재 → BC-1 |
| `PortfolioRiskPanel` (`decision-center.js`) | Home + Portfolio | 5 | 4 | 5 | 3 | 1 | **HOLD → BC-4 후 KEEP** | `portfolioRisk` 필드 자체가 없음. 현재 항상 "데이터 없음" |
| `PortfolioPositionTable` (`decision-center.js`) | Portfolio | 5 | 3 | 5 | 4 | 3 | **KEEP (Primary)** | 10열 중 Risk/Catalyst/판단 3열은 D=1 → BC-2까지 열 숨김 |
| `Portfolio` (`dashboard-view.js`) | Home(compact) + 내부 | 4 | 3 | 5 | 2 | 5 | **MERGE → PositionTable** | 5열 보유표가 10열 표와 중복 |
| `Proposals` (`dashboard-view.js`) | Home | 5 | 5 | 5 | 5 | 5 | **MERGE → ActionQueue** | 승인 대기 = Action의 한 종류 |
| `OrdersView` (`orders-view.js`) | Orders | 4 | 4 | 5 | 4 | 5 | **KEEP** | 상태별 주문 큐. Proposals와 역할 분리(큐 전체 vs 행동 대기) |
| `OrderApprovalPanel` (`.jsx`) | Home + Orders | 5 | 5 | 5 | 5 | 5 | **KEEP (모달 단일화)** | 표시값 확인 + step-up. 두 곳 렌더 → 1곳 |
| `OrderCreationPanel` | Orders | 5 | 3 | 5 | 5 | 3 | **KEEP + 확장** | 사전 위험검사·시뮬레이션 없음 → BC-6 |
| `BuyingPowerBanner` (`orders-view.js`) | Orders | 4 | 2 | 4 | 3 | 5 | **MERGE → 주문 폼 헤더** | 주문 작성 맥락에서만 의미 |
| `StockSummary` + `PositionPlan` | Stock | 5 | 4 | 5 | 5 | 1 | **KEEP 구조 / HOLD 값** | Decision Header의 골격. 값은 BC-3 |
| `AnalysisPanel` | Stock | 4 | 2 | 4 | 2 | 5 | **COLLAPSE → Thesis 내부** | 지표 나열은 판단 근거이지 판단이 아님 |
| `ForecastPanel` | Stock | 3 | 2 | 3 | 1 | 5 | **COLLAPSE → Advanced** | 확률 지표는 2차 근거 |
| `ExplanationPanel` (Gemini) | Stock | 3 | 1 | 3 | 1 | 4 | **MERGE → InvestmentThesis** | fixdesign 19항: LLM 설명은 독립 메인 카드 금지 |
| `StockWarningsPanel` | Stock | 4 | 4 | 5 | 2 | 4 | **KEEP (Risk Panel로 승격)** | 경고는 판단 직결 |
| `CandleChartPanel` | Stock | 3 | 3 | 3 | 2 | 4 | **KEEP (Secondary)** | 가격 맥락. 이벤트 마커는 P2 |
| `OrderbookPanel` | Stock | 2 | 4 | 2 | 1 | 3 | **COLLAPSE → Advanced Data** | 개인 중장기 투자 판단에 낮은 기여 |
| `InvestorTradingPanel` | Stock | 2 | 2 | 2 | 1 | 3 | **COLLAPSE → Advanced Data** | 동일 |
| `CommissionsPanel` | Stock | 2 | 1 | 3 | 2 | 3 | **MOVE → 주문 작성 폼** | 수수료는 주문 시점 정보 |
| `RelatedEvents` | Stock | 4 | 4 | 4 | 3 | 5 | **KEEP** | 종목 catalyst 타임라인 |
| `SnapshotHistory` | Stock | 2 | 1 | 2 | 2 | 5 | **COLLAPSE (최하단)** | 재현성·감사용. DESIGN.md 신뢰 신호라 삭제 금지 |
| `MarketCandleChart` (홈) | Home | 2 | 2 | 2 | 1 | 4 | **MOVE → Stock** | 홈 캔들은 판단 질문에 미대응 (fixdesign 9항) |
| `RankingsWidget` (거래량/시총/등락률) | Home | 1 | 2 | 2 | 1 | 3 | **REMOVE** | 어떤 결정 질문에도 매핑 안 됨 |
| `ExchangeRateWidget` | Home | 3 | 2 | 4 | 1 | 4 | **COLLAPSE → Portfolio 헤더 보조** | USD/KRW는 평가금액 해석에 필요 |
| `MarketCalendarWidget` | Home | 3 | 3 | 2 | 1 | 4 | **MERGE → Shell 시장 상태 + 예정 이벤트** | 위젯 전체는 과함 |
| `RealtimePriceTicker` | Home + Stock | 2 | 4 | 2 | 1 | 3 | **REMOVE (홈) / MERGE (Stock 헤더 현재가)** | 고정 심볼 6종 하드코딩(`api.js:576`)은 내 포트폴리오와 무관 |
| `PortfolioHistoryTrend` / `PortfolioHistoryView` | Home / Portfolio | 3 | 1 | 4 | 1 | 5 | **COLLAPSE → Portfolio 하단** | 사후 확인 단계 정보 |
| `Analysis` (`dashboard-view.js`) | Home | 3 | 2 | 4 | 1 | 4 | **MERGE → PortfolioRiskPanel + PositionTable** | 통화별 합계·비중은 포지션 맥락에서 봐야 함 |
| `Events` (`dashboard-view.js`) | Home | 4 | 4 | 4 | 2 | 5 | **MERGE → ActionQueue + Upcoming** | 요약 리스트 중복 |
| `EventWorkflow` (등록폼+리스트+비교) | Events | 4 | 4 | 4 | 3 | 4 | **SPLIT**: 비교표 KEEP / 리스트 KEEP / **등록폼 COLLAPSE** | fixdesign 27항 |
| `Comparison` (event before/after) | Events | 5 | 3 | 5 | 3 | 5 | **KEEP (승격)** | 이미 존재하는 최고가치 자산. "내 돈에 얼마 영향" 직답 |
| `NotificationCenter` | Shell | 3 | 4 | 3 | 2 | 5 | **MERGE → ActionQueue 보조** | 알림과 Action 이중 큐 방지 |
| `RiskPolicyPanel` | Settings | 3 | 1 | 5 | 2 | 4 | **KEEP (Settings)** | 편집은 설정, 영향은 홈 |
| `BrokerOnboarding` | Home(landing) + Settings | 3 | 1 | 3 | 4 | 5 | **KEEP (Settings) + 랜딩 유지** | 연결 없으면 아무것도 못 함 |
| `AnalysisOutcomeView` (예측품질·모델 레지스트리) | /predictions | 1 | 1 | 2 | 1 | 5 | **MOVE → Settings/Analysis** | fixdesign 34항 |
| `PaperPerformanceView` | /predictions | 2 | 1 | 2 | 1 | 5 | **MOVE → Settings/Strategy** | fixdesign 35항 |
| `PredictionOperationsView` (+API Key) | /predictions **+** /settings | 1 | 1 | 2 | 1 | 5 | **MOVE → Settings only** (중복 제거) | 현재 2곳 렌더 |
| `OperationsReadinessView` | Settings | 2 | 3 | 2 | 2 | 5 | **KEEP (Settings)** | Provider health |
| `AccountSwitcher` | Shell / 라우트 상단 섹션 | 3 | 1 | 4 | 2 | 4 | **MERGE → Shell 단일** | 라우트별 중복 섹션 제거 |
| `GlobalStockSearch` / `MarketStatusIndicator` / `DataFreshnessIndicator` | Shell | 3 | 3 | 3 | 3 | 5 | **KEEP** | Shell 필수 3종 |

### 3.3 집계

- KEEP: 15 · MERGE: 9 · MOVE: 4 · COLLAPSE: 7 · REMOVE: 2 · HOLD(계약 대기): 2
- **홈에서 사라지는 컴포넌트: 7종** (캔들, 랭킹, 환율, 시장 캘린더 위젯, 실시간 티커, 히스토리 추세, 중복 보유표)
- **메인 네비게이션에서 사라지는 라우트: 1종** (`/predictions`)

---

## 4. 상태별 중요도 (Adaptive Weight Model)

컴포넌트의 크기·위치를 **고정하지 않는다.** 각 컴포넌트는 `weight 0~4`를 갖고, weight는 **서버가 준 사실**로만 계산한다(추정 금지).

### 4.1 Weight 스케일

| weight | 렌더 형태 | 예시 |
|---|---|---|
| 4 HERO | 첫 화면 최상단 전폭, 자동 펼침, `aria-live` 갱신 | URGENT Action |
| 3 PRIMARY | 첫 화면 내 주요 열, 펼침 | 승인 대기 주문 |
| 2 STANDARD | 기본 카드 | 포지션 표 |
| 1 COMPACT | 1~2줄 요약 + "자세히" | Action 0건일 때의 ActionQueue |
| 0 HIDDEN | `<details>` 접힘 또는 미렌더 | Advanced Data |

### 4.2 Surface 상태 머신 (Home 기준)

상태는 **서버 사실만**으로 결정한다.

```
urgentCount  = 주문 중 (MANUAL_REVIEW_REQUIRED | RECONCILIATION_REQUIRED | BLOCKED)
             + 주문 중 (status=PROPOSED && expiresAt - now <= 15분)
             + killSwitch.engaged === true            ← BC-7 필요
actionCount  = PROPOSED 주문 + reviewStatus=PENDING 이벤트
riskBreached = analysis.result.riskEvaluation.breached.length > 0   ← BC-4 필요
dataBroken   = portfolio.unavailable || analysis.unavailable
```

| 상태 | 진입 조건 | ActionQueue | Portfolio Summary | Risk | Positions | Trend/Advanced |
|---|---|---|---|---|---|---|
| **BLOCKED** | 연결 없음 or `dataBroken` | 0 | 0 | 0 | 0 | 0 (온보딩/에러 복구만) |
| **CRITICAL** | `urgentCount ≥ 1` | **4 HERO** (URGENT만 펼침, 나머지 접힘) | 1 COMPACT | 3 | 1 | 0 |
| **RISK** | `urgentCount=0 && riskBreached` | 3 | 2 | **4 HERO** (초과 항목만) | 3 | 0 |
| **ACTIVE** | `actionCount ≥ 1` | 3 PRIMARY | 2 | 2 COMPACT | 2 | 0 |
| **CALM** | `actionCount = 0` | **1 COMPACT** ("확인할 결정 없음") | 3 | 1 COMPACT | 3 PRIMARY | 1 |

### 4.3 컴포넌트별 상태 규칙 (전 화면 공통)

| 컴포넌트 | 축소 조건 | 확대/승격 조건 |
|---|---|---|
| ActionQueue | 항목 0건 → weight 1, 1줄 | URGENT ≥1 → weight 4, 최상단 고정 |
| PortfolioRiskPanel | 모든 한도 정상 → weight 1 (한 줄 "한도 내") | 한도 초과 → weight 4, **화면 최상위로 승격**, 초과 항목만 표시 |
| Positions Table | 보유 0 → weight 0 (빈 상태 안내) | CALM 상태 → weight 3, 열 전체 표시 |
| Data Freshness | LIVE → Shell 배지만 | STALE/PARTIAL → 해당 카드마다 인라인 경고 + 주문 버튼 비활성 사유 표기 |
| Upcoming Events | 7일 내 없음 → weight 0 | D-1 이내 항목 존재 → weight 3 |
| Order Approval | 대기 0 → 미렌더 | 대기 ≥1 → ActionQueue 상단 병합, 만료 임박 시 카운트다운 |
| Advanced Data (호가/투자자/스냅샷) | 항상 weight 0 | 사용자가 명시적으로 펼칠 때만 |

### 4.4 Action 우선순위 규칙 (P0에서 프론트 어댑터로 계산, P1에서 서버 이관)

**허용 입력은 서버가 준 enum·timestamp·집합 연산뿐이다.** 가격/위험/확률을 프론트에서 새로 계산하지 않는다.

| priority | 조건 (전부 서버 사실) |
|---|---|
| URGENT | 주문 `status ∈ {MANUAL_REVIEW_REQUIRED, RECONCILIATION_REQUIRED, BLOCKED}` · `PROPOSED && expiresAt-now ≤ 15m` · killSwitch engaged |
| HIGH | 주문 `status = PROPOSED` · 이벤트 `reviewStatus=PENDING && affectedSymbols ∩ 보유심볼 ≠ ∅` |
| MEDIUM | 이벤트 `reviewStatus=PENDING && 보유 무관` · 분석 `status=DEGRADED` |
| LOW | `portfolio.stale` · `partial` · `cashBalanceStatus=UNKNOWN` 등 데이터 품질 항목 |

> `expiresAt - now ≤ 15m`는 서버 타임스탬프 비교이므로 추정이 아니다. 임계값 15분은 **UI 정책 상수**로 문서화하고 `lib/` 한 곳에 둔다.

---

## 5. 삭제 · 통합 결정

### 5.1 삭제 (REMOVE)

| 대상 | 이유 | 영향 |
|---|---|---|
| 홈 `RankingsWidget` (거래량·시총·상승률·하락률) | 어떤 결정 질문에도 매핑 안 됨. fixdesign 9항 | `loadRankings` API 호출 제거, `market-overview-view.js`에서 위젯 삭제 |
| 홈 `RealtimePriceTicker` | 하드코딩 6종(`AAPL,MSFT,NVDA,GOOGL,AMZN,TSLA`)은 사용자 포트폴리오와 무관 | Stock 헤더 현재가로만 유지. 심볼 인자를 보유종목으로 바꾸는 대안은 P2 재검토 |

### 5.2 통합 (MERGE)

| 통합 후 | 흡수되는 것들 |
|---|---|
| **ActionQueue** | `Proposals`(홈), `Events`(홈), `NotificationCenter` 목록, 데이터 품질 경고 |
| **PositionTable** | `Portfolio`(dashboard-view 5열 표) + `Analysis` 종목 비중 리스트 |
| **PortfolioSummary** | 홈 core-metrics 4칸 + `Portfolio` hero + `ExchangeRateWidget` |
| **InvestmentThesis** | `AnalysisPanel` + `ExplanationPanel`(Gemini) + `ForecastPanel` 요약 |
| **StockRiskPanel** | `StockWarningsPanel` + 분석 `missingData` + 데이터 품질 |
| **OrderCreationPanel** | `BuyingPowerBanner` + `CommissionsPanel` |
| **Shell 시장 상태** | `MarketStatusIndicator` + `MarketCalendarWidget` 오늘 항목 |

### 5.3 이동 (MOVE)

| 대상 | 현재 | 이동 후 |
|---|---|---|
| `AnalysisOutcomeView` | `/predictions` | `/settings` → 분석·모델 운영 |
| `PredictionOperationsView` + API Key | `/predictions` **및** `/settings` (중복) | `/settings` 단일 |
| `PaperPerformanceView` | `/predictions` | `/settings` → 전략 성과 |
| 홈 캔들 차트 | `/` | `/stocks/[symbol]` 메인 차트 |
| 라우트별 `AccountSwitcher` 섹션 | 각 라우트 상단 | Global Shell 단일 |

### 5.4 라우트 변경

| 라우트 | 처리 |
|---|---|
| `/predictions` | 메인 nav 제거(이미 됨) → **P2에서 `/settings#analysis` 로 리다이렉트**, 페이지 삭제 |
| 신규 라우트 | 없음. IA는 기존 7 라우트 내에서 재구성한다 |

---

## 6. 정보 아키텍처 (Action을 단위로)

### 6.1 도메인 모델 (프론트 View Model)

```
ActionItem {                      // ← 화면의 최소 단위
  id, priority: URGENT|HIGH|MEDIUM|LOW,
  type: ORDER|EVENT|RISK|DATA_QUALITY,   // P0 가용 4종
  status: OPEN|RESOLVED,                 // 서버 상태 파생 (임의 상태 신설 금지)
  symbol?, title, factLine,              // factLine = 서버 값 문자열화만
  deadline?, sourceType, sourceId,
  availableActions: [승인검토|이벤트보기|종목보기|주문작성|보류|무시]
}
```
P0 어댑터 위치: `lib/action-model.js` (신규, 순수 함수, 테스트 가능). `decision-center.js`의 `buildActionItems`를 대체한다.
P1에서 서버가 `GET /actions`(BC-1)를 제공하면 어댑터는 **매핑만** 남기고 우선순위 계산을 제거한다.

### 6.2 네비게이션

```
Global Shell
├─ Home       ─ 지금 무엇을 해야 하는가        (Action)
├─ Portfolio  ─ 어떤 포지션을 어떻게 할 것인가  (Position × Decision)
├─ Stocks     ─ 이 종목을 어떻게 할 것인가      (Decision + Plan)
├─ Events     ─ 무엇이 바뀌었고 영향은 무엇인가 (Change → Impact)
└─ Orders     ─ 실행할 것인가                   (Draft → Risk → Approve)
보조: Settings (Broker / Risk / Data / Analysis·Model / System)
```

### 6.3 화면 간 계약 (Action → 다음 화면)

모든 Action은 최대 2조작 내 종착지에 도달해야 한다.

| Action type | 1차 버튼 | 종착 화면 | 전달 파라미터 |
|---|---|---|---|
| ORDER | 승인 검토 | 승인 모달(현 위치) | `orderId` |
| EVENT | 영향 보기 | `/events?event=` | `eventId` |
| RISK | 포지션 보기 | `/portfolio#symbol` | `symbol` |
| 공통 | 주문 작성 | `/orders?symbol&side&sourceType&sourceId` (+BC-3 이후 `refPrice`, `suggestedQty`, `stop`) | 위 목록 |
| DATA_QUALITY | 다시 동기화 | 현 위치 mutation | `connectionId` |

---

## 7. 제품 방향 3안

### 안 A — Action-first Decision Feed

홈은 **Action 피드 단일 컬럼**. 포트폴리오·위험·시장은 Action 카드 안의 맥락 줄로만 등장한다. 다른 라우트는 Action의 상세 뷰.

```
[Shell]
[URGENT: MRVL 승인 대기 · 만료 12분]   ← 카드 안에 포지션·한도·근거 요약
[HIGH  : CPI 발표 · 보유 3종목 영향]
[MEDIUM: AMD 분석 품질 저하]
[ 오늘 요약 1줄: 평가 $12,340 · +1.2% ]
[ 포지션 보기 → ]
```

- 장점: "지금 뭘 해야 하나"에 최단 경로. 모바일에 그대로 맞음. 정보 과잉 제거.
- 단점: Action 0건일 때 화면이 텅 빈다. 포트폴리오 전체 감각 상실. 자산 확인 목적 방문에 불리.
- 전제: Action 품질이 높아야 함 → **BC-1~BC-5 전부 필요**.

### 안 B — Portfolio-first Position Cockpit

홈 = 포트폴리오 요약 + 포지션 표. Action은 각 포지션 행의 배지/버튼으로 붙는다. 포지션에 귀속되지 않는 Action(거시·데이터 품질)은 표 위 알림 줄.

```
[Shell]
[총평가 $12,340  오늘 +$148  현금 $1,020  한도 정상]
[포지션 표: 종목 현재가 수량 비중 P/L Risk Catalyst 판단 | Action]
[  MRVL … 18% +7.2%  MED  D-9   HOLD   [승인대기 1] ]
[거시/데이터 알림 줄]
```

- 장점: 기존 자산 최대 재사용(`PortfolioPositionTable` 그대로). 항상 채워진 화면. 자산 확인·행동 둘 다 커버.
- 단점: 긴급 항목이 표 안에 묻힌다. 포지션 없는 신규 진입 기회를 표현할 자리가 없다. 모바일에서 10열 표가 무너진다.
- 전제: BC-2(포지션 판단/위험/catalyst)가 핵심. 없으면 표가 지금처럼 UNKNOWN 3열.

### 안 C — Adaptive Decision Center (상태 기반 우선순위) ★

섹션 4의 상태 머신을 그대로 제품 원칙으로 삼는다. 같은 컴포넌트 집합을 쓰되 **상태에 따라 무엇이 HERO인지 바뀐다.**

```
CRITICAL           RISK                ACTIVE              CALM
[URGENT Action ]   [한도 초과 3건 ]    [요약 1줄     ]     [총평가·오늘손익 ]
[  전폭 HERO   ]   [  전폭 HERO   ]    [Action │ Risk]     [확인할 결정 없음]
[요약 1줄      ]   [Action        ]    [포지션       ]     [포지션 표(확대) ]
[포지션(접힘)  ]   [포지션        ]    [예정 이벤트  ]     [추세·시장 맥락  ]
```

- 장점: Action 0건에도 무의미한 빈 화면이 안 나온다(→ B로 접힘). 긴급 시 A처럼 동작. fixdesign 43항 시각 우선순위를 상태로 구현.
- 단점: 상태 전이가 불투명하면 사용자가 "화면이 매번 다르다"고 느낀다. 상태 조합 × 뷰포트 테스트 매트릭스가 커진다.
- 전제: 상태 결정식이 **결정론적이고 문서화**되어야 하며, 화면에 현재 상태 라벨을 노출해야 한다.

---

## 8. 비교 및 추천

가중치: 상황파악 속도 ×3, 위험 발견성 ×3, 행동 명확성 ×3, 정보 밀도 ×2, 모바일 ×2, 기존 재사용 ×2, 구현 복잡도(낮을수록 고득점) ×1.

| 기준 (가중) | A Action-first | B Portfolio-first | C Adaptive |
|---|---|---|---|
| 상황 파악 속도 ×3 | 5 → 15 | 3 → 9 | 5 → 15 |
| 위험 발견성 ×3 | 4 → 12 | 3 → 9 | 5 → 15 |
| 행동 명확성 ×3 | 5 → 15 | 3 → 9 | 5 → 15 |
| 정보 밀도 ×2 | 2 → 4 | 5 → 10 | 4 → 8 |
| 모바일 적합성 ×2 | 5 → 10 | 2 → 4 | 4 → 8 |
| 기존 기능 재사용 ×2 | 3 → 6 | 5 → 10 | 4 → 8 |
| 구현 복잡도(역) ×1 | 4 → 4 | 5 → 5 | 2 → 2 |
| **합계 (max 80)** | **66** | **56** | **71** |

### 추천: **안 C — Adaptive Decision Center**

이유 세 가지.

1. **이 제품의 실제 사용 패턴이 이분법이다.** 장전 계획(Action 0~1건, 포트폴리오 확인)과 장중 대응(URGENT 발생)의 요구가 정반대다. 고정 레이아웃은 둘 중 하나에서 반드시 실패한다.
2. **위험 발견성이 유일하게 5점인 안이다.** 한도 초과를 "표 안의 한 셀"이 아니라 화면 최상위로 승격시킬 수 있는 구조는 C뿐이다. DESIGN.md 원칙 1(추천보다 위험 먼저)과 일치한다.
3. **A와 B를 버리지 않는다.** C의 CRITICAL 상태 = A, CALM 상태 = B다. 상태 머신만 추가하면 두 안의 자산을 모두 쓴다.

**채택 조건 (이 3개를 지키지 않으면 C는 B보다 나쁘다)**
- C-1 상태 결정식은 `lib/surface-state.js` 단일 순수 함수로 두고, 입력은 서버 값만 받는다. 컴포넌트 내부 분기 금지.
- C-2 현재 상태를 화면에 텍스트로 노출한다(예: 홈 헤더 `상태: 확인 필요 2건`). 사용자가 레이아웃 변화를 예측할 수 있어야 한다.
- C-3 상태 전이는 **데이터 갱신 시점에만** 일어난다. 스크롤/hover/타이머로 레이아웃이 바뀌지 않는다. 단, 만료 카운트다운은 텍스트만 갱신하고 weight는 15분 경계에서 1회만 바뀐다.

---

## 9. 시각 위계 · 카드 규칙 · 숫자/상태 표현

기존 토큰 시스템(`app/globals.css:1-60`, light/dark 이중 팔레트)을 **그대로 쓴다.** 신규 색·신규 radius를 만들지 않는다.

### 9.1 시각 위계 (fixdesign 43항 구현)

| 계층 | 내용 | 타이포/색 | 배치 |
|---|---|---|---|
| L1 | **필요한 행동** (Action title, 버튼) | `--fs-lg` / `--fw-bold` / `--text` | 화면 최상단, 버튼 primary 1개만 |
| L2 | **위험** (한도 초과, 손절 근접, 만료 임박) | `--danger-text` + 아이콘 + 텍스트 라벨 | L1 카드 내부 또는 승격 시 최상단 |
| L3 | **현재 Decision** | `--fs-base` / `--fw-bold` + 배지 | Action/포지션 행/종목 헤더 |
| L4 | **포트폴리오 영향** (금액·비중 변화) | `--fs-base`, tabular numerals | Decision 바로 아래 |
| L5 | 가격·차트 | `--fs-sm` | 2차 영역 |
| L6 | Catalyst/Event | `--fs-sm` / `--muted` | 2차 영역 |
| L7 | 상세 분석·근거 | `--fs-sm` | `<details>` 기본 접힘 |
| L8 | Raw/운영 데이터 | `--fs-xs` / `--muted` | 최하단 또는 Settings |

**색 규칙**: 색은 단독 신호가 아니다(DESIGN.md 접근성). 위험/손익은 항상 `색 + 텍스트 라벨(+부호)` 3중 표기. 기존 `.badge-pill--{ok|warn|danger|info|neutral}` 5종 외 추가 금지.

### 9.2 카드(panel) 사용 규칙

지금 문제는 "모든 것이 `.panel`"이라 위계가 없다는 점이다. 카드는 4종으로만 쓴다.

| 종류 | 클래스(제안) | 용도 | 규칙 |
|---|---|---|---|
| **Hero card** | `.panel.panel--hero` | 화면당 **최대 1개**. weight 4 컴포넌트 전용 | 전폭, 굵은 좌측 상태 보더(danger/warn), 내부에 primary 버튼 1개 |
| **Standard card** | `.panel` | 기본 정보 블록 | 제목 + 품질 배지 + 본문. 중첩 금지(카드 안 카드 금지) |
| **Inline group** | 클래스 없음, `<dl>`/`<table>` | 카드 내부 지표 묶음 | 자체 배경·보더 금지 |
| **Collapsed group** | `<details class="panel panel--collapsed">` | weight 0~1 | 요약 줄에 핵심 1개 사실 포함(예: "고급 데이터 · 호가 5단계") |

추가 규칙
- 카드 안에 카드가 있으면 통합 대상이다. (현재 위반: 홈 `home-market-context` `<details>` 안에 캔들·마켓·티커 패널 3중첩 → 삭제·이동으로 해소)
- 같은 화면에 같은 질문에 답하는 카드가 2개면 통합한다. (현재 위반: 홈 `Portfolio(compact)` + Portfolio 라우트 `PositionTable`)
- 빈 상태 카드는 **렌더하지 않는다.** 단, "없음"이 판단 정보인 경우(승인 대기 0건)는 1줄 COMPACT로 남긴다.

### 9.3 숫자 표현

| 항목 | 규칙 | 근거/현황 |
|---|---|---|
| 통화 금액 | 항상 `lib/format.js`의 `formatAmount(currency, value)` 경유. 통화 접두 필수 | 이미 강제됨 |
| 손익 | `formatSignedAmount` — 부호 + 색 + 라벨. 색만으로 방향 표시 금지 | 이미 있음 |
| 비율 | `formatRatio` — 소수 2자리 고정 | 이미 있음 |
| 미확정 값 | `UNKNOWN_TEXT` 유지. **0으로 대체 금지** | `lib/format.js` |
| 정렬 | 표의 모든 수치 열 우측 정렬 + `font-variant-numeric: tabular-nums` | 신규 CSS 1줄 |
| 시각 | 절대시각(`formatInstant`) + 상대신선도(`formatFreshness`) 병기. 기한은 카운트다운(`만료 12분 전`) | 카운트다운 신규 |

### 9.4 상태 표현

| 상태 | 표기 | 비고 |
|---|---|---|
| 데이터 품질 | 기존 `stale / unknown / unavailable / empty / partial / available` 6종 **어휘 고정** | `dashboard-view.js:Quality`. 신규 어휘 금지 |
| 주문 상태 | 기존 13종 한국어 매핑 유지 | `ORDER_STATUS_LABELS` |
| 이벤트 검토 | `PENDING / CONFIRMED / HELD / IGNORED` | 서버 enum |
| Action 우선순위 | `URGENT / HIGH / MEDIUM / LOW` 배지 (danger/warn/info/neutral) | 신규, 4단 고정 |
| Decision | 서버가 주는 enum을 **그대로** 표시. 없으면 "판단 없음" | BC-3 전까지 필드 미표시 |
| 차단 사유 | 버튼 비활성 시 **항상 사유 텍스트 동반** | DESIGN.md Disabled 규칙 |

---

## 10. 반응형 전략 (360 / 768 / 1280 / 1440)

현행 CSS는 `max-width: 900/760/480/360` + `min-width: 768/1280/1440` 혼재다. **min-width 기준 4단으로 정리**하고, e2e state-matrix가 이미 이 4개 뷰포트를 검증한다(`e2e/state-matrix.spec.mjs`).

| 뷰포트 | 그리드 | 규칙 |
|---|---|---|
| **360 (mobile)** | 1열 | 중요도 순 세로 배치. 표는 **카드형 리스트로 전환**(현재 10열 표는 가로 스크롤로 방치됨). Action 카드는 사실 3줄 + 버튼 1개로 축약. Advanced는 전부 접힘. Shell은 계좌·검색을 오버플로 메뉴로 |
| **768 (tablet)** | 1열 + 2열 부분 | Portfolio Summary 2×2, Action은 여전히 1열 전폭. 표는 핵심 5열(종목/비중/P/L/판단/행동) 고정 + 나머지는 행 확장 |
| **1280 (desktop)** | 12칼럼, 본문 max 1120px | Home 8/4 분할(Action | Risk·Regime). Stock 8/4(Chart | Position Plan). Events 5/7(Feed | Detail). Orders 5/7(Create | Queue) |
| **1440+** | 12칼럼, 본문 max 1360px(기존값 유지) | 열 폭만 확대, 열 개수 변경 없음. 3열 배치 금지(밀도보다 흐름 우선) |

공통 규칙
- 뷰포트가 아니라 **상태**가 우선순위를 정한다. 360에서도 CRITICAL이면 URGENT Action이 1번째다.
- 데스크톱 2열 → 모바일 1열 변환 시 **오른쪽 열이 아래로** 간다. 단 Risk가 weight 4로 승격된 경우에는 위로 올린다.
- 가로 스크롤 0 유지(현재 520조합 전부 overflow 0 — 회귀시키지 않는다).
- 터치 타깃 44px 이상, hover 전용 정보 금지(DESIGN.md).

---

## 11. 최종 Wireframe 및 컴포넌트 매핑

### 11.1 Global Shell

```
┌──────────────────────────────────────────────────────────────────────┐
│ TRADE   [종목 검색______]      계좌 ▼   ● 시장 OPEN  ● LIVE  🔔2  ⚙  │
├──────────────────────────────────────────────────────────────────────┤
│ Home │ Portfolio │ Stocks │ Events │ Orders          상태: 확인 2건   │
└──────────────────────────────────────────────────────────────────────┘
```
- 계좌 라벨: `토스증권 · ****1234`(displayAccountNumber). 연결 UUID 노출 금지(현행 준수).
- `상태:` 텍스트 = 섹션 4 상태 머신 노출(C-2 조건).
- 라우트별 `data-route-region="connection"` 섹션 **삭제** → Shell 단일.

| 기존 | 신규 |
|---|---|
| `AccountSwitcher`(2곳) | `GlobalAccountSwitcher` (Shell 1곳) |
| `GlobalStockSearch` | 유지 |
| `MarketStatusIndicator` | 유지 + 캘린더 위젯 오늘 항목 흡수 |
| `DataFreshnessIndicator` | 유지 |
| `NotificationCenter` | 배지·토글 유지, 목록은 ActionQueue로 이관 |
| `RouteNav` | 유지 (predictions 링크 없음 — 이미 반영됨) |

### 11.2 Home — 상태별 4종

**CRITICAL**
```
┌ ⚠ 지금 처리 ─────────────────────────────────────────────┐  ← .panel--hero
│ URGENT · ORDER   MRVL 매수 3주 @ $82.20                   │
│ 승인 대기 · 만료 12분 전 · 상태 승인 대기                  │
│ [승인 검토]  [주문 취소]                    [종목 보기 →] │
└───────────────────────────────────────────────────────────┘
[요약 1줄: 총평가 $12,340 · 오늘 +$148 · 현금 $1,020 · LIVE]
[▸ 나머지 결정 3건]        [▸ 포지션 6종]        [▸ 시장 맥락]
```

**RISK**
```
┌ ⚠ 한도 초과 ────────────────────────────────────────────┐
│ 종목 집중도 MRVL 21% > 한도 20%  ·  정책 v7               │
│ 영향: 신규 매수 차단 · 매도 주문만 가능                    │
│ [포지션 보기]  [한도 확인]                                │
└──────────────────────────────────────────────────────────┘
[Action Queue 2건] [포트폴리오 요약] [포지션 표]
```

**ACTIVE (기본형, 1280)**
```
┌ Portfolio Summary ───────────────────────────────────────────────────┐
│ 총평가 $12,340   오늘 +$148 (+1.2%)   현금 $1,020   주문가능 $1,020  │
│ 한도 정상 · 데이터 LIVE (2026-08-18 09:31 기준)                       │
└──────────────────────────────────────────────────────────────────────┘
┌ Action Queue (3)                       ─┬─ Portfolio Risk ───────────┐
│ HIGH  ORDER  NVDA 승인 대기 · 만료 2시간 │ 집중도  최대 18% / 한도 20%│
│ HIGH  EVENT  CPI 발표 · 보유 3종목 영향  │ 현금비중 8%                │
│ MED   EVENT  AMD 공시 · 미보유           │ 한도 상태 정상             │
│                                          ├─ Market Regime ───────────┤
│                                          │ 시장 OPEN · USD/KRW 1,382 │
└──────────────────────────────────────────┴───────────────────────────┘
┌ Positions (6)  종목 비중 P/L 판단 행동 ──────────────────────────────┐
┌ Upcoming (7일)  MRVL 실적 D-9 · FOMC D-3 ────────────────────────────┐
[▸ 지난 이벤트 · 데이터 품질 · 추세]
```

**CALM**
```
[Portfolio Summary 확대: 총평가 / 오늘 / 총손익 / 현금 / 주문가능]
[확인할 결정이 없습니다 · 마지막 확인 09:31            ▸ 지난 결정]
[Positions 표 (전체 열)]
[Portfolio Trend + Upcoming]
```

| 기존 | 신규 |
|---|---|
| `DashboardView(homeLayout:"operations")` | **삭제** → `HomeDecisionCenter` (상태 머신 + 조립만) |
| `home-core-metrics` 4칸 | `PortfolioSummaryBar` (오늘 손익·주문가능 추가, 리스크 정책 문자열 제거) |
| `Proposals`, `Events`(홈) | `ActionQueue` 항목으로 흡수 |
| `PortfolioRiskPanel` | 유지, weight 규칙 적용 (BC-4 전까지 "서버 위험 데이터 없음" 1줄 COMPACT) |
| `Portfolio(compact)` | `PositionsSummaryTable` (Portfolio 라우트와 공용 컴포넌트, 홈은 상위 5행) |
| `PortfolioHistoryTrend`(홈) | Portfolio 라우트로 이동 |
| `MarketCandleChart`(홈) | Stock으로 이동 |
| `MarketOverviewView`(홈) | 환율만 Summary 보조로, 랭킹·캘린더 위젯 삭제 |
| `RealtimePriceTicker`(홈) | 삭제 |

### 11.3 Portfolio

```
┌ Summary  총자산 / 투자금 / 현금 / 현금비중 / 오늘 P/L / 총 P/L ──────┐
┌ Risk  집중도 · 섹터 · 현금비중 · 손절근접 · 실적노출 · 주문제한 ─────┐  ← 초과 시 최상단 승격
┌ Positions ───────────────────────────────────────────────────────────┐
│ 종목  현재가  수량  평가금액  비중  P/L  Risk  Next Catalyst  판단  ⋯│
│ MRVL  $82.20   3   $246.60   18%  +7.2%  MED   실적 D-9      HOLD  ⋯│
└──────────────────────────────────────────────────────────────────────┘
[▸ Equity/P&L 추세 (기간 필터)]     [▸ 예정 이벤트 · 실적 일정]
```
- `Risk` / `Next Catalyst` / `판단` 열은 **BC-2 전까지 렌더하지 않는다**(UNKNOWN 3열 금지).
- 행 클릭 → `/stocks/{symbol}`, 행 우측 `주문` → `/orders?symbol=&side=`.

| 기존 | 신규 |
|---|---|
| `DecisionCenter`(portfolio에서 ActionQueue 중복) | **제거** — Action은 홈에만 |
| `PortfolioPositionTable` | `PositionsTable` (열 가시성을 계약 존재 여부로 제어) |
| `PortfolioHistoryView` | 하단 `<details>`로 이동 |
| `Analysis` 통화별/비중 | Summary + 표에 흡수 |

### 11.4 Stock

```
┌ Decision Header ─────────────────────────────────────────────────────┐
│ MRVL  Marvell   $82.31 +2.3%   기준 09:31                            │
│ 보유 3주 · 평균 $76.80 · P/L +7.2% · 비중 12%                        │
│ 판단 HOLD · 신뢰도 72% · 위험 MEDIUM · 데이터 부분                    │   ← BC-3
└──────────────────────────────────────────────────────────────────────┘
┌ Price Chart (이벤트 마커)          ─┬─ Position Plan ────────────────┐
│                                     │ 진입 $78 / 추가 $74            │
│                                     │ 손절 $71 / 목표 $95, $108      │
│                                     │ R:R 2.4 · 최대손실 $33         │
│                                     │ [이 계획으로 주문 작성]        │   ← BC-3
├─────────────────────────────────────┼────────────────────────────────┤
│ Investment Thesis                   │ Risk Panel                     │
│ (Bull/Bear/기대치 격차/무효화 조건) │ (경고·변동성·집중도·누락데이터)│
├─────────────────────────────────────┴────────────────────────────────┤
│ News / Filing / Earnings 타임라인                                     │
├───────────────────────────────────────────────────────────────────────┤
│ ▸ Advanced Data (호가 · 투자자 매매동향 · 예측 지표 · 스냅샷 이력)     │
└───────────────────────────────────────────────────────────────────────┘
```

| 기존 | 신규 |
|---|---|
| `StockSummary` | `DecisionHeader` (판단/신뢰도는 BC-3 이후 노출) |
| `PositionPlan`(현 stub) | `PositionPlan` (BC-3 이후 값 노출, 그전까지 "계획 없음" + 주문 작성 버튼만) |
| `AnalysisPanel` + `ExplanationPanel` + `ForecastPanel` | `InvestmentThesis` 1개 + `<details>` 내부 지표 |
| `StockWarningsPanel` | `StockRiskPanel`로 승격 (+ `missingData` 흡수) |
| `CandleChartPanel` | 메인 차트 (홈 캔들 흡수, 이벤트 마커는 P2) |
| `RelatedEvents` | 타임라인으로 재배치 |
| `OrderbookPanel` / `InvestorTradingPanel` / `SnapshotHistory` | Advanced Data `<details>` |
| `CommissionsPanel` | 주문 작성 폼으로 이동 |

### 11.5 Events

```
┌ 필터: 전체 | 보유종목 | 실적 | 공시 | 뉴스 | 거시 ────────────────────┐
┌ Feed (5) ───────────────┬─ Detail ────────────────────────────────────┐
│ 🔴 MRVL Guidance  09:12 │ MRVL Guidance 하향 · SEC · 09:12 수집 09:14 │
│ 🟠 CPI D-1              │ 영향 종목: MRVL(보유 3주, 비중 12%)         │
│ 🟢 AMD SEC Filing       │ 포트폴리오 영향(재분석 기준):               │
│                         │   평가금액 -$12.40 · 비중 12%→11.6%         │
│                         │ 검토 상태: PENDING                          │
│                         │ [재분석] [확인] [보류] [무시]               │
│                         │ [종목 보기] [주문 작성]                     │
└─────────────────────────┴─────────────────────────────────────────────┘
[▸ 이벤트 직접 추가]
```
- "포트폴리오 영향"은 **기존 `ComparisonView` 값 그대로** 쓴다(신규 계산 없음). 이 화면의 최대 자산이다.
- 판단 변화(이전→신규 Decision)는 BC-5 이후. 그전까지 비교표만.

| 기존 | 신규 |
|---|---|
| `EventWorkflow` 등록 폼 | `<details>` 최하단 |
| `EventList` | `EventFeed` (필터 + 보유종목 표식) |
| `Comparison` | `EventImpactDetail`로 승격 (Detail 본문 상단) |
| 이벤트 상세 액션 버튼 | 유지 + `주문 작성` 추가 |

### 11.6 Orders

```
┌ Order Draft ──────────────────┬─ Order Queue ────────────────────────┐
│ 심볼 MRVL   매수 ▾            │ 탭: 전체 | 진행 | 종료               │
│ 수량 3   시장가 ▾             │ ● 승인 대기  MRVL 매수 3 · 만료 12분 │
│ ─ 예상 ───────────────────    │ ● 체결 진행  NVDA 매도 1             │
│ 주문금액 $246.60              │ ○ 체결 완료  AMD 매수 2              │
│ 수수료 $0.18                  │                                      │
│ 주문 후 현금 $773             │                                      │
│ ─ 위험 검사(서버) ─────────   │                                      │
│ ✓ 주문금액 ✓ 수량 ✕ 집중도 21%│                                      │
│ [제안 생성]  ← 실패 시 비활성  │                                      │
└───────────────────────────────┴──────────────────────────────────────┘
        [승인 모달: 표시값 확인 + step-up + 승인/거절]
```
- 위험 검사 블록은 **BC-6(사전 preview) 이후 실값**. 그전까지는 현행대로 "제안 생성 후 서버 검사" 안내 문구를 유지하고 체크리스트를 표시하지 않는다.
- `주문 후 현금/비중`도 BC-6 응답 필드로만 표시한다. 프론트 계산 금지.

| 기존 | 신규 |
|---|---|
| `OrderCreationPanel` | 유지 + preview 결과 블록 + 수수료·주문가능금액 흡수 |
| `BuyingPowerBanner` | 폼 헤더로 병합 |
| `OrdersView` | 유지 (탭 3종 유지) |
| `OrderApprovalPanel` | 유지, **홈에서 제거하고 Orders 단일 마운트** + 홈 Action은 `/orders?order=` 로 이동 |

### 11.7 Settings

```
Settings
├ 계좌   토스 연결 · 계좌 목록 · 연결 확인 · 동기화 · 삭제      (BrokerOnboarding)
├ 위험   주문한도 KRW/USD · 최대수량 · 최대집중도 · 변경 이력   (RiskPolicyPanel)
│        [거래 중지 Kill Switch]                                 ← BC-7
├ 데이터 Provider 상태 · 신선도 · 준비 점검                      (OperationsReadinessView)
├ 분석   모델 레지스트리 · 예측 품질 · API Key                   (AnalysisOutcomeView + PredictionOperationsView)
└ 전략   Paper Trading 성과                                      (PaperPerformanceView)
```
- 5개 섹션은 모두 `<details>`, 기본 접힘. 기본 펼침은 "계좌" 하나.
- `/predictions` → `/settings#analysis` 리다이렉트 후 라우트 삭제.

---

## 12. 백엔드 최소 계약 (프론트 추정 금지 원칙에 따른 요구사항)

**원칙**: 아래 계약이 없는 값은 프론트에서 만들지 않는다. 해당 UI는 "데이터 없음" 상태로 렌더하거나 아예 렌더하지 않는다. 각 계약은 **최소 범위**로만 요청한다.

| ID | 계약 | 필요 이유 (결정 질문) | 최소 스펙 | 없을 때 프론트 처리 | 우선순위 |
|---|---|---|---|---|---|
| **BC-1** | Action 목록 | Q-H1, Q-S4 | `GET /api/v1/broker-connections/{id}/actions` → `[{id, priority, type, status, symbol?, title, factLine, deadline?, sourceType, sourceId, availableActions[]}]` | **P0는 프론트 어댑터로 대체**(주문 상태·이벤트 검토상태·타임스탬프·보유심볼 집합만 사용). P1에 서버 이관 | P1 |
| **BC-2** | 포지션별 판단 | Q-P1, Q-P3, Q-P5, Q-H4 | 포트폴리오 분석 응답 `positions[]`에 `decision`, `confidence`, `riskLevel`, `nextCatalystAt`, `nextCatalystType` 추가 (nullable) | 해당 3개 열 **미렌더** | P1 |
| **BC-3** | 종목 Decision + Position Plan | Q-T1~Q-T4 | 종목 분석 응답에 `decision`, `confidence`, `positionPlan{entry, add, stop, target1, target2, riskReward, maxLoss, invalidation}`, `thesis{bull[], bear[], expectationsGap, catalysts[]}` | Decision Header에서 판단/신뢰도 행 미표시, Position Plan은 "계획 없음" | P1 |
| **BC-4** | 포트폴리오 위험 평가 | Q-P2, Q-H4, RISK 상태 | 분석 응답에 `riskEvaluation{policyVersion, items:[{key, current, limit, usageRatio, breached}]}` — **판정은 서버가 한다** | `PortfolioRiskPanel` 1줄 COMPACT("서버 위험 데이터 없음"), RISK 상태 진입 불가 | **P0 (상태 머신 전제)** |
| **BC-5** | 이벤트 판단 변화 | Q-E4, Q-E5 | 이벤트 상세에 `previousDecision`, `newDecision`, `confidenceChange`, `requiredAction` | 비교표(금액·비중 변화)만 표시 | P2 |
| **BC-6** | 주문 사전 위험 미리보기 | Q-O1, Q-O2 | `POST /api/v1/paper-orders/preview` (비영속) → `{approved, reasons[PreTradeRiskEngine.Reason], orderAmount, commission, cashAfter, weightAfter, maxLoss}` | 현행 유지(제안 생성 후 검사), 체크리스트 미표시 | **P0 요청 / P1 반영** |
| **BC-7** | Kill Switch 상태 조회 | Q-S5, Q-G4, CRITICAL 상태 | `GET /api/v1/trading/kill-switch` → `{scope, engaged, version, changedAt, reason}` (POST는 이미 존재) | Shell에 거래중지 표시 없음, CRITICAL 조건에서 kill switch 항 제외 | P1 |
| **BC-8** | 위험 정책 응답 정합 | P-6 | `RiskPolicySnapshot`에 `limits` 맵을 넣거나, **프론트가 4개 필드를 직접 읽도록 수정** | 프론트 수정으로 해결 가능 → 계약 변경 불필요 | P0(프론트만) |
| **BC-9** | 예정 이벤트(캘린더) | Q-H5, Q-P5 | 실적/거시 일정 조회. `MarketEventIngestion`에 미래 일정이 포함되는지 확인 필요 | "예정 이벤트" 블록 미렌더 | P2 |

> BC-1, BC-2, BC-3, BC-5는 analysis-service 계약 버전 증가(v5/v6)를 수반한다. `contracts/analysis/` 픽스처와 백엔드 매퍼를 함께 갱신해야 하므로 **프론트 단독 작업이 아니다.** 별도 delta spec(`docs/superpowers/specs/`)으로 분리한다.

---

## 13. 구현 계획

각 단계는 독립 PR. `main` 직접 푸시 금지, feature branch → delta spec → 구현+테스트 → 리뷰 → squash merge.

### P0 — Shell·홈 상태 머신·삭제 정리 (프론트 단독, 백엔드 변경 0)

**목표**: 계약이 없어도 지금 가능한 "판단 흐름"을 만든다. 빈 껍데기 렌더를 없앤다.

| 항목 | 변경 범위 |
|---|---|
| P0-1 상태 머신 도입 | 신규 `lib/surface-state.js` (순수 함수 `resolveHomeState(dashboard, killSwitch?)` → `BLOCKED\|CRITICAL\|RISK\|ACTIVE\|CALM`) |
| P0-2 Action 모델 | 신규 `lib/action-model.js` — `buildActions({dashboard, events, positions})` + 우선순위 규칙(4.4). `decision-center.js`의 `buildActionItems` 대체 |
| P0-3 ActionQueue 재작성 | `app/decision-center.js` — 우선순위 배지, 만료 카운트다운, 사실 3줄, 버튼 최대 3개. **없는 필드(portfolioImpact/decision/reason/source) 렌더 제거** |
| P0-4 홈 재구성 | `app/route-workspace.js` 홈 분기 + `app/dashboard-view.js` `homeLayout:"operations"` 경로 삭제 → 신규 `app/home-decision-center.js` |
| P0-5 Shell 단일화 | 라우트별 `data-route-region="connection"` 섹션 제거, `AccountSwitcher`를 topbar로. 계좌 라벨에 `displayAccountNumber` 사용 |
| P0-6 홈 정리 | 랭킹·실시간 티커·홈 캔들·마켓 캘린더 위젯 제거, `loadRankings`/`loadRealtimePrices`(홈) 호출 제거 |
| P0-7 위계 CSS | `.panel--hero`, `.panel--collapsed`, tabular-nums, 12칼럼 그리드 정리. 기존 토큰만 사용 |
| P0-8 리스크 정책 표기 수정 | `dashboard-view.js:414-415` — `limits` 참조 제거, 실제 4개 필드 표기 또는 홈에서 제거 |
| P0-9 중복 마운트 정리 | `OrderApprovalPanel` 홈에서 제거(Orders 단일), `DecisionCenter` 포트폴리오에서 제거, `PredictionOperationsView` `/predictions`에서 제거 |

**삭제/통합 대상**: `RankingsWidget`, 홈 `RealtimePriceTicker`, 홈 `MarketCandleChart` 마운트, `DashboardView` operations 분기, `buildActionItems`, 홈 `Proposals`/`Events` 마운트, 라우트별 connection 섹션.

**테스트**
- 단위: `test/surface-state.test.mjs`(신규, 상태 5종 × 경계값), `test/action-model.test.mjs`(신규, 우선순위 4종 + 만료 경계 15분), `test/home-decision-center.test.mjs`(신규, 상태별 렌더 스냅샷)
- 회귀: 기존 28개 테스트 통과. 삭제 컴포넌트의 테스트 파일도 함께 제거(`market-overview-view.test.mjs`의 랭킹 케이스 등)
- e2e: `state-matrix.spec.mjs`에 홈 상태 5종 추가 → 조합 수 증가. **가로 스크롤 0, axe violation 0 유지**

**완료 조건**
1. 홈에서 UNKNOWN만 표시되는 필드가 0개
2. Action 0건일 때 홈이 포지션 중심으로 접히고, URGENT 1건일 때 최상단으로 승격됨을 e2e로 증명
3. 같은 컴포넌트가 두 라우트에 동시 마운트되지 않음
4. 4개 뷰포트 × light/dark 가로 스크롤 0, axe 0

---

### P1 — 판단 계약 반영 (백엔드 동반)

전제: BC-2, BC-3, BC-4, BC-7 확정. 계약별로 PR 분리.

| 항목 | 변경 범위 |
|---|---|
| P1-1 Portfolio 재구성 | `PositionsTable` 열 확장(Risk/Catalyst/판단), 계약 존재 여부로 열 가시성 제어 |
| P1-2 Portfolio Risk Panel | BC-4 `riskEvaluation` 렌더, 초과 시 최상단 승격(RISK 상태 활성화) |
| P1-3 Stock Decision Header | BC-3 `decision`/`confidence` 노출 |
| P1-4 Position Plan | BC-3 `positionPlan` 렌더 + `[이 계획으로 주문 작성]` → 파라미터 전달 |
| P1-5 Investment Thesis 통합 | `AnalysisPanel`+`ExplanationPanel`+`ForecastPanel` → 1개 카드 + Advanced 접기 |
| P1-6 Stock Risk Panel | `StockWarningsPanel` 승격 + `missingData` 흡수 |
| P1-7 주문 사전 검사 | BC-6 preview 연동, 실패 시 제안 생성 버튼 비활성 + 사유 표시 |
| P1-8 Kill Switch | BC-7 상태 Shell 표시 + Settings 조작 UI |
| P1-9 Action 서버 이관 | BC-1 도입 시 `action-model.js`를 매핑 전용으로 축소 |

**삭제/통합 대상**: `ForecastPanel`·`ExplanationPanel` 독립 카드, `stock-analysis-product-surface.js`의 11패널 균등 그리드.

**테스트**: 계약 픽스처 기반 컴포넌트 테스트(각 신규 필드의 null/부분/정상 3케이스), `contracts/` 픽스처 갱신, e2e에 `decision` 상태 추가.

**완료 조건**: Stock 화면 최상단만 보고 §46(fixdesign) 10개 항목 판단 가능. Portfolio에서 위험 포지션을 스크롤 없이 식별 가능.

---

### P2 — Events / Orders 흐름 완성

| 항목 | 변경 범위 |
|---|---|
| P2-1 Events 2열 재구성 | `EventFeed`(필터 6종 + 보유종목 표식) + `EventImpactDetail`(비교표 승격) |
| P2-2 수동 등록 강등 | 등록 폼 `<details>` 최하단 |
| P2-3 Event → Order | 이벤트 상세에서 주문 작성 진입 + 파라미터 전달 |
| P2-4 판단 변화 표시 | BC-5 반영 |
| P2-5 Orders 2열 + 시뮬레이션 | BC-6 응답의 주문 후 현금/비중/최대손실 표시 |
| P2-6 `/predictions` 제거 | `/settings#analysis` 리다이렉트 후 라우트 삭제, `analysis-outcome-view`/`paper-performance-view`/`prediction-operations-view`를 Settings 하위로 이동 |
| P2-7 Settings 5섹션 정리 | 전부 `<details>`, 기본 접힘 |
| P2-8 차트 이벤트 마커 | 실적/공시/주문/체결 마커 (데이터 있는 종류만) |

**삭제/통합 대상**: `app/predictions/page.js`, `EventWorkflow` 상단 등록 폼 배치, Settings 중복 `predictionOperationsView` 마운트.

**테스트**: `route-surface.test.mjs`에 `/predictions` 리다이렉트 케이스, Events 필터 조합, Orders preview 실패 케이스.

**완료 조건**: fixdesign §47(Events), §48(Orders) 완료 조건 전 항목 충족. 메인 nav에 운영 지표 0개.

---

### P3 — 밀도·접근성·성능 마감

| 항목 | 변경 범위 |
|---|---|
| P3-1 모바일 표 → 카드 리스트 | 360px에서 `PositionsTable`/`OrdersView` 카드 전환 |
| P3-2 키보드 흐름 | Action → 상세 → 주문 전 구간 키보드 도달, focus 순서 검증 |
| P3-3 live region 정리 | 상태 승격 시 `aria-live` 안내 문구 표준화 |
| P3-4 로딩 우선순위 | 홈에서 Action·요약 우선 렌더, Advanced는 지연 로드 |
| P3-5 Upcoming Events | BC-9 확보 시 예정 일정 블록 |
| P3-6 문서 동기화 | `DESIGN.md` IA·컴포넌트 절, `web-dashboard/AGENTS.md`(단일 SPA 서술은 이미 사실과 다름), `app/AGENTS.md` 갱신 |

**완료 조건**: 520+ e2e 조합 axe 0 / 가로 스크롤 0 유지, 문서와 코드 불일치 0.

---

## 14. 승인 필요 사항 (착수 전 확인)

1. **추천안 C(Adaptive) 채택 여부.** B(Portfolio-first 고정)로 가면 P0 범위가 절반으로 줄고 상태 머신·e2e 확장이 빠진다.
2. **P0에서 홈 삭제 대상 확정.** 랭킹·실시간 티커·홈 캔들 3종은 최근 커밋(`0ed1413`, `1780aa7`)에서 추가된 자산이다. 삭제가 아니라 Stock 이동만 원하면 알려주면 범위를 조정한다.
3. **BC-2/BC-3/BC-4 백엔드 작업 착수 여부.** 이것 없이는 Decision·Position Plan·Portfolio Risk가 영구히 빈 칸이다. P0만으로는 "빈 칸을 정직하게 감추는" 수준까지만 간다.
4. **`/predictions` 라우트 삭제 시점** (P2 제안). 지금 삭제하면 예측 품질 화면 접근 경로가 Settings 한 곳뿐이다.
5. **Paper vs Live 주문 경로.** 현재 프론트는 `paper-orders`가 기본이고 `live-orders`는 정정/step-up만 연결돼 있다. Orders 화면 재설계 시 두 모드를 한 화면에서 다룰지 분리할지 결정 필요.

---

## 부록 A — 현행 컴포넌트 → 신규 구조 매핑 (전체)

| 현행 파일 | 현행 export | 신규 위치 | 처리 |
|---|---|---|---|
| `route-workspace.js` | `RouteWorkspace` | 유지(상태 소유) + 홈 조립부 분리 | 축소 |
| `route-workspace.js` | `AccountSwitcher` | Shell | 이동·단일화 |
| `route-workspace.js` | `RouteNav`, `loginHref` | Shell | 유지 |
| `decision-center.js` | `buildActionItems` | `lib/action-model.js` | 대체 |
| `decision-center.js` | `ActionQueue` | Home | 재작성 |
| `decision-center.js` | `DecisionCenter` | — | 삭제(홈 전용 조립기로 흡수) |
| `decision-center.js` | `PortfolioRiskPanel` | Home + Portfolio | 유지(BC-4 대기) |
| `decision-center.js` | `PortfolioPositionTable` | Portfolio(+홈 요약) | 확장 |
| `decision-center.js` | `GlobalStockSearch`, `MarketStatusIndicator`, `DataFreshnessIndicator` | Shell | 유지 |
| `dashboard-view.js` | `DashboardView` | — | 삭제 |
| `dashboard-view.js` | `Portfolio`, `Analysis`, `Events`, `Proposals` | — | 통합 후 삭제 |
| `dashboard-view.js` | `RealtimePriceTicker` | Stock 헤더 | 축소 |
| `dashboard-view.js` | `ORDER_STATUS_LABELS`, `OrderStatusBadge`, `OrderExpiryBadge`, `OrderTiming`, `Quality` | 공용 유지 | **정본 유지 — 삭제 금지** |
| `stock-analysis-product-surface.js` | `StockAnalysisProductSurface` | Stock | 재배치 |
| `stock-analysis-product-surface.js` | 내부 11패널 | DecisionHeader / PositionPlan / Thesis / Risk / Chart / Timeline / Advanced | 재편 |
| `event-workflow.js` | `EventWorkflow` | Events | 분해 |
| `orders-view.js` | `OrdersView`, `BuyingPowerBanner` | Orders | 유지·병합 |
| `order-creation-panel.js` | `OrderCreationPanel` | Orders | 확장 |
| `order-approval-panel.jsx` | `OrderApprovalPanel` | Orders 단일 | 유지 |
| `market-overview-view.js` | `ExchangeRateWidget` | Portfolio 요약 보조 | 축소 |
| `market-overview-view.js` | `MarketCalendarWidget` | Shell 시장 상태 | 흡수 |
| `market-overview-view.js` | `RankingsWidget` | — | 삭제 |
| `market-candle-chart.js` | `MarketCandleChart` | Stock | 이동 |
| `portfolio-history-view.js` | `PortfolioHistoryView`, `PortfolioHistoryTrend` | Portfolio 하단 | 강등 |
| `notification-center.js` | `NotificationCenter` | Shell 배지 + ActionQueue | 분리 |
| `risk-policy-view.js` | `RiskPolicyPanel` | Settings | 유지 |
| `broker-onboarding.js` | `BrokerOnboarding` | Settings + 랜딩 | 유지 |
| `analysis-outcome-view.js` | `AnalysisOutcomeView` | Settings/분석 | 이동 |
| `paper-performance-view.js` | `PaperPerformanceView` | Settings/전략 | 이동 |
| `prediction-operations-view.js` | `PredictionOperationsView` | Settings/분석 | 이동·중복 제거 |
| `operations-readiness-view.js` | `OperationsReadinessView` | Settings/데이터 | 유지 |

## 부록 B — 유지해야 할 기존 강점 (재구성 중 유실 금지)

1. 데이터 품질 6종 어휘와 `Quality` 배지 (`dashboard-view.js`)
2. 주문 상태 13종 한국어 매핑과 미등록 상태 원문 노출 (D-03)
3. 만료 제안 승인 차단 (D-42)와 표시값 확인 승인 (`OrderApprovalPanel`)
4. `createSingleFlight` 중복 제출 방지, 주문별 busy Set (D-13)
5. 낙관적 동시성(`expectedVersion` / `reviewVersion`) 충돌 처리
6. `provenance` / `missingData` / `asOf` 출처·누락 표기
7. 연결 UUID 비노출 정책
8. `aria-live` 단일 status region, reduced-motion 대응, 520 조합 axe 0
