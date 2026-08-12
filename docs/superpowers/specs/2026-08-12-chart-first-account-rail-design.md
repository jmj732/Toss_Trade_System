# 차트 우선 홈과 우측 계좌 rail 설계

## 목표

홈에서 실제 Toss 캔들 데이터를 가장 먼저 읽고, Toss증권 홈처럼 데스크톱 우측에 계좌 요약을 고정한다. 새 금융 데이터나 추정값은 만들지 않는다.

## 확정 방향

- 데스크톱: 본문과 계좌 rail의 2열 레이아웃. 계좌 rail은 `position: sticky`로 화면 우측에 유지한다.
- 모바일: 1열로 전환하고 DOM에서도 계좌 rail을 본문 위에 둔다. 데스크톱 CSS에서만 main을 1열, rail을 2열로 배치한다.
- 차트: 기존 `loadCandles`의 Toss 응답만 사용한다. 홈 기본 구간은 `1m`, 종목 화면은 기존 `1m`/`1d` 선택을 유지한다.
- 시각 표현: 실제 OHLCV로 SVG 캔들·거래량을 그린다. 상승은 빨강, 하락은 파랑이며 색상 외에 값·범례·aria label을 함께 제공한다.
- 데이터 시점/출처: 기존 provenance와 `asOf`를 차트 아래에 계속 노출한다. 데이터가 없거나 DEGRADED/ERROR이면 기존 메시지와 상태를 유지한다.
- 계좌: 기존 `DashboardView`의 `Portfolio` 렌더를 재사용한다. 현금·평가금액·P/L·주문 가능 금액·보유 종목을 rail에 둔다.

## 범위

### 포함

1. 홈 차트 영역을 시장 overview와 dashboard main의 핵심 콘텐츠로 추가한다.
2. 계좌 Portfolio를 홈 전체 높이를 아우르는 sticky rail로 이동한다.
3. 차트 구간 선택을 홈에도 제공하고 선택한 구간으로 Toss candles를 다시 조회한다.
4. 캔들 표의 접근 가능한 수치 정보는 유지하고, 차트와 함께 제공한다.
5. 빨강/파랑이 색맹 사용자에게 유일한 신호가 되지 않도록 상승/하락 텍스트와 숫자를 표시한다.

### 제외

- WebSocket/SSE 신규 backend 계약
- provider 응답에 없는 틱·호가·가격의 보간/합성
- 새 차트 라이브러리 및 새 UI route
- 계좌 데이터의 별도 API/캐시/저장소

## 구성과 데이터 흐름

`RouteWorkspace(home)`가 `loadDashboard`를 완료한 뒤 첫 보유 종목 symbol을 선택해 `loadCandles(connectionId, symbol, "1m")`를 호출한다. 결과는 `MarketCandleChart`에 전달한다. 사용자가 구간을 바꾸면 같은 API를 해당 interval로 한 번 다시 호출한다.

홈은 `home-workspace` 2열로 구성한다. DOM은 보조기술 순서를 맞추기 위해 rail을 먼저 둔다.

```text
home-workspace
└── home-account-rail
    └── existing Portfolio
└── home-workspace-main
    ├── MarketCandleChart
    ├── MarketOverviewView
    └── DashboardView(main only)
```

홈 차트 대상은 provider가 반환한 보유 종목 배열의 첫 번째 유효 symbol로 고정한다. 배열이 비었거나 symbol이 없으면 차트를 표시하지 않는다. 첫 symbol의 provider 조회가 실패해도 다른 symbol을 몰래 선택하지 않으며, 사용자가 종목 화면에서 명시적으로 종목을 선택할 수 있다.

차트는 provider candle의 open/high/low/close/volume을 시간순으로 표시한다. Toss 응답이 최신순이면 화면 좌→우만 시간순으로 뒤집고, 값 자체는 변형하지 않는다. SVG 봉은 OHLC 네 값이 모두 숫자인 candle만 그린다. 일부 OHLC가 null이면 해당 candle은 표에 `확인 필요`로 남기고 SVG에서 제외한다. volume이 null이면 가격 봉은 그리되 해당 volume bar는 생략하고 `거래량 미제공`을 표시한다. 유효한 OHLC 봉이 하나도 없으면 SVG 대신 기존 빈 상태와 수치 표만 표시한다.

## 상태와 오류

- `LOADING`: 기존 로딩 문구를 차트 영역에 표시한다.
- `READY`: UI 내부 표시 상태다. raw provider envelope의 `status`를 임의로 `READY`로 요구하지 않고, `data.candles` 배열이 있으면 payload를 정상 렌더한다. raw `DEGRADED`/`UNAVAILABLE`/`ERROR`는 그대로 보존해 배지·사유에 반영한다. 정상 payload에도 candles가 비어 있으면 빈 상태로 표시한다.
- `DEGRADED`: 제공된 캔들만 표시하고 누락 필드/상태를 숨기지 않는다. OHLC 누락 봉은 표에 `확인 필요`, volume 누락은 `거래량 미제공`으로 명시한다.
- `ERROR`/`UNAVAILABLE`: 빈 차트나 임의 선을 그리지 않고 기존 오류 사유를 표시한다.
- symbol 없음: 차트 대신 “보유 종목이 없어 차트를 표시할 수 없습니다”를 표시한다.
- 환율 변환, 평균화, 추정 수익률은 하지 않는다.

## 접근성·반응형

- 차트 SVG에 `role="img"`, 고유 `title`/`desc`, `aria-labelledby`를 넣고 실제 symbol/interval 설명을 제공한다. 구간 버튼은 `aria-pressed`로 선택 상태를 제공한다. 수치 표는 keyboard-scrollable region으로 유지한다.
- 상승/하락은 색상과 함께 `상승`/`하락`, 부호, 범례로 표시한다.
- `prefers-reduced-motion`에서는 전환 효과를 제거한다.
- 760px 이하에서는 rail이 먼저 나오고 sticky를 해제한다. 표는 기존 가로 스크롤을 사용한다.

## 검증

- `MarketCandleChart`의 시간순 정렬, 상승/하락 색상, 거래량, empty/missing 필드 렌더 테스트
- `RouteWorkspace` home fixture에서 `1m`/`1d` 요청과 interval 변경을 확인하고, `data.candles`, raw `status`, `provenance`, `asOf`, DEGRADED `unknownFields` shape를 고정한다. UI의 `READY`는 이 envelope에서 파생된 내부 표시 상태임을 테스트한다.
- `DashboardView`의 기존 portfolio/주문 테스트 회귀 확인
- 전체 dashboard unit test, build, 접근성 home/stock E2E 및 대표 desktop/mobile 스크린샷 확인
