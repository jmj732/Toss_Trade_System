# 예측 batch ingestion delta

## 범위

- 외부 모델이 한 broker connection에 prediction을 묶어 제출하는 batch POST API를
  추가한다.
- 모델 실행, 자동 prediction 생성, 주문 연동은 추가하지 않는다.
- 기존 단건 POST, GET, outcome 채점, 집계 의미와 계산식은 변경하지 않는다.

## API

- `POST /api/v1/broker-connections/{connectionId}/analysis-predictions/batch`
- body는 `items` 배열이며 1~100개를 허용한다.
- 각 항목은 `clientRequestId`, `symbol`, `currency`, `predictedDirection`,
  `modelVersion`, `contractVersion`을 받는다.
- 응답은 입력 순서를 유지하며 각 항목에 다음 중 하나를 반환한다.
  - `CREATED`: 새 prediction과 서버 quote 기준가를 반환한다.
  - `DUPLICATE`: 같은 사용자의 같은 `clientRequestId`와 같은 입력으로 이미 생성된
    prediction을 반환하며 quote를 다시 호출하지 않는다.
  - `FAILED`: 입력 오류 코드를 반환하고 다른 항목 처리는 계속한다.
- 같은 `clientRequestId`를 다른 connection 또는 다른 prediction 입력에 재사용하면
  `CLIENT_REQUEST_CONFLICT`로 실패한다.
- 등록된 `ACTIVE` model/contract 조합만 신규 생성할 수 있다. 사전 검사와 저장
  transaction의 `FOR SHARE` 재검사는 기존 단건 생성과 같다.
- quote 통화 불일치, 유효하지 않은 quote, broker quote 실패는 해당 항목만
  실패시키며 prediction을 저장하지 않는다.

## 스키마과 동시성

- Flyway V21에서 `analysis_predictions.client_request_id VARCHAR(100)` nullable 열을
  추가한다. 기존 단건 prediction은 `NULL`을 유지한다.
- `(user_id, client_request_id)` UNIQUE 제약으로 사용자별 idempotency를 보장한다.
- 신규 batch 항목은 quote 전에 기존 행을 조회한다. 저장 경쟁에서는 unique 제약이
  승자를 정하고 패자는 저장된 canonical prediction을 조회해 같은 입력이면
  `DUPLICATE`, 다르면 `CLIENT_REQUEST_CONFLICT`를 반환한다.
- 별도 receipt, payload JSON, TTL, background cleanup은 추가하지 않는다. 저장된
  prediction 자체가 immutable idempotency 결과다.

## TDD와 검증

- batch 성공과 서버 quote 기준가 저장
- 같은 batch 및 재요청 duplicate, duplicate quote 미호출
- 사용자별 같은 `clientRequestId` 허용과 동일 사용자 payload 충돌
- 미등록/deprecated version, invalid input, quote 실패의 항목별 실패와 부분 성공
- 기존 단건 POST 회귀
- V21 unique 제약과 Flyway 최신 버전 assertion
- 구현 전체 code review 1회
- backend `./mvnw clean verify`, dashboard `npm test`,
  prediction 변경 파일의 `com.jmj.trade.order` import 0건
