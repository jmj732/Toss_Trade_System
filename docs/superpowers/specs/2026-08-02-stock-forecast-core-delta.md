# Stock forecast core delta

## 범위

- 기존 v3 종목 분석 결과만 입력으로 받아 결정적 forecast를 계산한다.
- D1 상승확률, D5·D20 기대수익률, D20 기준 예상 최대손실을 반환한다.
- Forecast 값은 입력 metric의 provenance를 그대로 보존하고, input snapshot ID와
  분석 기준 시각을 함께 기록한다.
- 확률 범위, 입력 provenance의 시계열 정합성, 평가 시각 대비 24시간 freshness를
  검증한다. 검증 실패나 의존 metric 부재는 해당 forecast metric만 `null`과
  `missingData`로 남긴다. provider 값을 평균하거나 보정하지 않는다.
- backend는 결과를 immutable `stock_forecasts`에 저장하고, 유효한 `quote.price`가
  있을 때만 기존 `analysis_predictions`에 방향 prediction을 연결한다. 기존
  D1→D5→D20 outcome grading과 주문 영역은 변경하지 않는다.
- Gemini explain, Forecast 학습/외부 모델, 자동 주문은 범위 밖이다.

## 계약 및 계산

- analysis-service `/internal/v4/stock-forecasts`가 `StockAnalysisCoreResponse`,
  `evaluatedAt`, `modelVersion`, `contractVersion`을 입력으로 받는다.
- 각 forecast metric은 `value`, `asOf`, `provenance`, `missingData`를 가진다. 모든
  의존 provenance의 `asOf`가 동일하지 않거나, 평가 시각보다 미래/24시간 초과면
  그 metric은 degraded다.
- deterministic model은 `d1_up_probability`, `d5_expected_return`,
  `d20_expected_return`, `expected_max_loss` 네 metric으로 고정한다. 확률 계산값이
  `[0,1]` 밖이면 clamp하지 않고 missing 처리한다.
- 계산식은 다음 고정 계수로 구현한다: `D1 = 0.5 + priceVsSma20/4 +
  (rsi14-50)/200 + sp500Return20d/4 - volatility20/2`, `D5 = priceVsSma20/5 +
  priceVsSma50/5 + sp500Return20d/2 - volatility20/5`, `D20 = profitMargin/5 +
  roe/10 + fcfYield + smaTrend/2 + sp500Return20d - volatility20`,
  `expectedMaxLoss = -volatility20 * 0.2817`. 입력이 없으면 해당 식을 실행하지 않는다.
- top-level confidence는 완전한 forecast metric들의 의존 analyzer confidence 중
  최솟값이며, 하나라도 missing이면 `0`이다. 이는 confidence를 평균·추정하지 않는
  보수적 기준이다.
- v4 response `asOf`는 입력 분석의 기준 시각이고 `evaluatedAt`은 forecast 평가 시각이다.

## 저장/API

- Flyway V34에 snapshot/run 복합 FK를 가진 append-only `stock_forecasts`를 추가한다.
- `POST /api/v1/stock-forecasts/{symbol}`은 active prediction model/contract version과
  선택적 연결 ID를 받아 forecast를 생성한다. 동일 snapshot·version 조합은 기존 결과를
  재사용한다. credentials가 꺼져도 forecast 생성·조회는 가능하고, 연결 ID와 유효한
  baseline이 함께 있을 때만 ledger prediction을 추가한다.
- `GET /api/v1/stock-forecasts/{symbol}`은 사용자의 최신 저장 결과를 반환한다.
- 입력에 양수 `quote.price`가 있으면 forecast의 D1 방향(확률 >= 0.5는 UP)을 기존
  prediction ledger에 기록하여 scheduler/outcome grading 대상으로 만든다. quote가
  없으면 prediction ID 없이 forecast만 저장한다.

## 검증

- Python v4 contract/math tests: deterministic repeatability, range rejection,
  inconsistent/stale provenance, dependent missing data, no explain/order fields.
- Backend contract/workflow tests: v4 validation, snapshot persistence, ledger link,
  duplicate reuse, missing baseline behavior, API ownership.
- 기존 prediction/outcome, stock-analysis, Flyway schema tests는 유지한다.
