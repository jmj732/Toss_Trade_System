# 주문 제출·UNKNOWN 조정 V3 설계

## 1. 목표와 범위

토스 API를 직접 호출하지 않고 다음 주문 원장 기능을 구현한다.

- `SubmissionAttempt`, `BrokerOrder`, `ReconciliationCheck` 도메인과 JPA 매핑
- 동일 주문 제출의 멱등성 및 제한적 재시도
- UNKNOWN 주문의 조회 결과 기반 조정
- 여러 제출 시도가 하나의 증권사 주문을 확인하는 N:1 관계
- 도메인 변경, 감사 로그, Outbox의 단일 PostgreSQL 트랜잭션
- Flyway V3 제약과 Testcontainers 통합 테스트

조회 결과는 애플리케이션 서비스 입력값으로 받는다. 토스 DTO, HTTP client, 인증, 실제 주문·조회 호출은 이번 범위에 포함하지 않는다.

## 2. 구현 방식

기존 V1 주문 원장과 V2 `OrderIntent` 감사/Outbox를 수정하지 않고 V3 증분 마이그레이션을 추가한다.

- V1 `broker_orders`를 필요한 컬럼과 제약으로 확장한다.
- 계좌 범위 client order ID 소유권을 직렬화하는 `submission_idempotency_keys`를 추가한다.
- `submission_attempts`, `reconciliation_checks`를 추가한다.
- 제출 도메인 전용 `order_submission_audit_logs`, `order_submission_outbox_events`를 추가한다.
- 도메인 모델이 일차 불변성을 강제하고 PostgreSQL 제약과 트리거가 최종 방어한다.

기존 `BrokerOrder` 데이터가 있을 수 있으므로 V3에서 새 컬럼은 nullable 또는 안전한 기본값으로 추가한다. 애플리케이션이 새로 생성하는 행에는 완전한 필드를 요구한다.

## 3. 도메인 모델

### 3.1 SubmissionAttempt

필드:

- `id`
- `orderIntentId`
- `brokerAccountId`
- `attemptNumber`
- `internalIdempotencyKey`
- `clientOrderId`
- `requestBodyHash`
- `retryOfAttemptId`
- `confirmedBrokerOrderId`
- `status`
- `dispatchEvidence`
- `createdAt`
- `idempotencyExpiresAt`
- `startedAt`
- `finishedAt`
- `lastReconciliationCheckNumber`
- `version`

상태:

- `CREATED`
- `DISPATCHING`
- `ACKNOWLEDGED`
- `BROKER_REJECTED`
- `UNKNOWN`
- `RECONCILING`
- `RECONCILED_NO_MATCH`
- `RECONCILIATION_FAILED`

전이:

```text
CREATED -> DISPATCHING
DISPATCHING -> ACKNOWLEDGED | BROKER_REJECTED | UNKNOWN
UNKNOWN -> RECONCILING
RECONCILING -> ACKNOWLEDGED | RECONCILED_NO_MATCH | RECONCILIATION_FAILED
```

명령:

- `startDispatch(startedAt)`
- `markUnknown(finishedAt)`
- `startReconciliation()`
- `acknowledge(brokerOrderId, finishedAt)`
- `reject(finishedAt)`
- `markNoMatch(finishedAt)`
- `markReconciliationFailed(finishedAt)`

불변성:

- `attemptNumber >= 1`
- `internalIdempotencyKey`는 전체 unique
- `(orderIntentId, attemptNumber)`는 unique
- client order ID는 영숫자, `-`, `_`만 허용하고 최대 36자
- `(brokerAccountId, clientOrderId)`는 하나의 canonical `submission_idempotency_keys` 행만 가진다.
- 같은 계좌·client order ID를 공유하는 모든 attempt는 canonical 행과 같은 `orderIntentId`, `requestBodyHash`, 멱등 만료시각을 사용한다.
- 다른 OrderIntent는 같은 계좌의 client order ID를 재사용할 수 없다.
- `createdAt`은 생성 시 필수이며 이후 변경할 수 없다.
- retry는 원 attempt와 동일 intent, client order ID, body hash, 멱등 만료시각을 사용한다.
- retry는 새 internal idempotency key와 다음 attempt number를 사용한다.
- 생성 후 intent, attempt number, internal key, client order ID, body hash, retry 관계, 멱등 만료시각은 변경할 수 없다.
- `confirmedBrokerOrderId`는 ACKNOWLEDGED일 때만 존재한다.
- `lastReconciliationCheckNumber`는 조정 검사 추가 시 1씩 증가하며 `@Version` 충돌로 동시 번호 할당을 차단한다.

### 3.2 SubmissionIdempotencyKey

계좌 범위 client order ID 소유권을 나타내는 canonical DB 행이다.

- `brokerAccountId`
- `clientOrderId`
- `orderIntentId`
- `requestBodyHash`
- `idempotencyExpiresAt`
- `createdAt`

제약:

- `PRIMARY KEY(brokerAccountId, clientOrderId)`
- `UNIQUE(brokerAccountId, clientOrderId, orderIntentId, requestBodyHash, idempotencyExpiresAt)`를 추가해 PostgreSQL composite FK 대상 키를 제공한다.
- `(orderIntentId, brokerAccountId)`는 `order_intents(id, broker_account_id)`를 참조해 canonical key의 계좌가 실제 intent 계좌와 같음을 강제한다.
- attempt는 `(brokerAccountId, clientOrderId, orderIntentId, requestBodyHash, idempotencyExpiresAt)` composite FK로 canonical 행을 참조한다.
- attempt의 `(orderIntentId, brokerAccountId)`도 `order_intents(id, broker_account_id)`를 참조한다.
- 최초 attempt 생성 트랜잭션에서 canonical 행을 먼저 확보한다.
- 동일 계좌·client order ID가 이미 다른 intent 또는 body hash에 속하면 생성과 재시도를 거부한다.
- 다른 계좌는 같은 client order ID를 사용할 수 있다.

### 3.3 BrokerOrder

기존 테이블을 다음 projection으로 확장한다.

- 기존 `id`, `orderIntentId`, `brokerAccountId`, `brokerOrderId`, `status`
- `clientOrderId`
- `replacesBrokerOrderId`
- `version`

제약:

- `UNIQUE(brokerAccountId, brokerOrderId)` 유지
- `(id, orderIntentId)` composite unique 추가
- `(orderIntentId, brokerAccountId)`는 `order_intents(id, broker_account_id)` composite FK로 실제 intent 계좌와 일치해야 한다.
- `replacesBrokerOrderId`는 같은 intent의 다른 BrokerOrder만 참조한다.
- 동일 계좌·broker order ID가 다시 확인되면 새 행을 만들지 않고 기존 행을 사용한다.
- 기존 행의 `orderIntentId`가 현재 attempt와 다르면 연결하거나 덮어쓰지 않고 수동 검토 오류로 처리한다.
- 여러 SubmissionAttempt가 동일 BrokerOrder를 참조할 수 있다.
- attempt와 broker order의 `orderIntentId` 일치는 composite FK로 강제한다.

### 3.4 ReconciliationCheck

append-only 필드:

- `id`
- `submissionAttemptId`
- `orderIntentId`
- `checkNumber`
- `openOrdersComplete`
- `closedOrdersComplete`
- `closedWindowStart`
- `closedWindowEnd`
- `allPagesRead`
- `resultHash`
- `matchedBrokerOrderId`
- `decision`
- `checkedAt`

결정:

- `BROKER_ORDER_FOUND`
- `RETRY_SAME_KEY_ALLOWED`
- `MANUAL_REVIEW_REQUIRED`

규칙:

- 정확한 client order ID 일치가 있을 때만 `BROKER_ORDER_FOUND`
- 조회가 완전하고 정확한 일치가 없으며 멱등 시간이 남았을 때만 `RETRY_SAME_KEY_ALLOWED`
- 조회 불완전, 다중·충돌 결과, 멱등 만료는 `MANUAL_REVIEW_REQUIRED`
- CLOSED 미검색만으로 미접수를 확정하지 않는다.
- `(matchedBrokerOrderId, orderIntentId)`는 `broker_orders(id, order_intent_id)` composite FK로 같은 intent를 강제한다.
- `UNIQUE(submissionAttemptId, checkNumber)`로 attempt별 조정 순서를 고정한다.
- `checkNumber >= 1`, `lastReconciliationCheckNumber >= 0`을 DB CHECK로 강제한다.
- 최신 결정은 가장 큰 `checkNumber`의 행으로만 판단한다.
- 서비스는 attempt의 `lastReconciliationCheckNumber`를 증가시키고 같은 트랜잭션에서 해당 번호의 check를 삽입한다. 동시 검사는 attempt `@Version`으로 하나만 커밋된다.

## 4. 10분 멱등 재시도

최초 attempt 생성 시:

```text
idempotencyExpiresAt = createdAt + 10분
```

자동 재시도 허용 조건:

```text
now < idempotencyExpiresAt
AND latest reconciliation decision = RETRY_SAME_KEY_ALLOWED
AND retry clientOrderId = original clientOrderId
AND retry requestBodyHash = original requestBodyHash
AND retry orderIntentId = original orderIntentId
```

`now == idempotencyExpiresAt`부터 자동 재전송을 금지한다. 만료 후에는 새 client order ID와 기존 client order ID 모두 자동 생성하지 않고 수동 검토로 전환한다.

하나의 attempt에는 자동 retry child를 최대 하나만 허용한다. 연속 재시도는 직전 attempt를 기준으로 별도 조정 검사를 통과해야 한다.

## 5. 트랜잭션 서비스

`OrderSubmissionService`가 다음 명령을 제공한다.

- `createInitialAttempt(...)`
- `startDispatch(...)`
- `markUnknown(...)`
- `recordReconciliation(...)`
- `retrySameKey(...)`
- `confirmBrokerOrder(...)`
- `markBrokerRejected(...)`

각 public 명령은 하나의 `@Transactional` 경계다.
필요한 `OrderIntent` 명령으로 `requireReconciliation()`과 `requireManualReview()`를 추가하고 기존 `markSubmissionPending()`, `activate()`와 함께 애그리거트 내부 전이 검증을 사용한다.

### 5.1 UNKNOWN 처리

1. `DISPATCHING` attempt를 `UNKNOWN`으로 전환한다.
2. `OrderIntent`가 `SUBMISSION_PENDING`이면 `RECONCILIATION_REQUIRED`로 전환한다.
3. attempt/intent 변경과 감사·Outbox를 같은 트랜잭션에 저장한다.

### 5.2 조정 결과 기록

서비스 입력값은 조회 완전성, 조회 시간 범위, 전체 페이지 여부, 결과 hash, 정확히 일치한 BrokerOrder 정보, 실제 broker 상태, 누적 체결 수량과 체결 snapshot 시각이다.

- 정확한 일치 1건: BrokerOrder를 생성하거나 같은 intent의 기존 행을 재사용하고 attempt를 ACKNOWLEDGED로 연결한다. FK 대상 BrokerOrder 행을 먼저 생성·확인하고 ExecutionSnapshot을 추가한 뒤 BrokerOrder projection과 OrderIntent를 갱신한다.
- 완전 조회·일치 없음·10분 이내: check를 `RETRY_SAME_KEY_ALLOWED`로 기록하고 attempt를 `RECONCILED_NO_MATCH`로 전환한다.
- 그 외: check를 `MANUAL_REVIEW_REQUIRED`, attempt를 `RECONCILIATION_FAILED`, intent를 `MANUAL_REVIEW_REQUIRED`로 전환한다.

BrokerOrder 확인 시 OrderIntent 반영:

| 실제 broker 상태 | 누적 체결 수량 | OrderIntent 결과 |
| --- | ---: | --- |
| `PENDING`, `PARTIALLY_FILLED`, 취소·정정 진행 중 | 수량 이하 | `ACTIVE` |
| `FILLED` | intent 수량 | `COMPLETED`, `(finalFilledQuantity=quantity, remainingQuantity=0)` |
| `CANCELED`, `REJECTED`, `REPLACED` | `0` | `CANCELED`, `(0, quantity)` |
| `CANCELED`, `REJECTED`, `REPLACED` | `0 < filled < quantity` | `PARTIALLY_COMPLETED`, `(filled, quantity-filled)` |

`PARTIALLY_COMPLETED` 전환 전에 해당 BrokerOrder가 더 이상 체결 가능하지 않고 최신 ExecutionSnapshot의 누적 체결 수량이 종료 수량과 일치해야 한다. 상태와 수량이 충돌하거나 체결 증거가 부족하면 intent를 종료하지 않고 `MANUAL_REVIEW_REQUIRED`로 전환한다.

명시적 broker order ID가 생성되기 전의 거절은 BrokerOrder를 만들지 않는다. attempt를 `BROKER_REJECTED`, intent를 `REJECTED`로 전환하며 `terminalReason`, `terminalAt`, `finalFilledQuantity=0`, `remainingQuantity=quantity`와 양쪽 감사·Outbox를 같은 트랜잭션에 저장한다.

### 5.3 동일 키 retry

1. parent attempt에서 가장 큰 `checkNumber`의 결정이 `RETRY_SAME_KEY_ALLOWED`인지 확인한다.
2. `now < parent.idempotencyExpiresAt`을 확인한다.
3. parent와 같은 intent, client order ID, body hash, 멱등 만료시각을 가진 child attempt를 생성한다.
4. child는 새 internal idempotency key와 다음 attempt number를 가진다.
5. intent를 `RECONCILIATION_REQUIRED -> SUBMISSION_PENDING`으로 전환한다.
6. child 생성, intent 상태, 제출 감사·Outbox, 기존 V2 intent 감사·Outbox를 한 트랜잭션에 저장한다.

### 5.4 감사와 Outbox

제출 도메인 명령마다 다음 두 행을 추가한다.

- `order_submission_audit_logs`: append-only
- `order_submission_outbox_events`: business payload 불변, `publishedAt`과 `attempts`만 변경 가능

공통 필드:

- `orderIntentId`
- `aggregateType`
- `aggregateId`
- `action` 또는 `eventType`
- `actor`
- `payload`
- 동일한 `occurredAt`

상태 변경, 감사, Outbox 중 하나라도 실패하면 전체 트랜잭션을 롤백한다. 낙관적 락 충돌의 패자 트랜잭션도 감사·Outbox를 남기지 않는다.

`OrderIntent` 상태도 변경하는 `markUnknown`, `retrySameKey`, `confirmBrokerOrder`, 수동 검토 전환은 기존 V2 `order_intent_audit_logs`와 `order_intent_outbox_events`에도 intent 전이를 기록한다. 제출 전용 원장은 attempt, reconciliation, BrokerOrder 행위를 기록하고 V2 원장을 대체하지 않는다. 두 원장 중 하나라도 실패하면 전체 트랜잭션을 롤백한다.

## 6. PostgreSQL V3 제약

- SubmissionAttempt 상태와 전이 CHECK/trigger
- `order_intents UNIQUE(id, broker_account_id)`
- attempt 불변 요청 필드 UPDATE 차단
- retry 원본과 intent/client ID/body hash/expiry 일치 trigger
- `UNIQUE(order_intent_id, attempt_number)`
- `UNIQUE(internal_idempotency_key)`
- `submission_idempotency_keys PRIMARY KEY(broker_account_id, client_order_id)`
- canonical key의 `(order_intent_id, broker_account_id)`와 attempt/BrokerOrder의 동일 컬럼은 `order_intents(id, broker_account_id)` composite FK
- canonical key `UNIQUE(broker_account_id, client_order_id, order_intent_id, request_body_hash, idempotency_expires_at)`
- attempt와 canonical key의 account/client ID/intent/body hash/idempotency expiry composite FK
- 원 attempt별 retry child 최대 하나
- retry INSERT는 `NEW.created_at < original.idempotency_expires_at`일 때만 허용하고 정확히 같은 시각부터 차단
- ACKNOWLEDGED와 confirmed BrokerOrder nullability 일치
- attempt/BrokerOrder intent composite FK
- ReconciliationCheck/BrokerOrder intent composite FK
- ReconciliationCheck UPDATE/DELETE 차단
- `UNIQUE(submission_attempt_id, check_number)`
- 감사 로그 UPDATE/DELETE 차단
- Outbox business field UPDATE와 DELETE 차단

DB trigger의 현재 시각에 의존하지 않는다. retry 생성 시 서비스가 전달한 `createdAt`과 원 attempt의 `idempotencyExpiresAt`을 비교해 재현 가능한 경계 검증을 수행한다.

## 7. 테스트 완료 조건

### 도메인 테스트

- 허용·금지 상태 전이
- 종료 상태 재전이 금지
- client order ID 형식
- ACKNOWLEDGED BrokerOrder 필수

### Flyway/PostgreSQL 통합 테스트

- V1→V2→V3 마이그레이션
- unique 및 composite FK
- 요청 필드 불변성
- 다른 intent BrokerOrder 연결 차단
- 여러 attempt의 동일 BrokerOrder 연결 허용
- 같은 계좌·client order ID의 다른 intent/body hash 사용 차단
- 다른 계좌의 동일 client order ID 허용
- 10분 미만 retry 허용
- 정확히 10분 및 이후 retry 차단
- client order ID/body hash 변경 retry 차단
- ReconciliationCheck와 감사 로그 append-only

### 서비스 통합 테스트

- UNKNOWN 전환과 intent 조정 상태 원자 저장
- 일치 BrokerOrder 확인과 N:1 연결
- 조정 check 번호의 순차 증가와 동시 번호 충돌
- 완전 조회·무일치 retry 허용
- 불완전 조회·만료 시 수동 검토
- broker 상태별 ACTIVE/COMPLETED/PARTIALLY_COMPLETED/CANCELED 반영
- broker order ID 생성 전 거절의 zero-fill REJECTED 원자 종료
- 동일 attempt 중복 retry 차단
- 감사/Outbox 실패 전체 롤백
- 낙관적 락 충돌 패자 전체 롤백
