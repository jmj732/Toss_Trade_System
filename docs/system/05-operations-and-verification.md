# 05. 운영, 배포, 검증과 복구

이 문서는 저장소의 기본값과 자동화가 보장하는 절차를 설명한다. 현재 배포 상태, 외부 provider의 가용성, 실제 ChatGPT/MCP 연결, 데이터 신선도는 일반적으로 여기서 보장하지 않는다. 날짜와 workflow run이 명시된 진단 결과만 해당 시점의 증거로 취급한다. 비밀 값과 계좌·시장 데이터는 기록하지 않는다.

## 배포 토폴로지와 시작 조건

기본 [`compose.yaml`](../../compose.yaml)은 PostgreSQL 16, Redis 7, FastAPI `analysis`, Spring `backend`, Next.js `dashboard`로 구성된다. Compose 기본 포트는 loopback에만 게시되고 각 서비스에 healthcheck가 있다. backend는 PostgreSQL·Redis·analysis가 healthy인 뒤 시작한다. 기본 환경은 broker credentials, 실제 주문, market data collection, Sheets, monitoring, market-events, Telegram 등 외부 영향이 있는 기능을 대체로 비활성화한다. Compose 파일의 기본값은 특정 배포에서 override된 설정을 증명하지 않는다.

저장소에는 실행 형태가 다른 두 경로가 있다.

| 경로 | 목적과 경계 |
|---|---|
| 로컬/CI Compose | mock provider를 포함한 격리 stack과 readiness/smoke 확인. `scripts/test-local-stack.sh`, `scripts/smoke-local-stack.sh`의 범위를 따른다. |
| credentialed 배포 | `.github/workflows/cd.yml`이 성공한 `main` push의 동일 SHA로 backend·analysis·dashboard 이미지를 만들고, SSH로 전달해 staging credential overlay에서 Compose 배포한다. Doppler 및 서버 secret store 값은 문서에 기록하지 않는다. 별도 Vercel dashboard deploy job도 있다. |

CD는 `migrate` one-shot service가 끝난 다음 backend와 dashboard를 올리고 `/actuator/health/readiness`를 확인한다. `compose.staging.yaml`의 migrate service는 backend와 같은 pinned image의 격리된 `MigrateOnlyApplication` entrypoint를 사용하며, backend replica는 `SPRING_FLYWAY_ENABLED=false`로 실행된다. 따라서 배포 구성에서는 migration을 backend replica마다 실행하지 않는다. Compose 자체는 고가용 오케스트레이터나 자동 확장 시스템이 아니다. 세부 원격 설정과 배포 전제는 [backend CD runbook](../ops/backend-cd.md), [staging deploy/rollback](../ops/staging-deploy-rollback.md)을 참조한다.

## 설정과 scheduler 기본값

Spring 설정은 [`application.yml`](../../trading-backend/src/main/resources/application.yml), Compose 환경, 그리고 배포 secret/config overlay의 결합이다. `ddl-auto=validate`이므로 Hibernate가 schema를 자동 생성하거나 변경하지 않는다. Compose 기본 설정에서 주요 수집·연동 scheduler는 꺼져 있다. 활성화 여부를 추정하지 말고 배포 시 유효 설정과 scheduler 실행 기록을 확인한다.

| 기능 | 기본 설정 (환경변수) | 영향 / 확인 기준 |
|---|---|---|
| 투자 데이터 수집 | `INVESTMENT_DATA_SCHEDULER_ENABLED=false`; intraday `PT5M`, initial delay `PT1M`; timezone `America/New_York` | 켜졌을 때 저장된 시세·정규장 종가·기초·컨센서스 snapshot을 수집한다. 주중 정규장 구간과 cron capture가 별도다. 장중 스케줄은 거래소 휴일을 자체적으로 증명하지 않으며 provider freshness/calendar gate가 별도로 적용된다. |
| Google Sheets projection | `INVESTMENT_OS_SHEET_ENABLED=false`; interval `PT5M`, initial delay `PT1M`, lock TTL `PT10M` | DB view를 Sheet에 투영하고 계좌 수동 입력을 읽을 수 있다. 계좌/연구 열 소유권은 [04장](04-api-mcp-and-sheets.md#google-sheets와-postgresql-열-소유권) 및 [Sheet runbook](../ops/investment-os-sheet-sync.md)을 따른다. |
| 시장 이벤트 수집 | `MARKET_EVENTS_SCHEDULER_ENABLED=false`; 기본 주기 `PT15M`; 개별 SEC/IR/Fed/FRED/BLS/BEA provider도 각각 false | scheduler와 각 provider switch가 모두 유효해야 한다. 제한·lease·재시도 설정은 같은 YAML에 있다. |
| 보유·위험 monitoring | `MONITORING_SCHEDULER_ENABLED=false`; cycle `PT15M`, price `PT10M`, macro `PT1H` | 감시 결과는 저장된 snapshot과 provider freshness에 의존한다. notifier가 켜졌다고 monitoring scheduler까지 켜지는 것은 아니다. |
| 알림/주문 outbox | Telegram 기본 off; notification/order outbox도 Compose 기본 off | 전달 worker가 활성화되면 외부 전송/주문 효과가 가능하다. enabled, batch, retry/lease 설정과 outbox backlog를 함께 본다. |
| Thesis AI policy 및 주문 | policy와 `REAL_ORDER_ENABLED` 모두 기본 false | proposal 검증/승인 및 실제 주문 실행은 별도 scope와 사용자 정책을 포함한다. 활성 값은 해당 배포에서 직접 확인한다. |

Scheduler의 시간대, 시작 지연, 간격, 공급자별 활성화는 [`application.yml`](../../trading-backend/src/main/resources/application.yml)과 credentialed overlay에서 확인한다. 설정값이 곧 성공 실행이나 최근 데이터의 증거는 아니다. 정책/수집이 꺼져 있거나 provider 실패 시 저장 context는 오래되거나 부분 상태일 수 있으므로 `status`, `stale`, `asOf`, `missingFields`를 함께 확인한다.

## Health, 진단과 관측

- backend readiness는 `GET /actuator/health/readiness`다. readiness group에는 application readiness state, DB, Redis, analysis service가 들어간다. Compose healthcheck와 CD post-deploy curl도 이 경로를 확인한다. `UP`은 해당 시점의 readiness dependency 결과이지 Toss/시장 provider, Sheets, ChatGPT task, 실제 주문 전체를 검증한 결과가 아니다.
- 단일 응답 확인 예: `curl -fsS http://127.0.0.1:8080/actuator/health/readiness`. 배포 host에서 접근 가능 여부는 reverse proxy와 network 구성에 달려 있다. readiness 본문이 구성에 따라 상세 상태를 노출할 수 있으므로 외부 공개 설정을 별도로 관리한다.
- actuator는 `health`, `metrics`, `prometheus`를 expose하도록 설정되어 있다. 로그는 structured ECS console format이다. `CorrelationIdFilter`는 UUID 모양의 요청 상관 ID를 전파하거나 생성하고, operation/outcome/status/duration을 기록한다. `trade.operation.duration` timer와 `WorkflowMetrics`의 stale-running, outbox backlog, dead-letter gauge가 코드에 있다. Compose에는 Prometheus/Grafana/Alertmanager 수집·보관·경보 stack이 포함되지 않는다. endpoint 노출은 실제 scrape나 알림이 설정됐다는 뜻이 아니다.
- 로그인 사용자용 `GET /api/v1/operations/readiness`는 저장된 provider readiness를 읽는다. `POST /api/v1/operations/readiness/provider-check`는 외부 provider를 직접 호출할 수 있으므로 일반 health check가 아니다. [OperationalReadinessController](../../trading-backend/src/main/java/com/jmj/trade/observability/OperationalReadinessController.java)
- 수동 GitHub workflow [`runtime-readonly-diagnostic.yml`](../../.github/workflows/runtime-readonly-diagnostic.yml)과 [`runtime-readonly-diagnostic.sh`](../../scripts/runtime-readonly-diagnostic.sh)은 지정된 staging runtime에서 host/container liveness·readiness와 unauthenticated MCP HTTP status, container healthcheck/runtime/cgroup counters, 최근 30분 allowlisted exception counts, DB/Flyway 및 일부 sanitized 설정/저장 상태를 읽는다. HTTP 진단에는 Tomcat acceptor/poller/executor thread counts, allowlisted `futex`/`epoll`/accept/`pipe_write`/other wait-channel counts, port 8080 listener/receive queue, process FD count와 open-file soft/hard limits, `jcmd`/`jstack` availability booleans, JVM heap/`MaxRAMPercentage`/fail-fast option presence도 포함된다. 별도 로그 요약은 최근 36시간 중 최대 10,000줄에서 Tomcat acceptor, `Too many open files`/`EMFILE`, accept failure, `OutOfMemoryError`와 고정 OOM subtype의 count 및 OOM/Acceptor same-line count만 센다. PID/TID, thread name, socket address, descriptor path, stack, response body, log message, credentials는 artifact에 포함하지 않는다. 진단은 container 재시작, Compose 변경, provider 호출, MCP tool 호출, 사용자 credential 검증, ChatGPT Scheduled Task 검증을 하지 않는다. workflow는 수동 실행 전까지 현재 runtime 상태의 증거가 아니다.

2026-10-19 staging incident에서 workflow run `39255015258`은 12:49:33Z 관측 시점 기준 최근 36시간 `Java heap space` OOM 4건을 셌고, 그중 1개 로그 행에서 Tomcat Acceptor 종료 신호도 함께 확인했다. 같은 incident의 runtime snapshot은 port 8080 listener 1개와 receive queue 101, Acceptor 0개를 보였으며 HTTP/health probe는 응답하지 않았다. DB 조회는 성공했고 계좌 동기화 최신 attempt는 12:48:53Z `SUCCEEDED`, 최신 accepted snapshot의 account1 `asOf`는 12:48:57Z였다. 이는 모든 scheduler가 정상이라는 증거는 아니다. 증거는 heap OOM 이후 Acceptor가 종료되고 요청 queue가 쌓여 HTTP 경로가 중단된 실패 사슬을 확인한다. backend는 해당 snapshot에서 unhealthy였으며, 그 run 이후 상태나 복구 여부는 이 artifact가 확인하지 않는다. 최초 heap pressure의 원인은 확인되지 않았다.

Backend image는 `-XX:+ExitOnOutOfMemoryError`로 OOM 뒤 JVM을 종료하고, staging은 base [`compose.yaml`](../../compose.yaml)의 `restart: unless-stopped` 정책으로 종료된 backend를 다시 시작한다. `-XX:MaxRAMPercentage=50.0`은 768 MiB backend memory limit에서 maximum heap estimate를 기존 약 192 MiB에서 약 384 MiB로 올려 nominal non-heap/native headroom을 남긴다. 이는 자동 회복 경로와 heap sizing guardrail이며 최초 memory pressure 원인을 해결하거나 OOM이 다시 일어나지 않음을 보장하지 않는다. incident 복구는 새 host/container health 및 HTTP probe가 통과한 뒤에만 확인한다.

## CI, CD와 검증 증거

PR/push `main` CI는 변경 경로와 exact-SHA verification evidence를 보고 job을 선택할 수 있다. backend·analysis·dashboard·Compose stack 별 workflow가 있으며 CI gate는 필수 job 결과 `success` 또는 조건부 `skipped`를 허용한다. 변경 없는 component가 건너뛸 수 있어 모든 commit에서 모든 suite를 다시 실행한다고 단정하지 않는다. 구체적 workflow는 [CI entrypoint](../../.github/workflows/ci.yml), [backend](../../.github/workflows/ci-backend.yml), [analysis](../../.github/workflows/ci-analysis.yml), [dashboard](../../.github/workflows/ci-dashboard.yml), [stack](../../.github/workflows/ci-stack.yml)이다.

주요 실행 명령은 다음과 같다. 목적에 맞는 격리 환경에서 실행하고, CD나 provider 연결을 대신하는 증거로 취급하지 않는다.

| 범위 | 저장소의 명령 | 증거 / 한계 |
|---|---|---|
| Spring backend | `cd trading-backend && ./mvnw --no-transfer-progress -DforkCount=1C -DreuseForks=true -DargLine=-Xmx1024m -Djunit.jupiter.execution.parallel.enabled=false clean verify` | Maven 검증 및 Surefire 결과. CI는 클래스 내 공유 DB 경쟁을 줄이려고 JUnit parallel 실행을 끈다. |
| FastAPI | `cd analysis-service && pip install -e ".[test]" && pytest -q --junitxml=pytest-results.xml` | 해당 서비스 test suite 및 JUnit XML. Python 3.12 CI 환경. |
| Dashboard | `cd web-dashboard && npm ci && npm test && npm run build && npm audit --omit=dev` | Node 22 workflow에서 UI test/build와 production dependency audit. |
| Compose 계약/smoke | `bash scripts/test-backend-cd.sh`; `bash scripts/test-runtime-readonly-diagnostic.sh`; `bash scripts/test-cd-image-reuse.sh`; `./scripts/test-local-stack.sh`; `./scripts/smoke-local-stack.sh` | mock/격리 stack 및 workflow contract. 실제 provider·계정·배포 host 검증은 아님. |
| 검증 기록 | CI `ci-verification` artifact 및 workflow/job 결과 | exact commit 및 실행/skip 기록을 함께 확인한다. 이 저장소 변경 작업에서 과거 테스트 수를 새로 검증하지 않았다. |

CI 결과를 운영 또는 사용자 계정 상태와 혼동하지 않는다. 이번 문서 변경에서는 test suite, deployment, live provider, Scheduled Task를 실행하지 않았다. 즉, 이 문서는 procedure와 code contract만 설명하며 live proof를 주장하지 않는다.

## Flyway와 schema 변경

Spring 기본은 Flyway와 `ddl-auto=validate`를 사용하며, Hibernate 자동 schema 수정은 꺼져 있다. 현재 저장소에서 확인한 가장 높은 migration은 [`V62`](../../trading-backend/src/main/resources/db/migration/V62__investment_risk_event_links.sql)다. 이는 DB가 실제로 V62까지 적용된 증거는 아니다. 변경 적용 전 대상 DB의 Flyway history와 배포 image SHA를 확인한다.

staging CD의 순서는 `migrate` one-shot 완료 → backend/dashboard up → readiness 확인이다. migration 실패 시 backend 시작 조건을 통과하지 못해야 한다. `backend` 직접 시작/로컬 compose에서는 별도 overlay가 Flyway를 끄지 않는 한 Spring startup migration이 적용될 수 있다. 운영 migration을 수동으로 재실행하거나 schema를 임의로 수정하는 대신 [backend CD runbook](../ops/backend-cd.md)과 staging workflow를 따른다. Flyway 자동 down migration은 설정되어 있지 않다. 애플리케이션 이미지 rollback이 schema rollback이라는 뜻도 아니다.

## Backup, restore와 복구 범위

`scripts/backup-postgres.sh <output.dump>`는 실행 중 PostgreSQL의 `trade` DB를 `pg_dump -Fc` 형식으로 저장한다. 기본 예:

```sh
./scripts/backup-postgres.sh backups/trade.dump
```

복원은 `scripts/restore-postgres.sh <input.dump>`가 `pg_restore --clean --if-exists`로 기존 object를 교체하므로 **대상 DB에 파괴적인 작업**이다. write 작업을 중단하고 대상 DB·dump를 확인한 뒤 [Postgres backup/restore runbook](../ops/postgres-backup-restore.md)의 순서를 따라야 한다. 여기서는 복원 명령을 실행하지 않았다.

격리된 자동 drill은 다음 명령으로 실행된다.

```sh
./scripts/backup-restore-drill.sh
```

스크립트는 자체 임시 Compose project에서 canary를 seed하고 DB backup/volume 삭제/복원을 거쳐 canary 생존과 backend startup을 확인하도록 작성됐다. 이것은 격리 drill 증거다. 실DB 백업 주기·암호화·오프사이트 보관·retention·PITR/WAL은 이 자동화에 포함되지 않는다. Redis cache/session/OAuth token cache는 백업 범위 밖이라 restore 후 비어 있고 사용자는 다시 로그인해야 한다. 데이터베이스 backup만으로 외부 provider나 Sheets의 상태를 복원할 수도 없다.

Compose 수준 image 교체 drill은 `./scripts/staging-deploy-rollback-drill.sh`이며 mock profile에서 A→B→A 이미지 tag와 readiness를 확인한다. 실제 registry/운영 deployment rollback이나 schema rollback 검증은 아니다. real deploy의 실패 원인에 따라 이전 app image가 현재 DB schema와 호환되는지 확인해야 한다. 자세한 범위는 [staging rollback runbook](../ops/staging-deploy-rollback.md)에 있다.

## 장애 대응 경로와 한계

| 징후 | 우선 확인 | 복구 판단 경계 |
|---|---|---|
| backend readiness 실패 | Compose service 상태/health, backend 로그, DB/Redis/analysis health, migration service 종료 코드와 Flyway history | provider readiness endpoint를 호출해 원인을 찾지 않는다. DB schema mismatch라면 임의 SQL 수정이나 이전 image만의 rollback을 하지 않는다. |
| context 가격·기초 값이 오래됐거나 부분 상태 | 저장 view의 `asOf`, `stale`, `missingFields`, collection 상태와 provider status, scheduler/provider enablement | context GET은 refresh하지 않는다. 수집이 활성화되어도 provider limit/실패/market calendar gate 때문에 stale/missing일 수 있다. |
| Sheets sync 누락/충돌 | Sheet sync job/API 결과, 기존 `RESEARCH_MIRROR_SCHEMA_CONFLICT`, header/권한/마지막 snapshot | research tab을 canonical DB 입력으로 수동 편집하지 않는다. 호환되지 않는 header면 기존 tab 보호/skip 동작을 확인하고, `ACCOUNT_2` 입력은 04장 소유권 규칙을 따른다. |
| outbox backlog/dead-letter | `trade.outbox.backlog`, `trade.outbox.dead_letter` gauge, worker 상태·재시도 및 idempotency key | 저장 기록을 확인하고 원인을 제거하기 전에 무작정 replay하지 않는다. Telegram 전송 수락 뒤 프로세스 중단은 재전송을 일으킬 수 있다. |
| 주문 요청 결과 불명확 | order 상태와 reconciliation ledger, broker 조회, [`order-reconciliation-runbook`](../ops/order-reconciliation-runbook.md) | 제출 여부가 불명확하면 blind resubmit하지 않는다. 같은 client/order 키와 broker 상태를 reconciliation 절차로 맞춘다. |
| 배포 실패 또는 rollback 필요 | CI exact SHA/artifact, 배포 로그, image tag, migrate 결과, readiness | 이전 image 전환만으로 DB를 되돌릴 수 없다. migration/app 호환성을 먼저 확인한다. 자동 schema down migration은 없다. |

주문 canary, provider preflight, credentialed staging, API의 `provider-check`는 외부 연결/데이터에 영향을 줄 수 있는 명시적인 운영 행위다. 여기서는 실행하지 않는다. credentialed preflight 절차는 [staging deploy runbook](../ops/staging-deploy-rollback.md#credentialed-staging)을 사용하고 secret 값을 출력·문서화하지 않는다. 운영 현재값이 필요하면 해당 환경에서 승인된 진단을 실행해 시각, commit/image SHA, 검사 범위와 함께 별도로 기록한다.

## 근거 파일

- 배포/서비스: [`compose.yaml`](../../compose.yaml), [`compose.staging.yaml`](../../compose.staging.yaml), [`compose.staging.credentialed.yaml`](../../compose.staging.credentialed.yaml), [CD workflow](../../.github/workflows/cd.yml)
- scheduler/health/로그: [`application.yml`](../../trading-backend/src/main/resources/application.yml), [`CorrelationIdFilter`](../../trading-backend/src/main/java/com/jmj/trade/observability/CorrelationIdFilter.java), [`WorkflowMetrics`](../../trading-backend/src/main/java/com/jmj/trade/observability/WorkflowMetrics.java)
- schema: [`db/migration`](../../trading-backend/src/main/resources/db/migration/)
- 운영 절차: [backup/restore](../ops/postgres-backup-restore.md), [continuous monitoring](../ops/continuous-monitoring.md), [Sheet sync](../ops/investment-os-sheet-sync.md), [backend CD](../ops/backend-cd.md), [rollback](../ops/staging-deploy-rollback.md)
