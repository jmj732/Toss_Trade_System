# Paper Trading Broker Delta

- `OrderIntent`에 `side`, `type`, `symbol`, `limitPrice`, `tradingCurrency`를 추가한다.
- 기존 행 호환을 위해 컬럼은 nullable로 마이그레이션하되 paper 실행은 모두 필수 검증한다.
- MARKET은 입력된 기준가에서, LIMIT은 BUY `기준가 <= 지정가`, SELL `기준가 >= 지정가`일 때 체결 가능하다.
- 체결 가능 수량은 입력된 유동성 수량과 잔량 중 작은 값이다.
- 미체결 지정가는 `BrokerOrder.PENDING`, 일부 체결은 `PARTIALLY_FILLED`, 전량 체결은 `FILLED`다.
- 취소는 최신 누적 체결량을 보존해 무체결 `CANCELED` 또는 `PARTIALLY_COMPLETED`로 종료한다.
- 명시적 거절은 BrokerOrder 생성 전 기존 `BROKER_REJECTED`/`REJECTED` 전이를 사용한다.
- UNKNOWN은 기존 reconciliation 원장으로 broker 발견 또는 complete no-match를 기록한다.
- paper broker order ID는 로컬 UUID 기반 opaque 문자열이며 Toss 호출을 하지 않는다.
- `(brokerAccountId, clientOrderId)` 기존 canonical key와 OrderIntent 행 잠금으로 멱등·동시 실행을 직렬화한다.
- 같은 key와 같은 request hash는 기존 attempt를 반환하고, 다른 hash/intent는 거절한다.
- 수수료는 `filledAmount * commissionRate`, 세금은 SELL만 `filledAmount * taxRate`로 계산한다.
- rate는 설정값이며 기본값은 0, 금액은 통화 최소 단위 설정 scale로 반올림한다.
- `ExecutionSnapshot`은 누적 수량·평균가·체결금액·수수료·세금·통화를 append-only로 저장한다.
- paper 실행 한 건은 attempt, broker order, snapshot, intent, audit/outbox를 한 트랜잭션으로 커밋한다.
- 외부 시세 조회, portfolio 반영, 현금/보유수량 검증, 실주문은 제외한다.
