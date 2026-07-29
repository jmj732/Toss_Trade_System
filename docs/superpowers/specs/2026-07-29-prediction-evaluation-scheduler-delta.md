# 예측 결과 스케줄 채점 Delta

## 결정

- 예측 `GET`은 저장된 예측·결과 집계만 조회한다. 브로커 호출과 DB 쓰기를 하지 않는다.
- 채점은 `prediction.evaluation.enabled=false`가 기본인 opt-in 스케줄러가 수행한다.
- 스케줄러는 전용 PostgreSQL TTL lease를 획득한 한 인스턴스만 한 tick을 실행한다.
- 각 tick은 예측별 가장 이른 미채점·도래 horizon 하나만 `D1 → D5 → D20` 순서로 채점한다.
  해당 quote가 실패하면 결과를 쓰지 않아 같은 horizon을 다음 tick에 재시도한다.
- quote는 tick 안에서 `(brokerConnectionId, symbol)`별 한 번만 조회한다.
- `PENDING` 상태는 별도 행이나 enum 없이, 도래했지만 outcome 행이 없는 상태로 유지한다.
- outcome에는 목표 시각 `targetDueAt`, quote의 `observationTime`, 둘의 차이인 `lag`를 저장한다.
  DB에는 `TIMESTAMPTZ`, `TIMESTAMPTZ`, 비음수 `BIGINT lag_ms`를 사용하고 API의 `lag`는
  ISO-8601 `Duration`으로 노출한다.
- 기존 `(prediction_id, horizon)` unique와 append-only trigger, 수익률·적중률·MAE 집계식은
  변경하지 않는다.

## 범위 제외

- 예측 생성, 주문 import·생성·승인·실행 동작은 변경하지 않는다.
- 거래일 달력과 과거 시세 보간은 추가하지 않는다. horizon은 기존과 같이 calendar day다.
