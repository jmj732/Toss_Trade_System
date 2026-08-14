# 홈 대시보드 재구성 디자인

## 범위

로그인 후 홈(`/`)만 재구성한다. 포트폴리오, 종목, 이벤트, 주문, 분석, 설정 라우트의 정보 구조는 이번 작업에서 바꾸지 않는다.

배포 기준 화면은 [web-dashboard-phi-lac.vercel.app](https://web-dashboard-phi-lac.vercel.app/)의 인증 전 진입 화면이다. 배포판은 `내 투자, 한눈에` 로그인 게이트만 노출하므로, 인증 후 홈은 현재 저장소의 실제 API와 상태 매트릭스를 기능 기준으로 삼는다.

## 디자인 판독

이 작업은 규제된 미국 주식 운영 도구의 홈 재설계다. 차분한 한국어 제품 언어, 최신성·위험·승인 대기를 우선하는 운영 커맨드센터를 목표로 한다.

- 재설계 모드: Redesign - Preserve
- `DESIGN_VARIANCE`: 3. 정돈된 운영 화면, 제한적인 비대칭
- `MOTION_INTENSITY`: 2. 상태 전환과 focus만 짧게 전환
- `VISUAL_DENSITY`: 6. 정보 밀도는 유지하되 핵심 영역에 여백 제공
- 시각 언어: 밝은 중립 캔버스, Toss blue 계열 accent, 얕은 경계선, 단일 radius 체계
- 적용 스킬: `redesign-existing-projects`, `design-taste-frontend`, `agent-browser`, Visual Companion

## 감사 결과

### 보존할 것

- `/`의 로그인 게이트 문구와 `/auth/login?returnTo=/` 이동
- Next.js rewrites와 `lib/api.js`를 통한 same-origin API 호출
- 세션, 계좌 연결, CSRF, 단일 실행 mutation 흐름
- `DashboardView`, `MarketOverviewView`, `OrderApprovalPanel`의 기존 데이터와 안전 규칙
- `loading`, `empty`, `stale`, `partial`, `degraded`, `error`, `unauthorized` 상태 어휘
- 주문은 사용자 승인 후에만 전송된다는 안전 경계

### 개선할 것

- 홈의 첫 시선이 여러 패널로 분산되는 문제
- 핵심 자산 상태와 검토 필요 항목의 위계가 약한 문제
- 시장 보조 정보가 포트폴리오와 같은 무게로 보이는 문제
- 인증 후에도 운영 화면으로 전환되는 순간의 구조가 불명확한 문제

### 이번 범위에서 제외할 것

- API 계약, 백엔드 read model, 데이터 공급자 변경
- 라우트 slug와 주요 내비게이션 변경
- 신규 UI 라이브러리, 모션 라이브러리, 아이콘 의존성 추가
- 자동 주문, LLM 직접 주문, 실거래 credential 저장 활성화
- 다른 라우트의 전면 재설계

## 승인된 방향

선택된 방향은 `C. 운영 홈 우선형`이다. 로그인 게이트는 짧고 차분하게 유지하며, 인증 후 홈은 운영 판단에 필요한 정보를 먼저 보여준다.

### 정보 순서

1. 제품 식별, 계좌, 알림, 로그아웃
2. 데이터 최신성 및 지연 상태
3. 평가금액, 총 손익, 리스크 정책 상태, 검토 필요 건수
4. 포트폴리오 추이와 보유 종목
5. 승인 대기 주문과 오늘 확인할 이벤트
6. 환율, 시장 일정, 랭킹 같은 시장 컨텍스트

### 레이아웃

데스크톱은 상단 상태 바와 핵심 지표 행을 두고, 본문을 `주요 포트폴리오 영역`과 `검토 큐 영역`으로 나눈다.

- 주요 영역: 포트폴리오 추이, 보유 종목
- 검토 영역: 승인 대기 주문, 이벤트
- 보조 영역: 환율, 시장 일정, 랭킹, 실시간 시세, 종목 캔들
- 모바일: 두 열을 한 열로 접고, 요약 지표와 검토 큐를 먼저 표시
- 테이블: 가로 스크롤을 허용하되 핵심 열은 유지하고 상세 이동 링크를 제공
- 카드: 모든 콘텐츠를 동일한 카드로 감싸지 않고, 실제 그룹 경계가 필요한 영역에만 패널 사용

## 구성과 데이터 흐름

### 구성 경계

- `web-dashboard/app/route-workspace.js`
  - 홈의 인증 전·연결 전·워크스페이스 준비 상태와 상단 쉘을 유지한다.
  - 기존 계좌 전환, 알림, 리스크 정책, 로그아웃 핸들러를 재사용한다.
- `web-dashboard/app/dashboard-view.js`
  - 홈 레이아웃에서 정보 순서를 재구성한다.
  - 기존 품질 배지와 주문 승인 진입점을 유지한다.
- `web-dashboard/app/market-overview-view.js`
  - 시장 보조 정보를 하단 컨텍스트 영역으로 유지한다.
- `web-dashboard/app/globals.css`
  - 승인된 토큰, 홈 그리드, 반응형 전환, focus와 reduced-motion 규칙을 소유한다.
- `web-dashboard/test/`, `web-dashboard/e2e/`
  - 홈의 렌더링 계약, 접근성, 상태 매트릭스와 시각 기준을 갱신한다.

### 데이터 흐름

`loadSession`이 인증 상태를 결정하고, 인증된 사용자가 계좌를 연결하면 기존 `openWorkspace` 흐름이 대시보드와 보조 데이터를 로드한다. 홈은 기존 `loadPortfolioHistory`를 `HISTORY_QUERY`로 추가 호출해 포트폴리오 추이의 근거를 제공한다. 새로운 API나 백엔드 read model은 만들지 않는다.

화면은 API 결과의 품질 신호를 그대로 소비한다.

- 평가금액: `dashboard.portfolio.data.account.marketValueAmounts`
- 총 손익: `dashboard.portfolio.data.account.profitLossAmounts`
- 리스크 정책 상태: 기존 `riskPolicy`의 `customized`, `version`, 주문 금액·수량 한도
- 포트폴리오 추이: 기존 `loadPortfolioHistory` 결과의 `data.points`
- 검토 필요: `dashboard.pendingOrderProposals.data` 중 `status`가 `PROPOSED` 또는 `MANUAL_REVIEW_REQUIRED`인 항목 수. 만료된 `PROPOSED`도 검토 대상에 포함하고, 승인 버튼은 기존 만료 방어 규칙을 따른다.
- 데이터 최신성: 위 요약 섹션과 보조 위젯의 `stale`, `partial`, `unknown`, `unavailable` 신호를 별도 표시하며 검토 건수에 합산하지 않는다.

보조 데이터는 대시보드 핵심 영역의 렌더를 막지 않는다. 실패한 시장 위젯은 자체 오류와 재시도를 표시하고, 포트폴리오와 주문 검토 영역은 계속 사용할 수 있다.

## 상태와 상호작용

- 로그인 전: 배포판의 `내 투자, 한눈에`와 로그인 행동을 유지한다.
- 연결 전: 계좌 연결 안내와 기존 연결 불러오기를 표시한다.
- 로딩: skeleton과 진행 상태를 표시한다. 실제 숫자로 보이는 임시 데이터를 만들지 않는다.
- 새로고침 중: 마지막 성공 데이터를 유지하면서 해당 영역에 `새로고침 중`을 표시한다. 완료 전 값을 최신으로 단언하지 않는다.
- 정상: 값, 기준 시각, 출처 또는 품질을 함께 표시한다.
- 지연·부분 성공: `지연`, `일부 누락`, `확인 필요`를 색상과 텍스트로 함께 표시한다.
- 오류: 실패한 영역만 오류 처리와 재시도를 제공한다.
- 빈 상태: 계좌 연결, 보유 종목 없음, 검토 주문 없음, 이벤트 없음의 다음 행동을 설명한다.
- 주문: 홈에서는 요약만 보여주고, 승인 시 기존 `OrderApprovalPanel`의 확인 단계를 통과한다.
- 알림: unread 상태를 기존 `NotificationCenter`로 표시하며 실패 시 0으로 오인하지 않는다.
- 계좌: 기존 localStorage 복구와 계좌 전환 동작을 유지한다.

## 접근성·콘텐츠 규칙

- 모든 주요 영역을 landmark와 heading 계층으로 구분한다. 홈 `main`은 `내 자산 홈`, 핵심 지표 영역은 `핵심 계좌 지표`, 검토 영역은 `검토 대기 주문`, 시장 보조 영역은 `시장 정보` accessible name을 가진다.
- 표는 caption 또는 명확한 accessible name, `scope="col"`, 모바일 가로 스크롤 영역을 가진다.
- 추이 시각화는 동일한 데이터를 표 또는 요약 텍스트로도 제공한다.
- 손익과 위험 상태는 색상만으로 표현하지 않고 숫자와 텍스트를 함께 제공한다.
- mutation과 비동기 갱신은 기존 `aria-live` 영역으로 알린다.
- 모든 버튼, 링크, 입력, 계좌 전환, 주문 승인 동작은 키보드로 접근 가능해야 한다.
- focus ring을 유지하고 hover-only 정보를 만들지 않는다.
- `prefers-reduced-motion: reduce`에서는 transform과 전환을 제거한다.
- 제품 카피는 짧고 사실 중심으로 작성한다. 확정 수익, 안전 보장, 매수 유도 표현은 사용하지 않는다.

## 검증 기준

다음 조건을 모두 만족해야 홈 재구성이 완료된 것으로 본다.

- 배포판의 로그인 전 톤과 문구가 유지된다.
- 연결 후 홈이 `최신성 → 핵심 지표 → 포트폴리오 → 검토 대기 → 이벤트` 순서를 따른다.
- 360px, 768px, 1280px, 1440px에서 가로 overflow가 없다.
- 홈 상태 매트릭스의 loading, refreshing, empty, stale, partial, degraded, error, unauthorized가 모두 표현된다.
- 주문 승인과 취소가 중복 실행되지 않고 기존 확인 패널을 거친다.
- 브라우저 접근성 검사에서 새 위반이 없다.
- `cd web-dashboard && npm run lint:css`
- `cd web-dashboard && npm test`
- `cd web-dashboard && npm run build`
- `cd web-dashboard && npm run e2e`
- `git diff --check`

## 참고 자료

- 배포 기준: https://web-dashboard-phi-lac.vercel.app/
- 제품/UX 기준: `DESIGN.md`
- 홈 상태 소유자: `web-dashboard/app/route-workspace.js`
- 홈 콘텐츠 소유자: `web-dashboard/app/dashboard-view.js`
