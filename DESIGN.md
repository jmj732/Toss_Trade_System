# Design

## Source of truth

- Status: Active
- Last refreshed: 2026-08-19
- Primary product surfaces: 홈(Decision Center), 포트폴리오, 종목 분석, 이벤트, 주문, 설정
- Evidence reviewed: `docs/superpowers/specs/2026-07-26-us-equity-trading-platform-design.md`,
  `docs/superpowers/specs/2026-08-18-adaptive-decision-workspace-delta.md`,
  `web-dashboard/fixdesign.md`, `web-dashboard/claudedocs/decision-workspace-redesign-plan.md`,
  `web-dashboard/app/route-workspace.js`, `web-dashboard/app/home-decision-center.js`,
  `web-dashboard/app/globals.css`, Toss Invest OpenAPI 1.2.4

## Brand

- Personality: 차분하고 전문적이며 확률과 위험을 숨기지 않는 포트폴리오 운영 도구
- Trust signals: 데이터 시점, 출처, 신뢰도, 반대 논리, 무효화 조건, 감사 기록
- Avoid: 확정 수익 표현, 과도한 매수 유도, 색상만으로 전달하는 손익, 카지노형 애니메이션

## Product goals

- Goals: 포트폴리오 중심 분석, 이벤트 재평가, 위험 통제 주문
- Non-goals: HFT, 무승인 자동매매, LLM 직접 주문
- Success signals: 최신성 표시, 재현 가능한 분석, 중복 없는 승인형 주문, 사용자 데이터 격리

## Personas and jobs

- Primary personas: 미국 주식을 직접 운용하는 개인 투자자
- User jobs: 계좌 확인, 위험 파악, 종목·이벤트 분석, 주문안 검토·승인, 과거 판단 평가
- Key contexts of use: 장 전 계획, 장중 이벤트 대응, 장 후 성과 검토

## Information architecture

- Primary navigation: 홈, 포트폴리오, 종목, 이벤트, 주문 (보조: 설정)
- Core routes/screens: `/`, `/portfolio`, `/stocks/[symbol]`, `/events`, `/orders`, `/settings`
- UI의 최소 단위는 종목·뉴스·주문이 아니라 **Action**이다. 모든 분석 결과는 가능한 행동으로 연결된다.
- Content hierarchy: 필요한 행동 → 위험 → 현재 판단 → 포트폴리오 영향 → 가격 → catalyst → 상세 분석 → 원자료
- 운영·모델 지표(예측 품질, 모델 레지스트리, drift, API key, provider health)는 투자 메인 네비게이션에
  두지 않고 설정 하위로 둔다.

## Adaptive surface state

- 화면 레이아웃은 고정하지 않는다. 표면 상태에 따라 각 영역의 weight(0 숨김 ~ 4 HERO)와
  first-viewport 배치가 바뀐다.
- 상태: `BLOCKED`(연결 필요) · `CRITICAL`(즉시 확인) · `RISK`(한도 초과) · `ACTIVE`(확인 필요) · `CALM`(확인할 결정 없음)
- 상태는 **서버가 준 사실만으로** 결정한다: 주문 상태 enum, 서버 timestamp와 현재 시각의 차이,
  보유 심볼 집합과의 교집합, 서버 위험 판정의 `breached` boolean, kill switch 상태.
- 상태 전이는 데이터 갱신 시점에만 일어난다. 스크롤·hover·타이머로 레이아웃이 바뀌지 않는다.
- 현재 상태는 화면에 텍스트로 노출한다. 사용자가 레이아웃 변화를 예측할 수 있어야 한다.
- 빈 상태나 정상 상태가 대형 카드를 차지하지 않는다(예: Action 0건 → 한 줄로 축소).

## Design principles

- Principle 1: 추천보다 위험과 데이터 시점을 먼저 보여준다.
- Principle 2: 분석, 승인, 주문, 체결 상태를 시각적으로 분리한다.
- Principle 3: **프론트는 판단값을 계산하지 않는다.** 가격·확률·위험도·비중·한도 사용률·매수/보유/매도
  판단은 서버가 source of truth다. 계약에 없는 값은 정직하게 숨기거나 명시적 "확인 불가"로 표시하며,
  추정값이나 기본값으로 채우지 않는다.
- Principle 4: 판단을 노출할 때는 규칙 버전과 근거 지표를 함께 노출한다. 판단 근거를 감춘 단정은 하지 않는다.
- Tradeoffs: 정보 밀도는 높게 유지하되 주문 확인 정보는 축약하지 않는다.

## Visual language

- Color: 밝은 중립 캔버스, Toss blue 계열 primary, 상승·하락·경고 색상 제한. 민트·네온·다크 운영 콘솔 톤 금지
- Typography: 큰 제목 대비, 숫자 가독성이 높은 sans-serif와 tabular numerals
- Spacing/layout rhythm: 8/12/24px 기반, 한 화면 한 목적, 넓은 여백
- Shape/radius/elevation: 12px control, 16–20px panel, 약한 shadow
- Motion: 상태 변화 설명에 필요한 짧은 전환만 사용
- Imagery/iconography: 장식 이미지 없이 의미가 명확한 아이콘

## Components

- Existing components to reuse: RouteWorkspace(Shell·상태 소유), HomeDecisionCenter, ActionQueue,
  PortfolioSummaryBar, PortfolioRiskPanel, PortfolioPositionTable, StockAnalysisProductSurface,
  EventWorkflow, OrdersView, OrderCreationPanel, OrderApprovalPanel, RiskPolicyPanel,
  BrokerOnboarding, MarketCandleChart, MarketOverviewView, PortfolioHistoryView,
  OperationsReadinessView, AnalysisOutcomeView, PaperPerformanceView, PredictionOperationsView
- Pure logic modules(React 의존 없음): `lib/surface-state.js`(상태 판정), `lib/action-model.js`(Action 변환)
- Variants and states: loading, stale, partial, blocked, review-required, unknown
- Card 규칙: 화면당 hero 카드는 최대 1개. 카드 안에 카드를 두지 않는다. 같은 질문에 답하는 카드가
  둘이면 통합한다. 빈 상태 카드는 렌더하지 않되 "없음"이 판단 정보인 경우만 한 줄로 남긴다.
- Order 실행 컨텍스트: Paper와 Live를 한 화면에서 탭으로 분리한다. `executionMode`가 확인되지 않은
  주문은 Paper로 접지 않고 별도 그룹으로 격리하고 실행 액션을 차단한다. Live는 승인(approve)과
  전송(dispatch)이 분리된 단계임을 UI에서 합치지 않는다.
- Token/component ownership: frontend design tokens와 공용 UI 컴포넌트가 소유

## Accessibility

- Target standard: WCAG 2.2 AA
- Keyboard/focus behavior: 모든 주문·설정 동작 키보드 접근, 명확한 focus ring
- Contrast/readability: 손익과 위험은 색상과 텍스트·아이콘을 함께 사용
- Screen-reader semantics: 표 caption/header, 상태 live region, 오류 연결
- Reduced motion and sensory considerations: reduced-motion 준수, 깜빡임 금지

## Responsive behavior

- Supported breakpoints/devices: 360 / 768 / 1280 / 1440 네 뷰포트를 light·dark 두 스킴에서 검증한다
- Layout adaptations: 표는 핵심 열 고정 후 상세 drawer로 이동. 1280 이상에서만 2열 분할하고
  3열 배치는 하지 않는다(밀도보다 흐름 우선). 모바일에서도 뷰포트가 아니라 **상태**가 우선순위를
  정한다 — 360에서도 CRITICAL이면 긴급 Action이 첫 번째다
- 가로 스크롤은 전 조합에서 0을 유지한다. 넓은 표·차트는 자기 컨테이너 안에서만 스크롤한다
- Touch/hover differences: hover 정보는 focus/tap으로도 접근 가능

## Interaction states

- Loading: 마지막 성공 시점과 skeleton 표시
- Empty: 계좌 연결 또는 데이터 수집 안내
- Error: 실패 범위와 재시도 가능 여부 표시
- Success: 완료 시각과 authoritative state 표시
- Disabled: 차단 사유를 항상 노출
- Offline/slow network: 주문 승인 비활성, 마지막 데이터는 stale 표시

## Content voice

- Tone: 짧고 사실 중심
- Terminology: 주문 후보, 예상 손실, 신뢰도, 무효화, 검토 필요
- Microcopy rules: “오른다” 대신 “상승 확률 추정”, “안전” 대신 구체적인 한도와 시점 표시

## Implementation constraints

- Framework/styling system: Next.js + TypeScript; 스타일링 도구는 구현 계획에서 최소 선택
- Design-token constraints: 색상, spacing, typography 최소 토큰만 정의
- Performance constraints: 대시보드 핵심 정보 우선 렌더, SSE는 갱신 힌트로만 사용
- Compatibility constraints: 최신 evergreen browsers
- Test/screenshot expectations: 주요 상태와 주문 승인 흐름의 접근성·반응형 검증

## Open questions

- [ ] 토스증권 다중 사용자 SaaS 자격증명 보관·주문 대행 허용 범위 / 제품 책임자 / 실거래 차단
- [ ] 미국 재무·컨센서스·옵션·거시 데이터 공급자와 라이선스 / 데이터 책임자 / 분석 범위
- [ ] 실거래 전 step-up 인증 방식 / 보안 책임자 / 주문 승인 UX
- [ ] 모바일에서 실거래 승인을 허용할지 / 제품 책임자 / 위험 통제
- [ ] `decision-rule-v1` 임계값 13종이 백테스트 근거 없는 관례값이다 / 제품 책임자 / 판단 신뢰도.
      RSI 70/30과 VIX 20/30만 출처가 있다. 가중치 배분(기술 0.65·재무 0.20·밸류 0.20·매크로 0.10)과
      필수 지표를 기술 4종으로 정한 것도 자의적이다. 변경은 `DECISION_SIGNALS` 상수 한 곳이다
- [ ] `ADD`/`REDUCE` 판단은 보유를 전제하는 단어인데 분석 서비스는 보유 여부를 모른다 / 제품 책임자 /
      문구. 현재는 프론트가 보유 상태와 교차해 해석해야 한다
- [ ] kill switch 실효 상태(GLOBAL·USER·ACCOUNT를 합친 판정) 계약 / 보안 책임자 / 거래 차단 표시.
      현재는 단일 scope 조회뿐이고 프론트가 OR 합성하지 않는다

Phase 0 확인 전에는 `PaperTradingBrokerAdapter`와 read-only fixture만 기본 활성화하고, 토스 credential 저장 API는 feature flag로 비활성화한다.
