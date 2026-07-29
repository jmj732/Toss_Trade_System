# 예측 채점 관찰성 delta

## 결정 사항

- 기존 예측 GET, 채점 순서와 저장, 집계 수학은 변경하지 않는다.
- `PredictionEvaluationMetrics`가 Micrometer 메트릭을 소유한다.
- `trade.prediction.evaluation.backlog` gauge는 활성 broker connection에 속하며 현재
  처리 가능한 earliest due/ungraded horizon 수를 PostgreSQL에서 계산한다. D1 결과가
  없으면 D1만, D1 결과가 있고 D5 결과가 없으면 D5만, D1·D5 결과가 있고 D20 결과가
  없으면 D20만 후보로 센다.
- `trade.prediction.evaluation.max.lag.ms` gauge는 위 후보 중 가장 오래 지연된
  `CURRENT_TIMESTAMP - target_due_at`을 밀리초로 노출한다. 후보가 없으면 `0`이다.
- 두 gauge는 scrape마다 각각 SQL을 실행하지 않는다. 하나의 SQL로 backlog count와
  oldest target due 시각을 읽고 JVM snapshot으로 공유한다. snapshot 기본 TTL은
  `prediction.evaluation.metrics-cache-ttl=PT30S`이며 동시 refresh는 한 thread만
  수행한다. lag는 cached oldest due 시각과 현재 시각으로 메모리에서 계산한다.
- snapshot refresh가 실패하면 scrape 요청으로 DB 장애를 전파하지 않고 마지막 성공
  snapshot을 유지한다. 아직 성공한 refresh가 없으면 초기값인 backlog `0`, oldest
  due 없음, lag `0`을 유지한다. materialized view, 별도 polling scheduler, 외부
  cache는 추가하지 않는다.
- tick 처리 결과는 cumulative counter로 기록한다.
  - `trade.prediction.evaluation.attempted`
  - `trade.prediction.evaluation.succeeded`
  - `trade.prediction.evaluation.quote.failed`
- quote 조회 예외, 유효하지 않은 가격, target due 시각보다 오래된 관측은
  `quote.failed`로 센다. unique 충돌로 다른 evaluator의 결과를 확인한 경우는
  attempted에는 포함하지만 succeeded나 quote.failed에는 포함하지 않는다.
- 내부 단건 평가 결과는 `GRADED`, `QUOTE_FAILED`, `DUPLICATE`로 구분한다. 기존
  `evaluateDue(...)`의 `int` 반환값은 계속 성공적으로 추가된 outcome 수만 의미한다.
- lease 획득·갱신 실패는 `trade.prediction.evaluation.lease.failure` counter의
  `stage=acquire|renew` tag로 구분한다.
- tick이 건수 또는 시간 상한에 도달해 다음 batch를 시작하지 않으면
  `trade.prediction.evaluation.early.stop` counter의 `reason=count|time` tag로
  구분한다. 건수 상한은 `max-per-tick`에 도달한 tick을 의미하며 잔여 backlog
  존재 여부를 확인하기 위한 추가 query는 실행하지 않는다.
- batch 전 continuation 검사는 시간 상한을 먼저 확인하고, 시간이 남았을 때만 lease를
  갱신한다. 시간 초과는 `early.stop{reason=time}`만, lease 갱신 실패는
  `lease.failure{stage=renew}`만 기록한다. lease 획득 실패는 tick을 시작하지 않으며
  `lease.failure{stage=acquire}`만 기록한다.
- scheduler는 상세 평가 결과를 메트릭에 기록하지만 기존 `evaluateDue` 반환값과
  외부 호출 의미는 유지한다.
- Prometheus registry를 runtime dependency로 추가하고 기존 Actuator 경계 안에서만
  `/actuator/prometheus`를 노출한다. 별도 public management port, reverse-proxy route,
  SecurityFilterChain permit 예외는 추가하지 않는다.

## 배포 설정

- `compose.yaml` backend가 다음 환경변수를 Spring property로 전달한다.
  - `PREDICTION_EVALUATION_ENABLED` 기본값 `false`
  - `PREDICTION_EVALUATION_INTERVAL` 기본값 `PT1H`
  - `PREDICTION_EVALUATION_INITIAL_DELAY` 기본값 `PT1M`
  - `PREDICTION_EVALUATION_LOCK_TTL` 기본값 `PT10M`
  - `PREDICTION_EVALUATION_BATCH_SIZE` 기본값 `100`
  - `PREDICTION_EVALUATION_MAX_PER_TICK` 기본값 `1000`
  - `PREDICTION_EVALUATION_MAX_RUNTIME` 기본값 `PT5M`
  - `PREDICTION_EVALUATION_METRICS_CACHE_TTL` 기본값 `PT30S`
- `.env.example`과 `.env.staging.example`은 같은 설정을 문서화하며 scheduler는
  명시적으로 활성화하기 전까지 실행되지 않는다.
- `docs/ops/prediction-evaluation-runbook.md`는 활성화, 메트릭 확인, PromQL,
  경보 기준, 안전 중지와 rollback 절차를 기록한다. endpoint 확인은 기존 backend
  loopback/compose network 경계 안에서 수행하고 외부 scrape가 필요하면 기존 배포의
  network ACL 또는 인증 경계를 먼저 적용하도록 명시한다.
- compose가 application 설정을 덮어쓰므로
  `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE`에 `prometheus`가 포함되는지 검증한다.
  이 변경은 endpoint 목록만 확장하며 backend의 기존 host bind, port, proxy와
  SecurityFilterChain은 변경하지 않는다.

## SQL 실행계획 검증

- 이미 적용된 `V19__batch_prediction_evaluation.sql`은 수정하지 않는다.
- runbook에 실제 keyset candidate SQL의 `EXPLAIN (ANALYZE, BUFFERS)` 결과를 기록한다.
- 검증 데이터 규모, due 후보 비율, cursor와 limit, 사용된 인덱스, planning/execution
  time을 함께 명시하여 결과를 재현 가능한 운영 참고값으로 한정한다.
- computed `target_due_at`과 `UNION ALL` 때문에 PostgreSQL이 V19
  `(predicted_at, id)` 인덱스를 선택하지 않을 수 있다. 정확한 SQL·cursor·limit의
  실제 plan을 기록하며 인덱스 사용 자체를 통과 조건으로 만들지 않는다.

## 검증

- earliest horizon backlog와 max lag gauge가 D1 → D5 → D20 상태를 정확히 반영한다.
- 두 gauge가 TTL 안에서 하나의 DB snapshot query를 공유하고 refresh 실패 시 마지막
  성공값을 유지하는지 검증한다.
- 정상 채점, quote 실패, unique 충돌의 attempted/succeeded/quote-failed counter를
  검증한다.
- lease acquire/renew 실패와 count/time 상한 counter를 검증한다.
- scheduler 비활성 기본값과 compose/env property 전달을 검증한다.
- `/actuator/prometheus`가 새 public port, proxy route 또는 SecurityFilterChain 우회
  없이 기존 Actuator 네트워크 경계를 사용하는지 검증한다.
- 기존 prediction 통합 테스트, 전체 backend `./mvnw clean verify`, dashboard
  `npm test`를 실행한다.
