# Event Review Workflow — Delta Spec

## Read model

- 기존 broker connection 소유 이벤트 경로를 유지한다.
- 목록은 이벤트 정보, review 상태/version, 분석 비교 존재 여부를 반환한다.
- 상세는 목록 정보와 저장된 포트폴리오 분석 전후 비교를 반환한다.
- 분석 비교가 없으면 상세의 `analysisComparison`은 null이다.

## Review state

- 초기 상태는 저장 row 없는 `PENDING`, version `0`이다.
- 사용자 명령 상태는 `CONFIRMED`, `HELD`, `IGNORED`만 허용한다.
- 상태 row는 이벤트와 동일한 `(userId, brokerConnectionId)` 소유권을 DB FK로 묶는다.
- review 명령마다 `expectedVersion`을 요구한다.
- 현재 version과 다르면 HTTP 409이며 상태를 변경하지 않는다.
- 성공 명령은 version을 1 증가시킨다.

## Idempotency and concurrency

- `Idempotency-Key`는 사용자 범위에서 고유하다.
- 동일 key, event, 상태, expectedVersion 재호출은 기존 결과를 반환한다.
- 동일 key를 다른 요청에 재사용하면 HTTP 409다.
- 이벤트 row `FOR UPDATE`와 단일 DB transaction으로 명령 기록·상태 변경을 직렬화한다.
- command ledger는 append-only이며 결과 status/version을 기록한다.

## API

- `GET /api/v1/broker-connections/{connectionId}/events`
- `GET /api/v1/broker-connections/{connectionId}/events/{eventId}`
- `POST /api/v1/broker-connections/{connectionId}/events/{eventId}/review`
- review body는 `status`, `expectedVersion`; key는 `Idempotency-Key` header다.

## Exclusions

- review 상태는 분석·주문 제안·주문 실행을 유발하지 않는다.
- 자동 주문, 주문 제안, 알림, 외부 채널 연동은 추가하지 않는다.
