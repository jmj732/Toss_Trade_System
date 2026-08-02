# inbox 멱등 소비 delta

## 범위

- `inbox_messages` 테이블과 소비자 추상화를 추가한다. 소비 완료를 소비자별로 원장에
  기록해 재수신과 worker 재실행에서 부작용이 중복되지 않게 한다(SPEC:80, SPEC:464,
  SPEC:1126, MVP 완료 기준 6번).
- `OrderOutboxProcessor`의 핸들러 실행과 `NotificationOutboxProcessor`를 이 위로 이관한다.
- outbox 기록 지점과 `order_intent_outbox_events`, `order_submission_outbox_events`,
  `notification_outbox_events`의 컬럼 의미는 바꾸지 않는다.
- 메시지 브로커를 도입하지 않는다. 전달은 기존 in-process relay 그대로다.

## 왜 지금인가

현재 멱등은 하류 테이블 각자의 `ON CONFLICT`에 얹혀 있고, 핸들러가 relay의 claim
트랜잭션 안에서 실행된다. 소비자가 하나일 때만 성립한다. 같은 이벤트 타입에 소비자가
둘이 되면 한 소비자가 던질 때 claim 트랜잭션 전체가 롤백되어, 이미 성공한 다른 소비자의
부작용이 재실행된다. A1이 핸들러 SPI를 열었으므로 두 번째 구독자는 시간 문제다.

## 테이블

- `inbox_messages(id, consumer_name, event_id, event_type, received_at, processed_at)`
- `UNIQUE (consumer_name, event_id)`
- 미처리 조회용 부분 인덱스는 `WHERE processed_at IS NULL`
- `event_id`는 원본 outbox 행의 `id`다. outbox 테이블별로 FK를 걸지 않는다. 여러 outbox
  원장을 한 테이블로 받으므로 `event_type`으로 구분한다.

## 처리 불변식

- 소비자 실행은 **소비자마다 독립 트랜잭션**이다. 한 소비자의 실패가 다른 소비자의
  완료를 되돌리지 않는다.
- 한 트랜잭션 안에서 inbox 행의 `processed_at` 기록과 소비자의 도메인 변경을 함께
  커밋한다(SPEC:529). 둘 중 하나만 남는 상태가 없다.
- 같은 `(consumer_name, event_id)`가 이미 `processed_at`을 가지면 소비자를 실행하지 않고
  넘어간다. 재수신은 no-op이다.
- 서로 다른 `consumer_name`은 같은 `event_id`를 각각 1회씩 처리한다.
- outbox 행의 `published_at`은 그 이벤트 타입에 등록된 **모든** 소비자가 처리를 마친
  뒤에만 기록한다. 일부만 끝난 행은 미발행으로 남아 다음 틱에 남은 소비자만 실행한다.
- 소비자가 없는 이벤트 타입은 즉시 발행 처리되고 backlog에서 빠진다(A1 동작 유지).
- 재시도 상한과 dead-letter는 A1의 outbox 계약을 그대로 쓴다. inbox는 상한을 따로 두지
  않는다.
- 소비자 이름은 코드 상수다. 이름이 바뀌면 과거 처리 기록과 매칭되지 않으므로 바꾸지
  않는다.

## TDD와 검증

- 같은 outbox 이벤트를 소비자 2개가 각각 1회씩 처리
- 동일 소비자 재수신 시 소비자가 실행되지 않고 하류 부작용이 늘지 않음
- 소비자 A 성공, 소비자 B 예외일 때 A의 부작용이 롤백되지 않고 B만 재시도됨
- 소비자 일부만 끝난 outbox 행은 `published_at`이 남지 않고, 다음 틱에 남은 소비자만 실행
- `processed_at` 기록 직전에 죽은 경우 재실행되며 하류 결과가 1건 유지
- 소비자 없는 이벤트 타입은 발행 처리되고 backlog 0
- `NotificationOutboxProcessor` 이관 후 기존 알림 동작과 멱등성이 유지됨
- 구현 전체 code review 1회
- backend `./mvnw clean verify`
