# 주문 outbox relay delta

## 범위

- `order_intent_outbox_events`, `order_submission_outbox_events` 를 소비하는 공용 relay를
  추가한다. 두 테이블 모두 현재 write-only이고 소비자가 없다.
- relay는 `NotificationOutboxProcessor`의 claim 방식을 재사용한다. 별 트랜잭션에서
  `FOR UPDATE SKIP LOCKED`로 claim하고, 처리 후 `published_at`을 기록한다.
- 핸들러 SPI를 두고 이벤트 타입별로 구독한다. 첫 실제 소비자는 주문 전이 알림이며
  기존 `notification_outbox_events` 경로를 재사용한다.
- 재시도 상한과 dead-letter를 위해 `failed_at`, `last_error` 컬럼을 추가한다.
- inbox 도입은 이 delta 범위가 아니다. 소비자가 하나뿐인 동안은 하류의
  `outbox_event_id UNIQUE`가 중복을 막는다.
- 기존 outbox 기록 지점(`OrderIntentTransitionService`, `OrderSubmissionService`)의
  write 계약과 컬럼 의미는 바꾸지 않는다.

## 릴레이 계약

- claim 단위는 `published_at IS NULL AND failed_at IS NULL`인 행이며 `created_at` 오름차순이다.
- claim 트랜잭션과 핸들러 실행은 같은 트랜잭션에서 커밋한다. 핸들러가 던지면 롤백하고
  `attempts`만 증가시킨다.
- `attempts`가 상한에 도달하면 `failed_at`과 `last_error`를 기록하고 claim 대상에서 뺀다.
  dead-letter 행은 자동으로 재시도하지 않는다.
- 스케줄러 간격, 1회 배치 크기, 재시도 상한은 property로 노출하고 기본값을 갖는다.
- relay는 opt-in 조건부 설정으로 등록한다. `PredictionEvaluationSchedulingConfiguration`과
  같은 `@ConditionalOnProperty` 패턴을 쓰고 `matchIfMissing`을 쓰지 않는다.
- `last_error`에 자격증명, 원문 payload, 계좌번호를 넣지 않는다(SPEC:1151).

## 처리 불변식

- 같은 행을 재처리해도 하류 부작용이 늘지 않는다. 멱등성은 하류의
  `outbox_event_id UNIQUE`와 `ON CONFLICT DO NOTHING`이 보장한다.
- claim과 처리가 겹쳐도 두 인스턴스가 같은 행을 잡지 않는다. 스케줄러 틱이 겹치거나
  인스턴스가 여러 개여도 backlog를 나눠 가진다.
- 처리 도중 죽어서 `published_at`이 안 써진 행은 다음 틱에 다시 처리되며 부작용은 없다.
- `trade.outbox.backlog` gauge는 처리 후 0으로 수렴한다. dead-letter 행은 backlog에서
  빠지고 별도로 노출한다.
- 주문 상태 전이의 도메인 커밋과 outbox insert는 이미 같은 트랜잭션이며 이를 유지한다.

## TDD와 검증

- 두 테이블 각각에서 미발행 행이 처리되고 `published_at`이 기록됨
- 이미 처리된 행을 다시 claim해도 하류 레코드가 늘지 않음
- 핸들러 예외 시 `published_at`이 남지 않고 `attempts`만 증가
- `attempts` 상한 도달 시 `failed_at`·`last_error` 기록 후 재claim되지 않음
- 구독 핸들러가 없는 이벤트 타입은 발행 처리되고 backlog에서 빠짐
- backlog gauge가 relay 실행 후 0으로 수렴
- `last_error`에 민감정보가 들어가지 않음
- property 미설정 시 relay 빈이 등록되지 않고, `enabled=true`일 때만 등록됨
- 구현 전체 code review 1회
- backend `./mvnw clean verify`
