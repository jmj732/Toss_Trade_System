# Design

## Source of truth

- Status: Draft
- Last refreshed: 2026-07-26
- Primary product surfaces: dashboard, portfolio, stock analysis, event radar, orders, analysis history, settings
- Evidence reviewed: `docs/superpowers/specs/2026-07-26-us-equity-trading-platform-design.md`, Toss Invest OpenAPI 1.2.4

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

- Primary navigation: Dashboard, Portfolio, Stocks, Events, Orders, History, Settings
- Core routes/screens: `/dashboard`, `/portfolio`, `/stocks/[symbol]`, `/events`, `/orders`, `/analysis-history`, `/settings`
- Content hierarchy: 포트폴리오 위험 → 변동 원인 → 종목 근거 → 주문 행동

## Design principles

- Principle 1: 추천보다 위험과 데이터 시점을 먼저 보여준다.
- Principle 2: 분석, 승인, 주문, 체결 상태를 시각적으로 분리한다.
- Tradeoffs: 정보 밀도는 높게 유지하되 주문 확인 정보는 축약하지 않는다.

## Visual language

- Color: 다크 중립 배경, 제한된 상승·하락·경고 색상
- Typography: 숫자 가독성이 높은 sans-serif와 tabular numerals
- Spacing/layout rhythm: 4/8px 기반, 데스크톱 고밀도 카드와 표
- Shape/radius/elevation: 낮은 radius와 최소 elevation
- Motion: 상태 변화 설명에 필요한 짧은 전환만 사용
- Imagery/iconography: 장식 이미지 없이 의미가 명확한 아이콘

## Components

- Existing components to reuse: 없음
- New/changed components: MetricCard, FreshnessBadge, RiskLimitBar, PositionTable, ExposureChart, AnalysisScenario, EventImpactTable, OrderApprovalPanel, OrderStateTimeline, KillSwitch
- Variants and states: loading, stale, partial, blocked, review-required, unknown
- Token/component ownership: frontend design tokens와 공용 UI 컴포넌트가 소유

## Accessibility

- Target standard: WCAG 2.2 AA
- Keyboard/focus behavior: 모든 주문·설정 동작 키보드 접근, 명확한 focus ring
- Contrast/readability: 손익과 위험은 색상과 텍스트·아이콘을 함께 사용
- Screen-reader semantics: 표 caption/header, 상태 live region, 오류 연결
- Reduced motion and sensory considerations: reduced-motion 준수, 깜빡임 금지

## Responsive behavior

- Supported breakpoints/devices: desktop 우선, tablet과 mobile 조회·승인 지원
- Layout adaptations: 표는 핵심 열 고정 후 상세 drawer로 이동
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

Phase 0 확인 전에는 `PaperTradingBrokerAdapter`와 read-only fixture만 기본 활성화하고, 토스 credential 저장 API는 feature flag로 비활성화한다.
