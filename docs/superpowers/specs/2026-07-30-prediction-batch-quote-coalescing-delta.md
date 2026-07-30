# 예측 batch quote coalescing delta

## 범위

- 한 batch에서 실제 신규 생성 대상인 같은 symbol의 prediction은 broker quote를 한 번만
  조회하고 같은 기준가와 관찰 시각을 사용한다.
- 캐시는 `createBatch` 호출 내부에서만 유지한다. 요청 간 캐시, TTL, stale quote 대체는
  추가하지 않는다.
- 기존 단건 POST, GET, outcome 채점, 집계, API key 인증·scope, 주문 모듈 경계는
  변경하지 않는다.

## 처리 순서와 오류

- 입력 순서대로 기존 항목별 처리를 유지한다.
- null/invalid input, API key scope 불일치, 비활성 model/contract,
  `DUPLICATE`, `CLIENT_REQUEST_CONFLICT`는 quote cache에 접근하기 전에 판정한다.
- 첫 신규 항목이 symbol별 quote 결과를 batch-local map에 저장한다.
- 성공 quote는 같은 symbol의 후속 신규 항목이 기준가와 `observedAt`을 재사용한다.
- broker 실패도 symbol별로 저장한다. 같은 symbol의 신규 항목만 `QUOTE_FAILED`가 되고
  다른 symbol 처리는 계속한다.
- quote 통화 불일치와 유효하지 않은 가격은 기존 항목별 오류 코드를 유지한다.
- symbol key는 기존 입력 의미를 보존하기 위해 별도 대소문자 정규화를 하지 않는다.

## 동시성과 불변식

- 기존 `(user_id, client_request_id)` UNIQUE 제약과 저장 경쟁 후 canonical prediction
  재조회 로직을 유지한다.
- ACTIVE version은 quote 전 사전 검사와 저장 transaction 안의 `FOR SHARE` 재검사를
  유지한다.
- coalescing은 quote 호출 수만 줄이며 응답 순서, idempotency, prediction 스키마와
  저장 의미를 변경하지 않는다.

## TDD와 검증

- 같은 symbol 신규 항목이 quote 1회와 동일 기준가·관찰 시각을 사용
- duplicate/conflict/invalid/scope/version 실패 항목이 quote 호출에서 제외
- 한 symbol quote 실패가 같은 symbol에만 공유되고 다른 symbol은 생성
- 서로 다른 batch 요청은 quote를 다시 호출
- 기존 batch/단건 회귀
- 구현 전체 code review 1회
- backend `./mvnw clean verify`, dashboard `npm test`,
  prediction 변경 파일의 `com.jmj.trade.order` import 0건
