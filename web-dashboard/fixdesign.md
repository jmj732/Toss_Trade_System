투자 의사결정 시스템 프론트 재구성 실행 문서

1. 목적

현재 프론트는 기능 수는 충분하지만, 기능별 화면과 카드가 분산되어 있어 사용자가 다음 질문에 빠르게 답하기 어렵다.

지금 내 포트폴리오에서 무엇을 해야 하는가?

프론트를 단순 조회형 증권 대시보드가 아니라 다음 흐름을 지원하는 개인용 투자 의사결정 시스템으로 재구성한다.

시장 데이터 수집
→ 분석
→ 포트폴리오 맥락 결합
→ 투자 판단
→ Action 생성
→ 사용자 검토
→ 주문 생성
→ Risk Check
→ 승인
→ Toss Open API 주문
→ 체결
→ 포트폴리오 갱신
→ 재분석

⸻

2. 핵심 설계 원칙

1. 기존 기능과 컴포넌트를 최대한 재사용한다.
2. 새로운 기능 추가보다 화면 정보 구조와 우선순위 변경을 먼저 수행한다.
3. 홈은 통계 대시보드가 아니라 Decision Center로 만든다.
4. 전체 시스템의 핵심 UI 단위는 종목, 뉴스, 이벤트가 아니라 Action이다.
5. 분석 결과는 반드시 가능한 행동으로 연결되어야 한다.
6. 위험 정보는 정책 값 자체보다 현재 포트폴리오에 미치는 영향을 우선 표시한다.
7. 사용자 투자 판단과 직접 관련 없는 운영 기능은 메인 Navigation에서 제거한다.
8. 계좌 연결 ID 같은 내부 식별자를 일반 사용자 인터페이스에 노출하지 않는다.
9. 주요 화면에서 주문 생성까지 이동하는 클릭 수를 최소화한다.
10. 데이터 기준 시각, 출처, 신선도, 부분 데이터 상태 등 기존 데이터 품질 기능은 유지한다.

⸻

3. 전체 Navigation 재구성

현재

홈
포트폴리오
종목
이벤트
주문
예측
설정

변경

Home
Portfolio
Stocks
Events
Orders

보조 메뉴:

Settings
System / Model Operations

다음 기능은 메인 Navigation에서 제거한다.

* Prediction Quality
* Model Registry
* Drift Monitoring
* Prediction Operations
* API Key 관리
* Provider 운영 상태

이 기능들은 Settings 또는 System 하위로 이동한다.

⸻

4. 전역 Shell 재구성

현재 제거 대상

* 연결 ID 직접 입력 계좌 변경
* 홈 상단 리스크 정책 직접 노출
* 화면별 중복된 계좌 관련 UI

새로운 Global Shell

┌──────────────────────────────────────────────────────────────┐
│ App Name       [종목 검색________________]                   │
│                                                              │
│ Account ▼      MARKET OPEN ●    DATA LIVE ●    알림   설정  │
├──────────────────────────────────────────────────────────────┤
│ Home │ Portfolio │ Stocks │ Events │ Orders                 │
└──────────────────────────────────────────────────────────────┘

Global Shell 필수 기능

* Account Switcher
* 종목 검색
* 시장 OPEN / CLOSED 표시
* 데이터 LIVE / STALE 표시
* 알림 센터
* Settings 진입
* 로그아웃

Account Switcher

현재 연결 ID 직접 입력 방식을 제거한다.

예:

Toss · 1234
Toss · 5678
────────────
계좌 관리

⸻

5. Home /

역할

Home은 Decision Center다.

사용자가 홈에 들어왔을 때 가장 먼저 다음 질문에 답할 수 있어야 한다.

지금 무엇을 해야 하는가?

⸻

5.1 상단 Portfolio Summary

표시:

* 총 평가금액
* 현금
* 주문 가능 금액
* 오늘 손익
* 총 손익
* Portfolio Risk
* 현재 Market Regime
* 데이터 상태

기존 홈의 리스크 정책 값은 주요 Summary에서 제거한다.

대신 다음과 같이 실제 영향만 표시한다.

예:

집중도 한도 임박
일일 손실 한도 62% 사용
신규 주문 가능
추가 매수 제한

⸻

6. Home 핵심 컴포넌트: ActionQueue

신규 핵심 컴포넌트:

ActionQueue

기존 다음 기능을 Action 중심으로 통합한다.

* Proposals
* Events
* Analysis
* Alerts
* Orders

Action 데이터 예시

URGENT | RISK        | MRVL | 손절선까지 2.1%
HIGH   | EARNINGS    | YSS  | 실적 발표 D-2
HIGH   | ORDER       | NVDA | 승인 대기 주문 존재
MEDIUM | OPPORTUNITY | AMD  | 돌파 기준 접근
LOW    | NEWS        | AAPL | 신규 공시 분석 완료

각 Action이 가져야 할 정보

* priority
* action type
* symbol
* title
* 발생 원인
* 현재 상황
* 포트폴리오 영향
* 시스템 판단
* 판단 이유
* 필요한 행동
* deadline 또는 event time
* 관련 데이터 출처
* 관련 화면 링크
* 주문 생성 가능 여부

Action 버튼 예

분석 보기
종목 보기
주문 생성
승인
무시
보류

⸻

7. Home 레이아웃

데스크톱:

┌──────────────────────────────────────────────────────────────┐
│ Portfolio Summary                                            │
├────────────────────────────────────────┬─────────────────────┤
│                                        │ Portfolio Risk      │
│ ActionQueue                            ├─────────────────────┤
│                                        │ Market Regime       │
├────────────────────────────────────────┴─────────────────────┤
│ Positions Summary                                            │
├──────────────────────────────────────────────────────────────┤
│ Upcoming Events                                              │
├──────────────────────────────────────────────────────────────┤
│ Intelligence Feed                                            │
└──────────────────────────────────────────────────────────────┘

모바일:

Portfolio Summary
↓
ActionQueue
↓
Portfolio Risk
↓
Market Regime
↓
Positions
↓
Upcoming Events
↓
Intelligence Feed

⸻

8. Intelligence Feed

통합 대상:

* 뉴스
* SEC 공시
* 실적
* Guidance
* 추정치 변화
* 가격 이상
* 분석 결과 변화
* 주문 상태
* 거시 이벤트

Feed의 목적은 모든 데이터를 보여주는 것이 아니다.

다음 정보를 빠르게 전달해야 한다.

무슨 일이 발생했는가
왜 중요한가
어떤 종목에 영향을 주는가
Action으로 승격되었는가

⸻

9. Home에서 우선순위를 낮출 기능

현재 홈에 있는 다음 기능은 삭제하거나 Secondary 영역으로 이동한다.

* 독립 캔들 차트
* 거래량 랭킹
* 시가총액 랭킹
* 상승률 랭킹
* 하락률 랭킹
* 리스크 정책 직접 편집
* 과도한 시장 상태 카드

Market Context로 필요한 최소 정보만 유지한다.

⸻

10. Portfolio /portfolio

역할

현재 자산을 조회하는 화면이 아니라 Position Management 화면으로 변경한다.

질문:

어떤 포지션을 유지하고, 줄이고, 추가하고, 청산해야 하는가?

⸻

11. Portfolio 상단 Summary

표시:

* 총자산
* 투자금
* 현금
* 현금 비중
* 오늘 P/L
* 총 P/L
* Portfolio Risk
* 최대 예상 손실

⸻

12. Positions Table

현재

종목
종목명
수량
평가금액
손익

변경

종목
현재가
수량
평가금액
비중
P/L
Risk
Next Catalyst
Decision
Action

예:

MRVL | $82.20 | 3 | $246.60 | 18% | +7.2% | MEDIUM | Earnings D-9 | HOLD | 상세

Decision 예:

BUY
ADD
HOLD
REDUCE
SELL
WAIT

또는 시스템의 실제 투자 판단 enum을 그대로 사용한다.

⸻

13. Portfolio Risk Panel

표시:

* 단일 종목 집중도
* 섹터 집중도
* 테마 집중도
* 현금 비중
* 최대 예상 손실
* 손절 기준 접근 종목
* 실적 이벤트 노출
* 상관관계 높은 포지션
* 주문 제한 여부

⸻

14. Portfolio 하단

유지 또는 재사용:

* 예정 이벤트
* 실적 일정
* 주요 거시 일정
* 공시 일정
* Portfolio Equity Chart
* P/L Chart
* 기간 필터

기존 컴포넌트 최대 재사용:

Portfolio
PortfolioHistoryTrend
Analysis
Events
RealtimePriceTicker

⸻

15. Stock /stocks/[symbol]

역할

질문:

이 종목을 지금 사고, 추가 매수하고, 보유하고, 줄이고, 팔아야 하는가?

현재처럼 모든 분석 카드를 동일한 비중으로 나열하지 않는다.

⸻

16. Stock 상단 Decision Header

필수 정보:

* 종목 Symbol
* 종목명
* 현재가
* 당일 등락률
* 기준 시각
* 보유 수량
* 평균 매수가
* 현재 손익
* Portfolio 비중
* Decision
* Confidence
* Risk Level
* Data Quality

예:

MRVL
$82.31 +2.3%
보유 3주
평균 $76.80
P/L +7.2%
비중 12%
Decision: HOLD
Confidence: 72%
Risk: MEDIUM

⸻

17. Stock 메인 레이아웃

┌────────────────────────────────────────┬─────────────────────┐
│                                        │ Position Plan       │
│ Price Chart                            │                     │
│                                        │ Entry               │
│ Event Markers                          │ Add                 │
│                                        │ Stop                │
│                                        │ Target 1            │
│                                        │ Target 2            │
│                                        │ R:R                 │
│                                        │ Max Loss            │
│                                        │                     │
│                                        │ [주문 생성]         │
├────────────────────────────────────────┼─────────────────────┤
│ Investment Thesis                      │ Risk                │
├────────────────────────────────────────┴─────────────────────┤
│ News / SEC / Earnings / Events                               │
├──────────────────────────────────────────────────────────────┤
│ Advanced Data                                                │
└──────────────────────────────────────────────────────────────┘

⸻

18. Position Plan

신규 또는 기존 분석 결과를 통합하여 생성한다.

표시:

* Entry
* Add Price
* Stop
* Target 1
* Target 2
* Risk / Reward
* Max Loss
* 현재 위치
* 주문 생성 버튼

Decision이 WAIT인 경우:

현재 진입 금지
조건: $XX 돌파
또는
$XX~$XX 눌림목

⸻

19. Investment Thesis

표시:

* Bull Case
* Bear Case
* Expectations Gap
* Catalyst
* 최근 기대치 변화
* 실적 추정치 변화
* 가격 반영 수준
* 아직 반영되지 않은 변화
* Thesis Invalidated 조건

설명형 LLM 출력은 이 영역 안에 포함한다.

LLM 설명 자체를 별도의 독립 메인 카드로 두지 않는다.

⸻

20. Stock Risk Panel

표시:

* Earnings Risk
* Volatility
* Concentration
* Liquidity
* Gap Risk
* Data Quality
* Missing Data
* Dilution Risk
* 기타 투자 경고

⸻

21. Chart Event Marker

가능하면 차트에 다음을 표시한다.

* Earnings
* Guidance
* SEC Filing
* News
* Analyst Revision
* 가격 이상
* 분석 Decision 변경
* 실제 주문
* 실제 체결

⸻

22. Stock 기존 컴포넌트 매핑

StockSummary
→ DecisionHeader
AnalysisPanel
→ InvestmentThesis
ForecastPanel
→ DecisionHeader + Advanced Analysis
ExplanationPanel
→ InvestmentThesis 내부 설명
StockWarningsPanel
→ RiskPanel
CandleChartPanel
→ Main Price Chart
RelatedEvents
→ News / Filing / Event Timeline
OrderbookPanel
→ Advanced Data
InvestorTradingPanel
→ Advanced Data
CommissionsPanel
→ Order Creation 또는 Advanced Data
SnapshotHistory
→ 화면 최하단

⸻

23. Events /events

역할

질문:

무슨 일이 발생했고, 내 포트폴리오에 어떤 영향을 주며, 그래서 무엇을 해야 하는가?

수동 이벤트 등록 중심 구조를 제거한다.

⸻

24. Event Feed 데이터

자동 수집 중심:

* 뉴스
* SEC 공시
* 실적
* Guidance
* 애널리스트 추정치 변화
* CPI
* PCE
* 고용
* FOMC
* 금리
* 환율
* 가격 급등락
* 기타 주요 이벤트

⸻

25. Events 레이아웃

┌──────────────────────────────┬───────────────────────────────┐
│ Event Feed                   │ Event Detail                  │
│                              │                               │
│ 🔴 MRVL Guidance             │ Event                         │
│ 🟠 CPI D-1                   │ Source                        │
│ 🟢 AMD SEC Filing            │ Affected Symbols              │
│                              │ Portfolio Impact              │
│                              │ Thesis Change                 │
│                              │ Previous Decision             │
│                              │ New Decision                  │
│                              │ Required Action               │
│                              │                               │
│                              │ [종목 보기] [주문 생성]      │
└──────────────────────────────┴───────────────────────────────┘

⸻

26. Event Filter

전체
보유종목
시장
실적
공시
뉴스
거시

⸻

27. Event Detail

필수 정보:

* 이벤트 유형
* 제목
* 내용 요약
* 발생 시각
* 수집 시각
* Source
* 영향 종목
* 현재 보유 여부
* 포트폴리오 영향
* 기존 Thesis
* 변경된 Thesis
* 기존 Decision
* 신규 Decision
* Confidence 변화
* 필요한 행동

버튼:

종목 분석
재분석
주문 생성
확인
보류
무시

수동 Event 등록은 다음으로 이동한다.

⋯
→ 이벤트 직접 추가

⸻

28. Orders /orders

역할

투자 판단을 실제 주문으로 안전하게 실행한다.

현재 주문 승인·취소·정정 기능은 유지한다.

추가 핵심 기능:

OrderCreationPanel

⸻

29. 전체 주문 흐름

Decision
→ Order Draft
→ Server Preview
→ Risk Check
→ Step-up Authentication
→ User Approval
→ Toss Open API
→ Order Submitted
→ Partial Fill / Fill
→ Portfolio Update

⸻

30. OrderCreationPanel

입력:

* Symbol
* Buy / Sell
* Quantity
* Market / Limit
* Limit Price

자동 계산:

* 예상 주문 금액
* 주문 후 Position Size
* 주문 후 Portfolio Weight
* 예상 최대 손실
* Stop 기준 위험금액
* 수수료
* 주문 후 현금
* 주문 후 Portfolio Risk

⸻

31. Order Risk Check

반드시 서버 정책 기준으로 검사한다.

예:

✓ 주문 금액
✓ 주문 수량
✓ 종목 집중도
✓ 테마 집중도
✓ 현금 비중
✓ Trade Risk
✓ Daily Loss
✓ Weekly Loss

실패 예:

✕ 종목 집중도 10% 초과

사용자가 왜 주문할 수 없는지 바로 이해할 수 있어야 한다.

⸻

32. Orders 레이아웃

┌────────────────────────────┬─────────────────────────────────┐
│ Order Creation             │ Order Queue                     │
│                            │                                 │
│ Symbol                     │ Pending Approval                │
│ Buy / Sell                 │ Approved                        │
│ Quantity                   │ Submitted                       │
│ Type                       │ Partial Fill                    │
│ Limit                      │ Filled                          │
│                            │ Cancelled                       │
│ Estimated Value            │ Rejected                        │
│ Risk                       │ Blocked                         │
│                            │                                 │
│ [주문 생성]                │                                 │
└────────────────────────────┴─────────────────────────────────┘

기존 OrderApprovalPanel은 유지한다.

⸻

33. 주문 생성 진입점

다음 화면에서 바로 주문 생성으로 이동 가능해야 한다.

Home ActionQueue
Portfolio Position
Stock Position Plan
Event Detail

전달 가능한 값은 자동으로 채운다.

예:

symbol
side
referencePrice
suggestedEntry
suggestedStop
suggestedQuantity
sourceDecisionId
sourceEventId

⸻

34. Predictions /predictions

메인 투자 Navigation에서 제거한다.

다음 기능은 시스템 운영 영역으로 이동한다.

Prediction Quality
MAE
Brier Score
Calibration
Drift
Model Registry
Evaluation Backlog
API Key
API Key Rotation
API Key Revocation

새 위치:

Settings
└─ Analysis / Model Operations

⸻

35. Paper Trading

Paper Trading Performance는 모델 운영과 분리한다.

가능한 위치:

Research
└─ Strategy Performance

또는 초기 단계에서는 Settings 내 Secondary 메뉴로 유지해도 된다.

⸻

36. Settings /settings

Settings는 시스템 구성과 운영만 담당한다.

Broker

* Toss 연결
* 계좌 관리
* 연결 확인
* Portfolio Sync
* 연결 정보 변경
* 연결 삭제

Risk

* 최대 KRW 주문 금액
* 최대 USD 주문 금액
* 최대 수량
* 최대 Position Concentration
* 최대 Theme Concentration
* Max Risk Per Trade
* Daily Loss Limit
* Weekly Loss Limit
* Kill Switch
* 정책 변경 이력

Data

* Toss
* SEC
* FRED
* BLS
* BEA
* FMP
* Finnhub
* 기타 Provider
* Provider 상태
* 데이터 신선도

Analysis

* Model Registry
* Prediction Quality
* Model Drift
* Evaluation Operations
* API Keys

System

* 운영 준비 상태
* Provider Health
* Live Canary
* 데이터 지연
* 시스템 상태

⸻

37. 기존 컴포넌트 재사용 우선순위

기존 구현을 삭제하거나 새로 만들기 전에 반드시 재사용 가능성을 확인한다.

우선 재사용 대상:

RouteWorkspace
BrokerOnboarding
DashboardView
MarketOverviewView
PortfolioHistoryView
StockAnalysisProductSurface
EventWorkflow
OrdersView
OrderApprovalPanel
AnalysisOutcomeView
PaperPerformanceView
PredictionOperationsView
OperationsReadinessView
RiskPolicyPanel

기능 로직은 유지하고 화면 구조와 컴포넌트 관계를 재조정한다.

⸻

38. 신규 핵심 컴포넌트

우선 필요한 신규 컴포넌트:

GlobalAccountSwitcher
GlobalStockSearch
MarketStatusIndicator
DataFreshnessIndicator
DecisionCenter
ActionQueue
ActionQueueItem
PortfolioRiskPanel
PositionDecisionCell
DecisionHeader
PositionPlan
InvestmentThesis
StockRiskPanel
EventTimeline
EventIntelligenceFeed
EventImpactDetail
OrderCreationPanel
OrderRiskCheck

필요 이상으로 새로운 컴포넌트를 만들지 않는다.

⸻

39. 데이터 모델 관점

UI에서 Action을 다음과 같은 공통 개념으로 다룰 수 있도록 설계한다.

예시:

Action {
  id
  priority
  type
  symbol
  title
  summary
  reason
  portfolioImpact
  decision
  previousDecision
  confidence
  deadline
  createdAt
  sourceType
  sourceId
  status
  availableActions
}

가능한 Action type 예:

RISK
OPPORTUNITY
EARNINGS
NEWS
FILING
MACRO
ORDER
PRICE_MOVE
ANALYSIS_CHANGE
DATA_QUALITY

Action status 예:

OPEN
ACKNOWLEDGED
DEFERRED
RESOLVED
IGNORED

실제 백엔드 모델이 다르면 기존 모델에 맞춰 UI Adapter 계층을 사용한다.

⸻

40. Decision 모델

가능한 경우 종목 분석 결과를 공통 Decision 형태로 UI에 전달한다.

예:

Decision {
  symbol
  action
  confidence
  thesis
  bullCase
  bearCase
  expectationsGap
  catalysts
  risks
  entry
  addPrice
  stop
  target1
  target2
  riskReward
  invalidation
  asOf
  sources
}

기존 분석 API가 이 구조를 직접 제공하지 않으면 프론트 View Model로 조합한다.

⸻

41. 구현 순서

P0

1. Global Shell 재구성
2. Account Switcher 구현
3. 홈을 Decision Center 구조로 변경
4. ActionQueue 구현
5. 기존 Proposal / Event / Order / Alert 데이터를 ActionQueue에 연결
6. Stock Decision Header 구현
7. Position Plan 구현
8. 신규 OrderCreationPanel 구현
9. Stock → Order 연결
10. ActionQueue → Order 연결

P1

1. Portfolio Position Management 구조로 변경
2. Portfolio Risk Panel 구현
3. Position별 Decision 표시
4. Event 화면을 Intelligence Feed 구조로 변경
5. Event → Stock 연결
6. Event → Order 연결
7. News / Filing / Earnings / Macro 이벤트와 ActionQueue 통합

P2

1. Predictions 메인 Navigation 제거
2. Model Operations를 Settings로 이동
3. API Key 관리 이동
4. Provider 운영 UI 이동
5. Home 저우선순위 컴포넌트 정리
6. Advanced Data 접기 또는 Secondary 영역으로 이동

⸻

42. 구현 금지 사항

다음은 하지 않는다.

1. 기존 API를 이유 없이 전면 교체하지 않는다.
2. 기존 컴포넌트를 재사용할 수 있는데 중복 구현하지 않는다.
3. 모든 정보를 Home에 표시하지 않는다.
4. MAE, Brier Score, Provider Health 같은 운영 지표를 투자 메인 화면에 노출하지 않는다.
5. 연결 ID를 일반 사용자에게 계좌 선택 방식으로 사용하게 하지 않는다.
6. 분석 카드들을 모두 동일한 시각적 우선순위로 배치하지 않는다.
7. 주문 생성 없이 승인 UI만 유지하지 않는다.
8. 투자 판단과 실제 주문 사이를 수동 복사·입력 흐름으로 만들지 않는다.
9. 데이터 신선도 및 출처 표시 기능을 제거하지 않는다.
10. 현재 백엔드에 존재하지 않는 데이터를 프론트에서 임의 계산하거나 추정하지 않는다.

⸻

43. UX 우선순위

시각적 우선순위:

1. 필요한 행동
2. 위험
3. 현재 Decision
4. Portfolio Impact
5. 가격과 차트
6. Catalyst / Event
7. 상세 분석
8. Raw Data / 운영 정보

⸻

44. Home 완료 조건

Home에서 사용자가 별도 라우트 이동 없이 다음을 확인할 수 있어야 한다.

* 지금 가장 중요한 Action
* 위험도가 높은 보유종목
* 오늘 또는 가까운 미래의 주요 이벤트
* 주문 승인 필요 여부
* 신규 진입 또는 청산 후보
* 전체 Portfolio Risk
* 현재 시장 환경
* 데이터가 최신인지 여부

Action에서 최대 1~2번의 사용자 조작으로 관련 Stock 또는 Order 화면에 도달할 수 있어야 한다.

⸻

45. Portfolio 완료 조건

Portfolio 화면에서 다음이 가능해야 한다.

* 각 Position의 비중 확인
* 각 Position의 P/L 확인
* 각 Position Risk 확인
* 다음 Catalyst 확인
* 현재 Decision 확인
* 위험한 Position 빠른 식별
* Position 상세 이동
* 주문 생성 이동
* 전체 Portfolio Risk 확인

⸻

46. Stock 완료 조건

Stock 화면 최상단만 보고 다음을 판단할 수 있어야 한다.

* 보유 중인지
* 현재 손익
* 현재 Decision
* Confidence
* Risk
* Entry
* Stop
* Target
* 다음 주요 Catalyst
* 주문 필요 여부

Raw 분석 결과를 읽지 않아도 기본 투자 판단이 가능해야 한다.

⸻

47. Events 완료 조건

Event를 클릭하면 다음이 보여야 한다.

무슨 일이 발생했는가
어떤 종목에 영향을 주는가
내가 해당 종목을 보유하고 있는가
기존 분석에서 무엇이 달라졌는가
Decision이 바뀌었는가
현재 어떤 행동이 필요한가

⸻

48. Orders 완료 조건

다음 전체 흐름이 프론트에서 연결되어야 한다.

Stock / Action / Event
→ 주문 생성
→ Risk Check
→ Server Preview
→ Step-up
→ 승인
→ Toss 주문
→ 주문 상태
→ Fill

⸻

49. Responsive

Desktop:

* Decision Center는 좌우 분할 가능
* Stock은 Chart + Position Plan 2열
* Events는 Feed + Detail 2열
* Orders는 Order Creation + Order Queue 2열

Mobile:

모든 핵심 화면은 중요도 순서로 1열 배치한다.

Mobile 우선순위:

Decision
→ Action
→ Risk
→ Price
→ Events
→ Details

⸻

50. 접근성 및 기존 상태 처리

기존 기능을 유지한다.

* Keyboard Navigation
* aria-live
* Loading
* Error
* Retry
* Partial Data
* Stale Data
* Unsupported State
* Data timestamp
* Data source

상태 표시는 화면 재구성 과정에서 누락시키지 않는다.

⸻

51. 최종 제품 원칙

이 프로젝트는 다음 형태가 되어서는 안 된다.

가격 보는 화면
뉴스 보는 화면
공시 보는 화면
예측 보는 화면
주문 보는 화면

다음 형태여야 한다.

무슨 일이 일어났는가
↓
왜 중요한가
↓
내 Portfolio에 어떤 영향을 주는가
↓
기존 투자 Thesis가 바뀌었는가
↓
현재 Decision은 무엇인가
↓
지금 무엇을 해야 하는가
↓
실행할 것인가

모든 주요 UI 재구성은 이 흐름을 기준으로 판단한다.

⸻

52. 최종 구현 목표

기존 프론트 기능을 최대한 유지하면서 다음 핵심 흐름을 하나의 시스템으로 연결한다.

Market Intelligence
→ Analysis
→ Portfolio Context
→ Decision
→ ActionQueue
→ Order
→ Risk
→ Approval
→ Execution
→ Portfolio Update

프론트의 최종 목적은 사용자가 데이터를 직접 조합해서 결론을 내리게 하는 것이 아니다.

시스템이 데이터를 분석하고 중요도를 정리하여 사용자에게 다음을 명확하게 전달해야 한다.

지금 무슨 일이 일어났고,
왜 중요한지,
내 돈에 어떤 영향을 주며,
그래서 지금 무엇을 해야 하는지.

이 문서를 기준으로 현재 구현을 재구성한다.
