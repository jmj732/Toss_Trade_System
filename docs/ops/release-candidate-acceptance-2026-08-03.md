# Release Candidate Acceptance — 2026-08-03

결론: 로컬 Release Candidate Gate 통과. 현재 환경에는 실 provider·Gemini·Toss 자격증명이 없어 외부 실호출과 실주문은 실행하지 않았고, canary는 주문 없는 preflight 경로로 종료했다.

## 설정 판정

- `.env`, `.env.local`, `.env.staging`과 실제 자격증명 파일 없음.
- `.env.example`의 `REAL_ORDER_ENABLED=false`, `REAL_ORDER_CANARY_ENABLED=false`.
- `compose.yaml`의 `BROKER_CREDENTIALS_ENABLED=false`; Gemini 키와 staging 값은 비어 있거나 `CHANGE_ME`.
- 따라서 실계좌 canary를 강제로 켜지 않았다. 실 provider/Gemini/Toss 호출을 통과한 것으로 판정하지 않는다.

## E2E 증적

- Provider 수집·stock analysis: `StockAnalysisWorkflowIntegrationTest` 3건 PASS.
- Portfolio analysis: `PortfolioAnalysisWorkflowIntegrationTest` 10건 PASS.
- Forecast: `StockForecastIntegrationTest` 5건 PASS.
- Gemini 경계·degrade 정책: `StockAnalysisGeminiExplainPolicyTest` 3건 PASS.
- Event 수집·영향 분석: `AutomatedMarketEventIngestionIntegrationTest` 1건, `EventIntelligenceIntegrationTest` 6건 PASS.
- 설정 부재 preflight: `RealOrderCanaryServiceTest` 1건 PASS — `PREFLIGHT_ONLY`, `CANARY_DISABLED`, broker 호출 없음.
- mock canary: submit → observe → cancel → reconcile, idempotency·fault·UNKNOWN 경계 포함 `RealOrderCanaryIntegrationTest` 7건 PASS.

집중 RC 테스트 합계: 67건, 실패 0, 오류 0.

## 운영 드릴

| 드릴 | 결과 |
|---|---|
| `./scripts/test-local-stack.sh` | `local stack contract: PASS` |
| `./scripts/smoke-local-stack.sh` | PASS |
| `./scripts/backup-restore-drill.sh` | `backup restore drill: PASS` |
| `./scripts/staging-deploy-rollback-drill.sh` | A → B → A readiness PASS |
| `./scripts/prediction-evaluation-observability-drill.sh` | required Prometheus metrics PASS |
| kill switch API/ledger tests | 3 + 7건 PASS |
| UNKNOWN reconciliation tests | 9건 PASS |

Backup 복구는 canary row 보존, backend 재기동, dump 내 password/key 부재를 확인했다. 백업 암호화 at rest·offsite·PITR은 별도 운영 전제다.

## 전체 검증

- Backend: Java 21 Maven `clean verify`, 632건 PASS, 실패 0.
- Analysis service: `pytest -q`, 19건 PASS.
- Dashboard: `npm test`, 63건 PASS; `npm run build` PASS.
- Docker/Colima 6 GiB에서 Testcontainers 포함 검증. 2 GiB에서는 JVM fork가 exit 137로 종료되어 용량을 올린 뒤 재실행했다.

## 잔여 위험·운영 전제

- 실제 provider/Gemini/Toss 호출, 실계좌 주문·조회·취소·reconciliation은 자격증명과 승인된 allowlist 계좌가 준비된 운영 환경에서 별도 1회 실행해야 한다.
- 실주문 전 `REAL_ORDER_*` 전부 명시, credential vault 활성화, quote freshness·한도·step-up·kill switch·readiness를 다시 확인한다. 기본값은 계속 비활성이다.
- staging deploy/rollback 증적은 mock/local 이미지 drill이다. 실제 registry 이미지, OIDC, secret-file, 운영 endpoint는 아직 검증 대상이다.
- 출시 차단 코드 결함은 발견되지 않았다. 새 기능·리팩터링·한도 상향은 하지 않았다.
