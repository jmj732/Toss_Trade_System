# 제출 직전 재검증 delta

## 범위

- 승인된 intent 를 브로커로 보내기 직전에 계좌 가용성을 **다시 읽어** 검증하는 단계를
  넣는다. 지금은 승인 시점 판단만으로 제출한다.
- 읽기 포트에 종목별 매도 가능 수량 조회를 추가한다. `sellable` 은 저장소 전체에서 0건이다.
- 재검증 실패 시 **브로커를 호출하지 않고** intent 를 `BLOCKED` 로 전환하고 구체적인
  risk reason 을 남긴다(SPEC:890).
- 실거래 진행이 확정됐으므로(Phase 0 Q2) 이 단계 없이는 승인 시점 잔고로 실제 주문이
  나간다. E6 의 선행 필수다.

## 재검증 항목

SPEC:875-889 는 12개 항목을 요구한다. 이 delta 는 그중 **의존 모듈이 이미 존재하는 것만**
구현한다. 나머지는 담당 항목에서 이 재검증 지점에 추가한다.

| SPEC 항목 | 이 delta |
|---|---|
| 1. 승인 사용자와 계좌 소유권 | 포함 |
| 3. 최신 holdings 와 `AccountCapacitySnapshot` 의 완전성·시각 | 포함 |
| 5. 통화별 cash buying power 또는 종목별 sellable quantity | 포함 |
| 6. 동일 종목 OPEN 주문 | 포함 |
| 2. TradeProposal 만료 | E1 (`proposal` 모듈 없음) |
| 4. 현재가·호가 timestamp 와 가격 편차 | B1 (`marketdata` 모듈 없음) |
| 10. 전체 주문 중지 kill switch | E4 |
| 11. 전략 활성 상태 | F1 |
| 12. 분석·portfolio snapshot 최대 나이 | C6 |
| 7·8·9 비중·손실·FX 환산 | 기존 `PreTradeRiskEngine` 재사용 범위에서 판단 |

- **재검증 지점은 확장 가능한 하나의 자리로 만든다.** 위 미구현 항목이 각자 담당 항목에서
  붙을 때 구조를 다시 뒤집지 않아도 되게 한다.

## 처리 불변식

- 재검증은 **브로커 호출 직전**에 수행한다. 승인 시점 스냅샷을 재사용하지 않는다.
- 하나라도 실패하면 브로커를 호출하지 않는다. `BLOCKED` 전환과 risk reason 기록이
  같은 트랜잭션에서 커밋된다.
- 재조회 결과가 승인 시점보다 **부족하면** 차단한다. 같거나 늘어난 경우만 통과다.
- **미제공 잔액은 `UNKNOWN` 을 유지한다**(SPEC:1078). `UNKNOWN` 을 0 으로 렌더하거나
  0 으로 간주해 통과시키지 않는다. `UNKNOWN` 은 차단 사유다 — 확인할 수 없으면 보내지 않는다.
- 매도 주문은 sellable quantity 로, 매수 주문은 통화별 buying power 로 판정한다.
- 재검증 자체가 브로커 오류로 실패하면 통과가 아니라 차단이다. fail-closed.
- 기존 `PreTradeRiskEngine` 의 승인 시점 판단을 없애지 않는다. 재검증은 추가 관문이다.

## TDD와 검증

- 재조회 sellable quantity 가 승인 시점보다 부족하면 제출 차단 + `BLOCKED` 전이
- 재조회 buying power 가 부족하면 제출 차단 + `BLOCKED` 전이
- 차단 시 **브로커 호출이 발생하지 않음**을 검증
- `UNKNOWN` 잔액이 0 으로 간주되지 않고 차단 사유가 됨
- 재검증 중 브로커 오류가 통과가 아니라 차단으로 이어짐(fail-closed)
- 계좌 소유권 불일치 시 차단
- 동일 종목 OPEN 주문 존재 시 정책대로 판정
- 재검증 통과 시 기존 제출 경로가 그대로 동작(회귀 없음)
- `BLOCKED` 전이에 구체적 risk reason 이 남음
- backend `./mvnw clean verify`
