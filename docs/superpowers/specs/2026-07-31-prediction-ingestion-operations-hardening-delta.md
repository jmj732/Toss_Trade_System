# Prediction ingestion operations hardening delta

## 목표

- 기존 비정규 symbol prediction도 canonical 요청의 동일 `clientRequestId` replay로 인정한다.
- 만료된 ingestion API key를 DB 시각 기준으로 `EXPIRED` 상태로 정리한다.
- 사용자별 API key 관리와 prediction 평가 상태를 dashboard에서 운영할 수 있게 한다.
- host/JVM과 PostgreSQL 시계 차이 때문에 API key lifecycle 테스트가 불안정하지 않게 한다.

## 결정

- replay 비교에서만 기존 저장 symbol을 `trim` 후 대문자로 canonicalize한다. 기존 row,
  GET 응답, migration/backfill은 변경하지 않는다.
- 만료 key는 삭제하지 않는다. rejection audit의 append-only/FK와 key lifecycle 이력을
  보존하기 위해 `ACTIVE → EXPIRED` 단방향 상태 전이를 추가한다.
- 만료 판정, cleanup, `createdAt`, `lastUsedAt`, revoke 시각은 PostgreSQL
  `CURRENT_TIMESTAMP`를 기준으로 통일한다. cleanup은 기본 활성화된 주기 작업이며
  `prediction.ingestion-api-key.cleanup.*` 설정으로 끌 수 있다.
- 인증 조회는 `ACTIVE`와 `EXPIRED` key를 찾되 DB가 계산한 만료 여부를 filter에 전달한다.
  따라서 cleanup 전후 모두 기존 EXPIRED audit/metric을 유지하고 quote, 저장,
  `lastUsedAt` 부작용은 없다.
- dashboard는 기존 session/CSRF API만 사용해 key 발급·목록·rotation·폐기를 제공한다.
  원문 key는 발급/rotation 응답 직후 메모리에만 표시하며 목록 API에는 추가하지 않는다.
- 운영 read API는 로그인 사용자 자신의 earliest due/ungraded backlog와 최고 lag만 반환한다.
  전역 Prometheus/Actuator 공개 범위는 바꾸지 않고 수동 평가 실행도 추가하지 않는다.

## 보존 계약

- 단건/batch ingestion, API key scope/rate-limit/fail-closed, idempotency 결과와 응답 순서를
  유지한다.
- prediction 생성·평가·집계와 주문 모듈 동작을 변경하지 않는다.
- 자동 예측, 실제 주문, 다른 API의 API key 인증, 원문 key/hash/payload 로깅을 추가하지
  않는다.

## 검증

- legacy lowercase/공백 symbol row가 canonical replay에서 `DUPLICATE`이고 GET 저장값은 유지
- DB 시각 기준 만료 cleanup, cleanup 전후 인증 거부·audit, lifecycle 불변성
- clock drift 상황에서도 issue/mark-used/rotation/revoke constraint 안정성
- API key UI의 1회 원문 표시, list/issue/rotate/revoke CSRF, 운영 backlog/lag 표시
- backend `./mvnw clean verify`, dashboard `npm test`/build, 전체 Release Gates
