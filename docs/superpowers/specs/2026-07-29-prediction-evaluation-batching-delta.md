# 예측 채점 배치 처리 delta

## 결정 사항

- 스케줄 채점은 전체 예측을 메모리로 읽지 않고, 미평가 상태이며 기준 시각에 도달한 예측만 PostgreSQL에서 조회한다.
- 후보는 horizon별 SQL을 합쳐 구성한다. D1은 D1 결과가 없을 때, D5는 D1 결과가 있고 D5 결과가 없을 때, D20은 D1·D5 결과가 있고 D20 결과가 없을 때만 선택한다.
- 후보 순서는 `(targetDueAt, predictionId)`로 고정하고 keyset cursor와 `LIMIT`를 사용한다.
- batch 기본 크기는 100이며 `prediction.evaluation.batch-size`로 설정한다.
- 한 tick은 due 후보가 소진될 때까지 batch를 반복하되 기본 최대 1,000건과 5분으로 제한한다. 각각 `prediction.evaluation.max-per-tick`, `prediction.evaluation.max-runtime`으로 설정한다.
- query limit은 `min(batchSize, maxPerTick - attemptedCount)`로 계산한다.
- tick에서 한 번 조회된 prediction ID는 성공·quote 실패와 무관하게 이후 batch 후보에서 제외한다. 따라서 오래 밀린 prediction도 같은 tick에서는 최초 horizon 하나만 시도하며 다음 horizon은 다음 tick까지 기다린다.
- quote memoization 범위는 기존과 같이 tick 전체의 `(brokerConnectionId, symbol)`이다.
- quote 실패 후보도 현재 cursor에서는 소비하여 같은 tick 재조회 루프를 막고, outcome을 쓰지 않아 다음 tick에 재시도한다.
- 각 batch 전에 `renew(owner)`가 `name`, `owner`, `expires_at > CURRENT_TIMESTAMP`를 모두 만족하는 행만 CAS 방식으로 갱신한다. 갱신 실패, 처리 건수 초과, 실행 시간 초과 시 다음 batch를 시작하지 않고 안전 종료한다.
- `analysis_predictions(predicted_at, id)` 조회 인덱스를 추가한다. outcome unique/append-only 제약은 변경하지 않는다.
- GET, 예측 생성, horizon 순서(D1 → D5 → D20), prediction별 tick당 한 horizon, quote 관측값 저장, 기존 집계 수학은 변경하지 않는다.

## 검증

- due-only 조회와 `(targetDueAt, predictionId)` 결정 순서를 검증한다.
- batch 크기, tick 최대 처리 건수, quote memoization, quote 실패 재시도 상태를 검증한다.
- D1·D5·D20이 모두 지연된 prediction도 반복 batch 한 tick에서는 D1만 처리됨을 검증한다.
- batch별 lease 갱신과 lease 상실 시 조기 종료를 검증한다.
- 기존 prediction 통합 테스트, 전체 백엔드 검증, 대시보드 테스트를 실행한다.
