# Stock analysis Gemini explain delta

## Scope

- 기존 성공한 stock analysis와 stock forecast의 immutable input snapshot을 근거로
  `evidence`, `counterArguments`, `missingData`, `invalidationConditions` 설명을 만든다.
- Gemini는 서술 claim만 만들며, 서버가 저장·반환하는 analysis/forecast 수치와 확률은
  원본 forecast response를 그대로 사용한다. 주문, 매매 제안, 자동 실행은 연결하지 않는다.
- snapshot observation마다 결정적 citation ID
  `snapshot:<snapshotId>:observation:<index>`를 만들고, 모든 claim은 하나 이상의
  존재하는 citation ID를 가져야 한다. citation이 없거나 snapshot에 없는 ID를 쓰거나,
  숫자·확률·수익·가격을 포함한 claim은 제거한다.

## Provider contract

- Backend가 Gemini `generateContent` REST API를 호출한다. API key는 `x-goog-api-key`
  header로만 전달하고 URL·로그·저장 응답에 남기지 않는다.
- structured JSON response는 네 claim 배열을 가진다. model ID와 prompt version은
  설정값으로 고정하고 요청·저장 결과에 기록한다. 서버는 응답에서 첫 candidate의 JSON만
  읽고, schema/claim/citation 정책을 통과한 결과만 저장한다.
- API key 부재, timeout, HTTP/JSON 오류, unsupported/uncited response는 explain만
  `DEGRADED`로 저장한다. 해당 상태와 missingData를 반환하지만 analysis/forecast 결과는
  성공 상태를 유지한다.

## Persistence/API

- Flyway V35 `stock_analysis_explanations`는 user, analysis run, forecast, input snapshot,
  symbol, model ID, prompt version, `asOf`, status, missingData, citations, sanitized
  response, createdAt을 append-only로 저장하고
  `(user_id,stock_forecast_id,model_id,prompt_version)`를 unique하게 보장한다.
- `POST /api/v1/stock-analysis-explanations/{symbol}`은 최신 forecast에 연결해 설명을
  생성하거나 같은 forecast/version 결과를 재사용한다.
- `GET /api/v1/stock-analysis-explanations/{symbol}`은 저장된 설명과 원본 forecast를
  재호출 없이 반환한다. stored response가 없으면 NOT_FOUND다.

## TDD/verification

- citation ID determinism, valid claim retention, uncited/unknown/numeric claim removal,
  provider missing/timeout/error degradation, cache replay, user scoping, V35 schema,
  and no numeric forecast mutation are covered by Python-free Java contract/unit and
  WireMock/Testcontainers integration tests.
- Full verify remains backend Docker `clean verify`, analysis-service pytest/Ruff,
  dashboard tests, and compose smoke.
