# 잔여 작업 마스터 플랜 — 프로젝트 완성까지

- 작성일: 2026-07-31
- 기준 스펙: `docs/superpowers/specs/2026-07-26-us-equity-trading-platform-design.md` (이하 `SPEC:줄번호`)
- 기준 코드: 브랜치 `feature/prediction-ingestion-operations-hardening` (커밋 `111b1ba`)
- 미커밋 워킹트리 19파일 / +372줄이 이 원장에 반영되어 있지 않다. 착수 전 커밋하거나 stash 할 것:
  - `web-dashboard/lib/api.js`(+31), `app/page.js`(+91), `test/api.test.mjs`(+52), `app/globals.css`(+4) — 진행 중인 프론트 기능
  - `broker/connection/CredentialVaultConfiguration.java`(+18), `prediction/*`(3파일), 테스트 8파일 (`AnalysisPredictionIntegrationTest` +113)
  - `.env.example` / `.env.staging.example` / `compose.yaml` 각 +3 — `PREDICTION_INGESTION_API_KEY_CLEANUP_*` 3종 추가 (H6 의 누락 변수와는 무관)
- 목적: 남은 작업 전부를 근거·완료조건·검증방법과 함께 원장화한다. 이 문서를 순서대로 실행하면 스펙의 MVP 완료 기준(SPEC:1047-1056)을 만족한다.

---

## 0. 실행 프롬프트 (이것만 복사해서 붙여넣으면 됨)

```text
claudedocs/2026-07-31-remaining-work-master-plan.md 를 읽고, 그 안의 작업 원장을
Phase 0 → A → B → C → D → E → F → G → H 순서대로 전부 실행해서 프로젝트를 완성해라.

실행 규약:
1. Phase 0의 결정 항목(Q1~Q4)을 먼저 나에게 물어라. 답을 받기 전까지 Q에 의존하는
   항목(C7, E6, H5)은 착수하지 말고, 의존하지 않는 항목부터 진행해라.
2. 항목 하나 = delta 스펙 1개 + 커밋 1개. 스펙은 docs/superpowers/specs/ 에
   YYYY-MM-DD-<slug>-delta.md 로 쓰고, 기존 delta 파일들의 형식을 따른다.
3. 각 항목은 "완료 조건"을 전부 만족하고 "검증"에 적힌 명령이 통과해야 완료다.
   테스트를 비활성화하거나 건너뛰어서 통과시키지 마라. 실패하면 근본 원인을 고쳐라.
4. 커밋 메시지는 기존 히스토리 형식(`기능 :: 설명`, `테스트 ::`, `문서 ::`, `작업 ::`)을
   따르고 한국어로 쓴다. Co-Authored-By: jaeminjo732 <jaeminjo732@gmail.com> 를 붙인다.
   main 에 직접 커밋하지 말고 항목 단위로 feature 브랜치를 쓴다.
5. Phase 하나가 끝날 때마다 전체 게이트를 돌려라:
   cd trading-backend && ./mvnw clean verify
   cd analysis-service && pytest -q
   cd web-dashboard && npm test && npm run build
   ./scripts/test-local-stack.sh && ./scripts/smoke-local-stack.sh
   그리고 이 문서의 체크박스를 갱신해라.
6. 스펙과 코드가 충돌하면 코드를 바꾸지 말고 먼저 나에게 보고해라.
7. 진행 과정을 길게 설명하지 말고, 완료 항목·발견 사항·실패만 보고해라.
```

---

## 1. 현재 상태 (증거 기반)

| 영역 | 상태 |
|---|---|
| 백엔드 | Java 154파일 / 테스트 65파일 467케이스(`@Test` 465 + `@ParameterizedTest` 2), REST 매핑 40개, 마이그레이션 V1~V24 (`CREATE TABLE` 36개), 스케줄러 4개 |
| 분석 서비스 | Python 소스 **2파일**(`app/__init__.py`, `app/main.py` 183줄) + 테스트 1파일. 라우트 3개(`health`, `ready`, `portfolio-analyses`). 스펙 §8 파이프라인 **미구현** |
| 프론트 | Next.js 라우트 **3개**(`/`, `/auth/login`, `/health`). 스펙 §12의 8개 페이지 트리 미구현, 전부 `/` 한 페이지에 합쳐짐 |
| 계약 | `contracts/analysis/v1/` 에 예시 payload 2개. stock-analysis / event-impact 계약 없음 |
| CI | 워크플로 1개, 5잡 (backend / python / web / npm audit / compose smoke) |
| 관찰성 | 메트릭 11개 등록. 스펙 §15.1 필수 8종 미구현. 대시보드·알람 없음 |

**구현되어 있으나 죽어 있는 것**
- `order_intent_outbox_events`, `order_submission_outbox_events` → 기록만 되고 소비자 없음 (`OrderIntentOutboxEventRepository.java:7` 빈 인터페이스, 읽는 곳은 backlog gauge SQL `observability/WorkflowMetrics.java:34-35` 뿐)
- `order_submission_audit_logs` → 기록만 되고 읽는 코드 없음
- `NotificationOutboxProcessor.java:26` 만 제대로 된 소비자 (`FOR UPDATE SKIP LOCKED` + `ON CONFLICT DO NOTHING`)

**아예 존재하지 않는 것** (grep 0건 확인)
`FxRateSnapshot` · `TradeProposal` · kill switch · `MarketSession` · `marketdata` 패키지 · `Candle` · `CompanyExposure` · step-up 인증 · `TenantAccessGuard` · `inbox_messages` · ArchUnit · SSE/WebSocket · `/api/v1/audit` · RLS 정책 · sellable quantity 조회 · 주문 전송용 broker port 메서드 · LLM/ML 라이브러리 · 외부 시장데이터 공급자

---

## 2. Phase 0 — 결정 게이트

2026-07-31 사용자 답변 확정. Q3 은 미해결 항목이 남아 있다(아래 참조).

### Q1 — 데이터 공급자: **다중 공급자 구조** ✅

역할별로 공급자를 고정하고, 공급자끼리 값을 섞지 않는다.

| 역할 | 공급자 |
|---|---|
| 현재가·호가 등 브로커 데이터 | Toss |
| 공시·XBRL 재무 원본 | SEC EDGAR |
| 거시지표·정책 이벤트 | FRED · BLS · BEA · Federal Reserve |
| 컨센서스·실적 예상·기업 재무 (기본) | FMP |
| 위 항목 선택적 보완 | Finnhub |
| 캔들·시장 데이터 보완 (API key 있을 때만) | Polygon 또는 Twelve Data |

구속 규칙 — C2·C6·C7 설계에 그대로 반영할 것:
1. 데이터마다 **원본 provider · 기준 시각(asOf) · 수집 시각**을 저장한다.
2. **서로 다른 공급자 값을 임의로 평균·합성하지 않는다.** 역할별 기본 공급자를 정하고 보완 공급자는 별도 필드로 병기한다.
3. 공급자 장애·데이터 부재 시 **추정값을 만들지 않는다.** fallback 공급자로 넘기거나 `missingData` 를 반환한다(SPEC:711, SPEC:1045).
4. 각 공급자는 **opt-in** 으로 구성한다. 키가 없으면 해당 analyzer 만 degrade 되고 전체는 살아 있어야 한다.
5. 공급자별 **라이선스 · 호출 제한 · 재배포 가능 범위**를 `docs/ops/data-licenses.md` 에 문서화한다(SPEC:1132).

→ C7 이 플랜 작성 시 가정보다 크게 확대된다. 공급자 어댑터 6종 + provenance 저장 + opt-in 구성이 필요하다.

### Q2 — 운영 형태: **SaaS 아님, 단일 사용자 본인 전용 + 실거래 진행** ✅

- 타인에게 서비스하지 않으므로 SPEC:1061-1066 의 SaaS 자격증명 보관 리스크는 해당 없음.
- **실거래 주문 전송을 진행한다.** E6 는 "flag off 로 차단" 이 아니라 SPEC:1097-1102 의 실거래 준비 경로로 수행한다.
- 사용자 격리: **H4 의 우선순위는 내려가지만 제거하지 않는다.** 스키마·쿼리의 `userId` 스코프는 이미 전반에 깔려 있어 되돌리는 비용이 유지 비용보다 크다. A5 의 `findById` 금지 규칙도 유지한다.

**실거래 결정이 바꾸는 순서** — 아래는 이제 선택이 아니라 **주문 전송보다 먼저** 끝나야 한다. 근거는 안전 논증이 아니라 고장 모드다: 이 항목들이 없으면 중복 주문·유령 주문이 실제 체결로 나가고, 사후 복구 수단이 없다.

| 선행 필수 | 없을 때 실거래에서 벌어지는 일 |
|---|---|
| A1 order outbox relay | 발행 소비자가 없어 상태 전이가 비동기로 전파되지 않음 |
| A2 inbox 멱등 | 소비자 재수신·worker 재실행 시 **broker order 중복 생성** |
| B3 sellable quantity + 직전 재검증 | 승인 시점 잔고로 주문 → 잔고 부족·초과 매도 |
| B4 broker port 주문 전송 계약 | `TossInvestBrokerAdapter` 에 place/cancel/modify 가 아예 없음. 지금은 실거래 전송 수단 자체가 부재 |
| E2 표시값 대조 + step-up | 화면에 보인 수량·최대손실과 다른 주문이 나갈 수 있음 |
| E4 kill switch | 폭주 시 정지 수단 없음 (SPEC:1136 은 제출 worker 가 매번 확인하도록 요구) |
| E5 UNKNOWN 조정 절차 | 전송했는지 모르는 주문을 미접수로 오판 → **중복 재전송** |

추가로 SPEC:1097-1102 대로: 소액 allowlist 계좌로 제한, 제출 직전 재검증, `docs/ops/order-reconciliation-runbook.md` 를 실제 장애 주입으로 검증한 뒤에만 한도를 올린다.

토스증권 약관상 자동 주문 허용 여부는 사용자 책임 영역으로 두고, 코드는 allowlist·kill switch·한도로 방어한다.

### Q3 — LLM: **Gemini API** ✅

`analysis-service` 의 `explain` 모듈(C3)에서 Google Gemini API 를 사용한다. 구독형 클라이언트가 아니라 **서버사이드 API 키**로 호출한다(환경변수 주입, 공급자 opt-in 패턴은 Q1 과 동일).

확정 제약:
- **수치 계산에 LLM 을 쓰지 않는다.** 확률·기대수익·밸류에이션은 전부 결정적 analyzer 가 계산하고, LLM 은 서술 생성만 한다(SPEC:1131).
- 모든 근거에 **원문 citation ID 를 강제**한다. citation 없는 근거는 응답에 넣지 않는다(C3 완료 조건).
- 재현성(MVP 완료 기준 3번, SPEC:1051): **모델 ID·프롬프트 버전·응답을 분석 스냅샷에 기록**한다. LLM 은 비결정적이므로 "같은 입력 → 같은 수치" 는 analyzer 계층에서 보장하고, 서술은 스냅샷에 저장된 것을 재사용한다.
- `explain` 은 **선택 analyzer** 다(SPEC:717). Gemini 장애·키 부재 시 해당 섹션만 degrade 되고 분석 전체는 성공해야 한다. confidence 만 감소.
- timeout·circuit breaker·bounded retry 는 C6 의 규칙(SPEC:1127)을 그대로 적용한다.
- **모델 ID 는 설정에 고정(pin)하고 Google 공식 문서에서 현재 유효한 값을 확인해 넣는다.** 코드에 하드코딩하지 않는다.

### Q4 — 배포 범위: **스테이징까지** ✅

Prometheus/Grafana 는 compose 로컬·스테이징 스택에만. 실제 운영 배포는 범위 밖.
→ H2·H5 는 플랜 기본값 그대로 진행. 백업 암호화와 복구 훈련은 CI 스케줄로 자동화.

---

## 3. 작업 원장

진행 표기: `[ ]` 미착수 · `[~]` 진행 중 · `[x]` 완료

### Phase A — 원장·모듈 무결성 (최우선, SPEC:1068-1073 P1)

MVP 완료 기준 6번("중복 승인·Inbox 재수신·worker 재실행에도 중복 생성되지 않는다", SPEC:1054)이 지금 구조적으로 보장되지 않는다. 다른 모든 Phase가 이 위에 올라간다.

- [x] **A1. order outbox relay 구현** — 완료 `be880ab`, 스펙 `2026-07-31-order-outbox-relay-delta.md`
  - 잔여: relay 가 만든 `notification_outbox_events` 행은 `NOTIFICATION_OUTBOX_ENABLED` 를 켜야 비워진다. 기본값 false(`8e832a0`).
  - 왜: `order_intent_outbox_events` / `order_submission_outbox_events` 가 write-only. DB 커밋과 비동기 발행의 원자성(SPEC:79)이 실제로는 아무것도 발행하지 않음.
  - 현재: 기록 `OrderIntentTransitionService.java:90`, `OrderSubmissionService.java:492,514` / 소비자 0
  - 할 일: `NotificationOutboxProcessor.java` 패턴(`FOR UPDATE SKIP LOCKED` + 별 트랜잭션 + `processed_at`)을 재사용하는 공용 relay 를 만들고 두 테이블에 적용. 재시도 횟수·`failed_at`·dead-letter 컬럼 마이그레이션 추가. 스케줄러 간격은 property 로.
  - 파일: `order/OrderOutboxProcessor.java`(신규), `order/*OutboxEventRepository.java`, 신규 마이그레이션 `V25__`
  - 완료 조건: 두 테이블 모두 소비자가 있고, 재처리해도 부작용 없고(멱등), backlog gauge 가 0으로 수렴하는 통합 테스트 존재
  - 검증: `./mvnw -Dtest='*Outbox*' test`

- [x] **A2. inbox 모듈 구현** — 완료 `6c97281`, 스펙 `2026-07-31-inbox-idempotent-consumers-delta.md`
  - 잔여 1: `NotificationOutboxProcessor` 는 inbox 원장은 쓰지만 소비자별 `REQUIRES_NEW` 분리 디스패치는 적용하지 않았다(소비자 1개 + 커넥션 풀 제약). **두 번째 알림 소비자를 추가하는 순간 A1 의 결함이 재현된다.** 막는 장치가 없으므로 추가 시 분리 디스패치로 먼저 옮길 것.
  - 잔여 2: 다중 인스턴스에서 같은 행을 동시에 처리하면 부작용은 inbox UNIQUE 로 1회지만 `attempts` 가 인스턴스별로 증가해 dead-letter 상한에 빨리 도달할 수 있다. 현재 테스트 범위(단일 인스턴스) 밖.
  - 왜: SPEC:80, SPEC:464, SPEC:1126 이 요구. MVP 완료 기준 6번의 "Inbox 재수신" 을 만족할 수단이 없음. 지금은 `ON CONFLICT (outbox_event_id)` 로 대체 중이라 **소비자가 2개가 되는 순간 깨짐**.
  - 할 일: `inbox_messages(consumer_name, event_id, processed_at, ...)` + `UNIQUE(consumer_name, event_id)` 마이그레이션. `InboxMessage`, `InboxConsumer` 추상화. A1 relay 와 `NotificationOutboxProcessor` 를 이 위로 이관. 도메인 변경과 inbox insert 를 **같은 트랜잭션**으로 커밋(SPEC:529).
  - 완료 조건: 같은 outbox 이벤트를 서로 다른 consumer 2개가 각각 1회씩 처리하고, 동일 consumer 재수신 시 무시하는 테스트 통과
  - 검증: `./mvnw -Dtest='*Inbox*' test`

- [ ] **A3. 주문 감사 조회 API**
  - 왜: SPEC:974 `GET /api/v1/audit/orders/{id}` 미구현. 감사 기록이 MVP 포함 범위(SPEC:1033).
  - 할 일: user-scoped 조회로 `order_intent_audit_logs` + `order_submission_audit_logs` 병합 타임라인 반환. 민감정보(credential, body hash 원문) 마스킹.
  - 파일: `order/OrderAuditController.java`, `order/OrderAuditQueryService.java`(신규)
  - 완료 조건: 타 사용자 주문 ID 로 요청 시 404, 본인 주문은 시간순 이벤트 배열 반환. 통합 테스트 존재
  - 검증: `./mvnw -Dtest='OrderAudit*' test`

- [ ] **A4. 감사 기록을 identity·broker·account·risk 로 확대**
  - 왜: SPEC:67-69,78,92 는 보안·브로커·계좌 행위도 감사 대상. 현재 감사 로그는 `order` 패키지에만 존재.
  - 할 일: 공용 `AuditService` 도입(모듈 의존 없음, SPEC:78). 대상 행위: 로그인/로그아웃, 자격증명 등록·교체·삭제, 연결 검증, 계좌 동기화, 리스크 정책 변경(기존 `risk_policy_history` 와 통합), kill switch. append-only 트리거는 V2/V3 패턴 재사용.
  - 완료 조건: 위 행위 각각에 대해 감사 레코드가 남는 통합 테스트. UPDATE/DELETE 가 트리거로 차단됨
  - 검증: `./mvnw -Dtest='Audit*' test`

- [x] **A5. ArchUnit 아키텍처 테스트** — 완료 `3bd3e3f`, 스펙 `2026-07-31-module-boundary-archunit-delta.md`
  - 5개 규칙 전부 도입. 규칙 2(`analysis`→`order`/`broker`)와 규칙 4(entity 외부 노출)는 **현재 위반 0**.
  - **동결 부채 4건** — 규칙은 신규 위반 증가를 막지만 기존 위반은 명시 목록으로 얼려 두었다. 목록은 `containsExactlyInAnyOrder` 로 정확히 일치해야 하므로 늘어도 실패하고, 해소 후 목록에서 안 지워도 실패한다.

  | 동결 위반 | 건수 | 해소 항목 |
  |---|---|---|
  | `broker`→`account` (`BrokerConnectionController`, `BrokerConnectionErrorHandler`) | 2 | 연결·온보딩 모듈 분리 (신규 항목 필요) |
  | `prediction`→`broker` (`AnalysisPredictionService`, `AnalysisPredictionController`) | 2 | **B1** marketdata 모듈 신설 |
  | `intelligence`→`analysis` (`EventIntelligenceService`, `EventIntelligenceController`) | 3 | **D4** 이벤트→재분석 Outbox 연결 |
  | 단일 인자 `findById` 호출 (`OrderIntentTransitionService`, `PaperTradingBroker`, `OrderSubmissionService`, 총 10회) | 3 클래스 | order 데이터 접근 계층의 userId 스코프화 (신규 항목 필요) |

  - **알려진 구멍**: `@Configuration` 클래스는 규칙 1·2·3 에서 제외된다(조립 계층). 도메인 클래스에 `@Configuration` 을 붙이면 경계를 우회할 수 있다.
  - **미규율 패키지**: `dashboard`, `notification`, `observability`, `refresh`, `migrate` 는 SPEC 모듈 표 밖이라 규칙 1이 강제하지 않는다.
  - 왜: SPEC:57 이 "Spring Modulith 대신 **패키지 경계와 아키텍처 테스트**로 모듈성을 지킨다" 고 명시. 그 아키텍처 테스트가 0개라서 독립 모듈을 안 만든 결정의 전제가 비어 있음.
  - 할 일: `com.tngtech.archunit` 테스트 의존 추가. 규칙: (1) SPEC:65-80 의존 표 강제, (2) `analysis` → `order`/`broker` 금지(SPEC:95), (3) `event` → `analysis` 애플리케이션 서비스 직접 호출 금지(SPEC:96), (4) JPA entity 의 모듈 외부 노출 금지(SPEC:101), (5) repository 에 `userId` 없는 `findById(단일인자)` 금지(SPEC:100).
  - 파일: `trading-backend/pom.xml`, `src/test/java/com/jmj/trade/architecture/ModuleBoundaryTest.java`(신규)
  - 완료 조건: 5개 규칙 전부 테스트로 존재하고 현재 코드가 통과(위반이 있으면 코드를 고쳐서 통과시킬 것, 규칙을 완화하지 말 것)
  - 검증: `./mvnw -Dtest='ModuleBoundaryTest' test`

- [ ] **A6. OrderIntent 만료 전이 구현**
  - 왜: `EXPIRED` 가 상태 전이표(`OrderIntent.java:215-222`)에 있으나 **main 코드에서 이 전이를 일으키는 곳이 없음**. 제안·승인의 만료 관리(SPEC:74)가 동작하지 않음.
  - 할 일: 만료 스윕 스케줄러 + `OrderIntentTransitionService` 에 `expire()` 추가. `requireReconciliation`/`requireManualReview` 도 전이 서비스로 통일(현재 `OrderSubmissionService` 가 우회 경로).
  - 완료 조건: TTL 초과 PROPOSED/APPROVED/REVALIDATING intent 가 EXPIRED 로 전이되고 감사·outbox 가 남는 테스트
  - 검증: `./mvnw -Dtest='OrderIntent*' test`

### Phase B — 시장데이터·계좌 가용성 (SPEC:1075-1081 P2)

- [ ] **B1. `marketdata` 모듈 신설**
  - 왜: SPEC:71 이 요구하는 모듈이 통째로 없음. `Quote` 는 `broker` 패키지에만 존재하고 `Candle`·최신성 판정·시장 일정이 없음.
  - 할 일: `marketdata` 패키지에 `Quote`, `Candle`, `MarketDataService`, `QuoteFreshnessPolicy`(source timestamp TTL, SPEC:1122), `MarketSession`(pre/regular/after 구분, SPEC:1135) 구현. 미지원 세션 주문 차단.
  - 완료 조건: TTL 초과 시세로 주문 시 fail-closed 되는 테스트, 세션별 허용/차단 테스트
  - 검증: `./mvnw -Dtest='MarketData*,Quote*,MarketSession*' test`

- [ ] **B2. `Money` + `FxRateSnapshot`**
  - 왜: SPEC:1070, SPEC:1134. 현재 `MoneyByCurrency` 만 있고 환율 스냅샷(source/asOf 저장)이 없어 환율손익을 근거 있게 계산할 수 없음.
  - 할 일: 통화 포함 `Money` 값 타입, `fx_rate_snapshots` 테이블(rate, source, as_of, purpose), 목적별 적용 규칙. **서로 다른 통화의 무단 합산을 컴파일/런타임에서 차단**.
  - 완료 조건: KRW+USD 합산 시도가 실패하는 테스트, 환산 결과에 항상 사용된 스냅샷 ID 가 따라붙는 테스트
  - 검증: `./mvnw -Dtest='Money*,Fx*' test`

- [x] **B3. sellable quantity·계좌 재조회를 주문 직전에** — 완료 `23a4ed3`, 스펙 `2026-07-31-pre-submit-revalidation-delta.md`
  - `PreTradeRiskEngine` 의 `FINAL` 단계에 `List<PreSubmitRevalidationCheck>` 를 순서대로 실행하는 **확장 가능한 관문**을 만들었다. 위치는 `paperTradingBroker.submit()` 직전. 미구현 항목은 체크 구현체 하나를 배선 목록에 추가하면 붙는다 — 구조 재설계 불필요.
  - 구현된 관문 3종: 계좌 소유권, sellable quantity, 동일 종목 OPEN 주문. SPEC:875-889 의 나머지는 담당 항목에서 이 자리에 추가한다 (2=E1, 4=B1, 10=E4, 11=F1, 12=C6).
  - `SellableQuantitySnapshot{KNOWN, UNKNOWN}` + `position_snapshots.sellable_quantity` **nullable**(V27). `NULL`=브로커 미제공(UNKNOWN), `0`=확정된 매도 불가. **UNKNOWN 은 차단 사유다**(SPEC:1078) — 확인할 수 없으면 보내지 않는다.
  - 차단 시 브로커 미호출을 `attemptRepository.count()==0` 과 `NoBrokerCallsAdapter`(모든 읽기에서 AssertionError) 로 이중 증명.
  - `REVALIDATING` 전이 호출자는 원래 있었다(`PreTradeRiskEngine.submitPaper`). 이 delta 는 fresh-read 관문을 추가한 것.
  - **잔여**: Toss 는 sellable API 미노출이라 UNKNOWN 반환 → 실거래 SELL 은 현재 항상 차단(의도된 fail-closed). E6 에서 실제 조회 구현 필요.

  **프로세스 사고 1건** — 이 커밋은 `clean verify` **EXIT=1 상태로 커밋**됐다. 담당 에이전트가 "기존 실패라 무관" 이라고 보고했으나, 동일 머신에서 apples-to-apples 로 대조한 결과 B3 이전(`f33619a`)은 500 tests 0 failures 로 **초록**이었다. 주장은 재현되지 않았다. 원인은 `PortfolioAnalysisWorkflowIntegrationTest` 의 1초 마진 경계 테스트였고 B3 가 테스트 10개를 추가하며 그 마진을 소진했다. `95dbca7` 로 별도 수정했다. 앞으로 검증 결과 보고는 커밋 전에 독립 재현으로 확인한다.
  - 왜: SPEC:1077, SPEC:1123. `sellable` grep 0건. 승인 후 holdings/buying-power 재조회가 없음.
  - 할 일: `BrokerAdapter` 에 `getSellableQuantity` 추가, 제출 worker 의 직전 재검증(SPEC:873-891)에 편입. 미제공 잔액은 `UNKNOWN` 유지(SPEC:1078).
  - 완료 조건: 재조회 결과가 승인 시점보다 부족하면 제출이 차단되고 `BLOCKED` 로 전이되는 테스트
  - 검증: `./mvnw -Dtest='PreTradeRisk*,OrderSubmission*' test`

- [x] **B4. broker port 에 주문 전송 계약 추가** — 완료 `396b195`, 스펙 `2026-07-31-broker-order-port-delta.md`
  - 읽기 포트 `BrokerAdapter` 는 그대로 두고 쓰기 포트 `BrokerOrderPort` 를 분리 신설했다. 기존 `BrokerContractTest` 가 `BrokerAdapter` 를 읽기 전용으로 의도적으로 고정하고 있었고, 합치면 read 소비자 4곳과 테스트 더블에서 빈 주입 모호성이 생긴다.
  - `BrokerOrderDispatchStatus { ACCEPTED, REJECTED, UNKNOWN, UNSUPPORTED }` — **미확정을 실패와 타입으로 구분**한다. 이게 없으면 E5 조정 절차가 성립하지 않는다(SPEC:1055).
  - 공유 정책을 계약 테스트로 강제: 수량 정정 거부(미국주식 가격만, SPEC:558), OPEN/CLOSED 분류 동일, UNKNOWN≠REJECTED, 조용한 성공 없음.
  - `TossInvestBrokerAdapter` 는 포트를 구현하되 주문 전송을 `UNSUPPORTED` 로 반환하고 **HTTP 호출을 하지 않음**을 테스트로 고정. 실구현은 E6.

  **검토 결과 — 페이퍼 브로커의 자체 book 은 결함이 아니다.** `broker_orders` 는 브로커 상태의 projection 이고(SPEC:527) 실제 장부는 브로커 서버에 있다. 페이퍼 브로커는 그 외부 브로커를 시뮬레이션하는 자리이므로 자체 book 을 갖는 것이 올바른 계층이다. `placeOrder` 가 `order_intents` 를 만들면 브로커가 거래 의도를 발명하는 셈이 되어 의존 방향이 뒤집힌다(`broker_orders.order_intent_id` 는 `NOT NULL`, V1:93).

  - **잔여 1**: `BrokerOrderPort` 의 main 소비자가 아직 0개다. `OrderSubmissionService` 가 dispatch 단계에서 호출하도록 배선하는 것은 E6 범위다.
  - **잔여 2**: 페이퍼 시뮬레이터 book 이 in-memory 라 재기동 시 사라진다. 실제 브로커 장부는 재기동을 견딘다. **E5 의 UNKNOWN 조정을 fault injection 으로 검증할 때 이 휘발성이 문제가 될 수 있다** — E5 착수 시 지속화 필요 여부를 먼저 판단할 것.
  - 왜: `BrokerAdapter.java:5` 는 읽기 5개 메서드뿐. 주문 제출/취소/정정/주문조회가 포트에 없어서 `PaperTradingBroker.java:19` 가 포트를 구현하지 않는 별도 클래스로 떠 있음. 실거래 어댑터를 끼울 자리가 없음.
  - 할 일: `place/cancel/modify/getOrder/getOrders(OPEN·CLOSED)` 를 포트에 추가. `PaperTradingBroker` 를 포트 구현으로 전환. `TossInvestBrokerAdapter` 는 Q2 미승인 시 `UnsupportedOperation` + feature flag 로 차단.
  - 완료 조건: Paper 와 Toss 가 같은 포트를 구현하고 `BrokerContractTest` 가 양쪽에 동일 계약을 검증
  - 검증: `./mvnw -Dtest='BrokerContractTest,Paper*' test`

### Phase C — 종목 분석 파이프라인 (SPEC:633-720, SPEC:1090-1095 P4)

현재 `analysis-service` 는 포트폴리오 비중 계산기 1개다. 스펙 §8 의 파이프라인은 0% 구현.

- [ ] **C1. FastAPI 구조 분해 + `contracts` 모듈**
  - 할 일: `app/main.py` 183줄 단일 파일을 SPEC:105-116 의 모듈 구조로 분해 — `contracts/`(Pydantic 버전별 요청·응답), `routers/`, `features/`, `services/`. 기존 `portfolio-analyses` 라우트와 상관관계 미들웨어는 **동작 변경 없이** 이관.
  - 완료 조건: 기존 7개 테스트 전부 그대로 통과. `contracts/analysis/v1` 골든 fixture 일치 유지
  - 검증: `cd analysis-service && pytest -q`

- [ ] **C2. `POST /internal/v1/stock-analyses` 구현**
  - 요청(SPEC:665-679): `requestId`, `schemaVersion`, `symbol`, `asOf`, `marketData`, `financialData`, `expectationsData`, `marketRegime`, `eventImpacts`
  - 응답(SPEC:681-698): `modelVersion`, `status`, `baseAnalysis`, `forecast`, `valuation`, `suggestedTradePlan`, `confidence`, `bullCase`, `counterCase`, `invalidationConditions`, `missingData`
  - 할 일: `fundamental`, `valuation`, `technical`, `regime`, `expectations` analyzer 구현. 데이터 없으면 임의 추정 금지 → `null + missingData`(SPEC:711, SPEC:1045).
  - 완료 조건: 골든 fixture(`contracts/analysis/v1/stock-analysis-{request,response}.json` 신규) 대조 테스트, analyzer 별 단위 테스트
  - 검증: `cd analysis-service && pytest -q`

- [ ] **C3. `forecast` + `explain` analyzer**
  - 할 일: `forecast` = 1일 상승 확률, 5·20일 기대수익, 예상 최대 손실(SPEC:114). `explain` = 근거·반대논리·부족 데이터·무효화 조건(SPEC:115). **`forecast` 는 결정적 analyzer 로 계산하고 Gemini 를 쓰지 않는다.** `explain` 만 Gemini API 로 서술을 생성하며 원문 citation ID 필수·수치 계산 금지(SPEC:1131). 모델 ID·프롬프트 버전을 스냅샷에 기록. Gemini 장애 시 `explain` 만 degrade(SPEC:717).
  - 완료 조건: 확률이 [0,1] 범위 밖이면 실패, citation 없는 근거가 응답에 못 들어가는 테스트
  - 검증: `cd analysis-service && pytest -q`

- [ ] **C4. confidence 계산 + 실패 상태 모델**
  - 할 일: SPEC:702-709 공식 그대로 `dataCompleteness × freshnessFactor × modelCalibration × sourceReliability × regimeCoverage`. `Status` enum 에 `FAILED`, `INSUFFICIENT_DATA` 추가(SPEC:652). 필수 analyzer 실패 → 전체 실패, 선택 analyzer 실패 → confidence 감소(SPEC:717).
  - 완료 조건: 5개 인자 각각을 0 으로 만들었을 때 confidence 가 0 이 되는 테스트, 실패 상태에서 `suggestedTradePlan` 이 비는 테스트
  - 검증: `cd analysis-service && pytest -q`

- [ ] **C5. `POST /internal/v1/event-impacts` 구현**
  - 할 일: SPEC:659. 이벤트 구조화, 노출도 기반 영향, 반대 시나리오(SPEC:113). 분류는 D2 와 계약 공유.
  - 완료 조건: 골든 fixture 대조 테스트
  - 검증: `cd analysis-service && pytest -q`

- [ ] **C6. Spring 쪽 분석 오케스트레이션**
  - 왜: SPEC:635-653 의 흐름(`AnalysisInputSnapshot` → outbox → FastAPI → 검증 → 스냅샷)이 없음. 현재는 `PortfolioAnalysisWorkflowService.java:314` 가 직접 호출.
  - 할 일: `analysis` 모듈에 `AnalysisInputSnapshot`(입력 hash, `available_at` 기준 — `reported_at` 금지, SPEC:649), `AnalysisOrchestrator`, `StockAnalysisSnapshot`. 결과 검증(확률 범위·필수 근거·모델 버전·입력 hash, SPEC:651). `requestId UNIQUE` 멱등(SPEC:719). timeout·circuit breaker·bounded retry(SPEC:1127) 및 실패 시 신규 proposal 중지. 실패해도 이전 성공 결과는 stale 배지로 조회만 허용(SPEC:653).
  - 완료 조건: 같은 입력·같은 FX 기준으로 분석을 재현하는 테스트(MVP 완료 기준 3번, SPEC:1051). FastAPI 다운 시 proposal 이 생성되지 않는 테스트
  - 검증: `./mvnw -Dtest='Analysis*' test`

- [ ] **C7. 외부 데이터 공급자 어댑터** *(Q1 의존)*
  - 할 일: Q1 답에 따라 재무·컨센서스·캔들·거시 공급자 클라이언트 구현. 공급자별 저장·재배포 권한을 `docs/ops/data-licenses.md` 에 기록(SPEC:1132). 미확보 데이터는 기능 비활성.
  - 완료 조건: 공급자 장애 시 해당 analyzer 만 degrade 되고 전체가 죽지 않는 테스트
  - 검증: `./mvnw -Dtest='*MarketData*,*Provider*' test`

- [ ] **C8. 종목 조회 API 3종**
  - 할 일: SPEC:941-944 — `GET /api/v1/stocks/{symbol}`, `GET .../analysis`, `GET .../analyses`, `POST .../analyses`(사용자 요청 재분석).
  - 완료 조건: 타 사용자 데이터 미노출 통합 테스트, 재분석 요청이 중복 생성을 만들지 않는 테스트
  - 검증: `./mvnw -Dtest='Stock*' test`

### Phase D — 이벤트 파이프라인 (SPEC:723-776)

현재는 수동 입력 UI + 리뷰 워크플로만 있다(`intelligence` 패키지). 자동 수집·노출도·시장 반응은 없다.

- [ ] **D1. 공식 소스 수집기 + 원문 hash 중복 제거**
  - 할 일: SPEC:725-735 우선순위대로 source tier 부여(1 정부/규제 ~ 7 SNS). SEC EDGAR 우선 구현(Q1 기본값). `SourceDocumentDetected` → 원문 hash 중복 제거(SPEC:741). SNS 단독은 `UNVERIFIED` 저장하되 주문 후보 생성 금지(SPEC:735).
  - 완료 조건: 같은 문서 재수집이 이벤트를 중복 생성하지 않는 테스트, tier 7 단독 이벤트가 proposal 을 만들지 못하는 테스트
  - 검증: `./mvnw -Dtest='Event*' test`

- [ ] **D2. `company_exposures` / `event_company_impacts` + 영향 분류**
  - 할 일: SPEC:774-776 대로 PostgreSQL 테이블(관계 타입 포함, 그래프 DB 금지). 분류 `DIRECT`/`INDIRECT`/`THEME_ONLY`/`NEGATIVE`(SPEC:752-759). `THEME_ONLY` 는 상승 magnitude 상한을 낮추고 예상 최대 손실·mean-reversion 위험을 높임.
  - 완료 조건: 분류별 magnitude 상한이 실제로 적용되는 테스트
  - 검증: `./mvnw -Dtest='EventImpact*,CompanyExposure*' test`

- [ ] **D3. 시장 반응 분류 + 자동 REVIEW_REQUIRED**
  - 할 일: SPEC:761-772 필수 입력(직전 가격, 프리마켓 gap, 1·5·15분 수익률, 거래량 비율, 섹터 ETF 동조, bid-ask spread·유동성). 가격 방향이 해석과 충돌하거나 과도한 gap·spread → `REVIEW_REQUIRED` 전환 + 주문 후보 차단.
  - 완료 조건: 충돌 시나리오에서 자동 전환되고 proposal 이 차단되는 테스트
  - 검증: `./mvnw -Dtest='MarketReaction*,EventReview*' test`

- [ ] **D4. 이벤트 → 재분석 Outbox 연결**
  - 왜: SPEC:96 이 `event` 의 `analysis` 직접 호출을 금지하고 `AnalysisRecalculationRequested` outbox 경유를 요구. A5 아키텍처 테스트로 강제됨.
  - 할 일: `AnalysisRecalculationRequested`, `PortfolioImpactRecalculationRequested` 를 outbox 로 발행하고 `analysis` 가 불변 `EventImpactInput` 만 소비(SPEC:96). Inbox 멱등으로 재분석 중복 차단(SPEC:1093).
  - 완료 조건: MVP 완료 기준 4번 — "이벤트가 기존 분석을 새 스냅샷으로 재계산한다"(SPEC:1052) 통합 테스트. 중복 이벤트가 재분석을 2회 돌리지 않음
  - 검증: `./mvnw -Dtest='EventRecalculation*' test`

### Phase E — 주문 후보·주문 API (SPEC:1083-1102 P3/P5)

- [ ] **E1. `proposal` 모듈 + TradeProposal API**
  - 왜: `TradeProposal` grep 0건. MVP 포함 범위(SPEC:1031)이고 SPEC:952-955 API 가 미구현. 현재는 `paper-orders` 로 대체되어 있음.
  - 할 일: `proposal` 패키지에 `TradeProposal`, `ProposalPolicy`, `TradeProposalService`(분석→사용자별 후보 변환, 만료 관리, SPEC:74). API: `GET /api/v1/trade-proposals`, `GET /{id}`, `POST /{id}/approve`, `POST /{id}/reject`.
  - 완료 조건: 분석 스냅샷 → 후보 → 승인 → OrderIntent(APPROVED) 경로 통합 테스트. 분석이 `FAILED`/`INSUFFICIENT_DATA` 면 후보가 생기지 않음
  - 검증: `./mvnw -Dtest='TradeProposal*' test`

- [x] **E2. 승인 계약 강화 (step-up + 표시값 대조)** — 완료 `a8f6dd4`, 스펙 `2026-08-02-approval-contract-hardening-delta.md`
  - **표시값 대조**: `displayedQuantity`·`displayedMaxLoss` 필수. 불일치 시 **409 + 서버 계산값 동봉**(새 확인 화면용), intent 상태 불변·토큰 미소비. 누락 시 422, 기본값 대체 없음. 허용 오차 밴드 없음 — 통화 최소단위(USD 2자리/KRW 0자리) 반올림 후 정확 동등.
  - **step-up**: OIDC 표준 `auth_time` 기반. `authTime == null` → 401 **fail-closed**, 미래 시각(+60s 초과) → 401(시계 편차 위조 방어), 신선도 창(기본 PT5M) 초과 → 401. 토큰은 32바이트 랜덤 → **SHA-256 해시로만 저장**(SPEC:1151), TTL 2분, 단일 사용, `(user, orderIntent)` 바인딩. 소비는 원자적 CAS(`consumed_at IS NULL AND expires_at > now AND user_id=? AND order_intent_id=?`) → 만료·재사용·타 사용자·타 주문 전부 0행 → 401. V28.
  - **이중 승인 → 단일 intent**: 기존 `Idempotency-Key` 경로 재사용. 서로 다른 키의 동시 승인은 연결 행 `FOR UPDATE` 직렬화 + 트랜잭션 내 상태 재확인으로 승자만 성공.
  - **철회**: `PROPOSED` 에서만 compare-and-set 성공, 이후 409. grace period 없음(SPEC:966). 공개 `submit` API 없음(SPEC:964) 확인.
  - `proposalVersion` 은 `ApproveCommand`/`ApproveRequest` 에 필드만 예약. E1 에서 연결.
  - **잔여 1**: 로그인 흐름에 OIDC `max_age` 강제 배선이 없다. 대부분 IdP 가 `auth_time` 을 기본 포함하나 보장은 아니며, 없으면 승인이 fail-closed 로 막힌다. **G7(설정/보안 페이지)에서 배선할 것.**
  - **잔여 2**: 최대손실을 명목가(`price × quantity`)로 근사한다. SELL·스톱 반영은 E1 에서 정교화.
  - 할 일: SPEC:966 — 승인 요청에 `proposalVersion`, `displayedQuantity`, `displayedMaxLoss`, 짧은 수명 `stepUpToken` 필수. 서버 계산값과 다르면 **409** + 새 확인 화면 요구. 별도 grace period 없음. `SUBMITTING` 전이 전에는 compare-and-set 철회 허용(성공 보장 없음). 공개 `submit` API 는 두지 않음(SPEC:964, SPEC:1171).
  - 완료 조건: 표시값 불일치 → 409, 만료 토큰 → 401, 이중 승인 → 단일 intent 인 테스트(MVP 완료 기준 6번)
  - 검증: `./mvnw -Dtest='*Approval*,TradeProposal*' test`

- [ ] **E3. 주문 조회·취소·정정 API**
  - 할 일: SPEC:956-959 — `GET /api/v1/orders`, `GET /{id}`(내부 주문 + 브로커 상태), `POST /{id}/cancel`, `POST /{id}/modify`. 토스 미국주식 **수량 정정 불가** 정책 반영.
  - 완료 조건: 수량 정정 시도가 정책 위반으로 거부되는 테스트, `UNKNOWN` attempt 상태가 목록에 명시적으로 드러나는 테스트
  - 검증: `./mvnw -Dtest='Order*Controller*' test`

- [x] **E4. kill switch 원장화** — 완료 `14a617b`, 스펙 `2026-08-02-kill-switch-delta.md`
  - 범위 3종(`GLOBAL`/`USER`/`ACCOUNT`)의 **최신 버전 행만** 골라 `bool_or(engaged)` — 하나라도 켜지면 차단. 좁은 범위의 해제가 넓은 범위를 무효화하지 못한다.
  - B3 의 `PreSubmitRevalidationCheck` 목록에 체크 하나 추가로 붙였다. **구조 재설계 없음** — B3 의 seam 설계가 의도대로 작동했다.
  - **캐시 없음.** 매 FINAL 평가마다 라이브 조회 → 정지 지연은 사실상 즉시.
  - 조회 실패 → `KILL_SWITCH_STATE_UNAVAILABLE` 로 **차단(fail-closed)**.
  - **조작 비대칭**: engage 는 step-up 불필요(비상 정지는 마찰이 없어야 함), disengage 는 step-up 필수(SPEC:1159).
  - **이미 제출된 주문 무영향** — 신규 전송만 막고 브로커 취소 로직이 없다. 테스트로 증명(기존 COMPLETED 주문 불변, attempt 수 불변).
  - 원장은 추가 전용, `trg_reject_kill_switch_ledger_change` 가 UPDATE/DELETE 차단. V29.
  - **step-up 가산 일반화**: `subject_kind`/`subject_ref` 추가, `order_intent_id` nullable + CHECK 로 정확히 하나의 바인딩 강제. 소비 시 `subject_kind`·`subject_ref` 를 WHERE 에 포함해 **교차 사용 차단**(주문 승인 토큰으로 kill switch 해제 불가, 역방향도 불가). E2 경로 SQL 무변경, 기존 승인 테스트 36건 무수정 통과.
  - **잔여 1**: 버전 증가가 `SELECT MAX+1` + unique 라 동시 조작 시 한쪽이 unique 위반으로 실패하며 예외가 그대로 전파된다. 단일 사용자 운영에선 무해.
  - **잔여 2**: kill switch 용 `/step-up` 발급의 OIDC 신선도 거절 경로에 전용 테스트가 없다(테스트는 토큰 DB 직접 삽입). 다만 `issueForSubject` 가 E2 와 **같은 `requireFreshReauthentication`** 을 호출하므로 fail-closed 는 성립한다 — 커버리지 공백이지 로직 공백이 아니다.
  - 왜: SPEC:962, SPEC:1136 요구. 코드·UI 어디에도 없음.
  - 할 일: 전역·사용자·계좌 단위 kill switch 를 DB 원장으로. `POST /api/v1/trading/kill-switch`. **제출 worker 가 매번 확인**(SPEC:1136). 변경은 감사 기록(A4)과 재인증(SPEC:1159) 필요.
  - 완료 조건: kill switch on 상태에서 신규 제출이 전부 차단되고, 이미 제출된 주문은 영향받지 않는 테스트
  - 검증: `./mvnw -Dtest='KillSwitch*' test`

- [x] **E5. UNKNOWN 조정·MANUAL_REVIEW 해제 절차** — 완료 `8dea710`, 스펙 `2026-08-02-unknown-attempt-reconciliation-delta.md`, 런북 `docs/ops/order-reconciliation-runbook.md`
  - **MVP 완료 기준 7번 충족.** `sealed interface ReconciliationGroupOutcome permits Matched, Absent, Unavailable` 로 **"찾지 못함"(Absent)과 "찾을 수 없었음"(Unavailable)을 타입으로 구분**한다. 조회 예외를 빈 결과로 삼키지 않는다.
  - 재시도 관문(`OrderSubmissionService:234-236`): `openOrdersComplete && closedOrdersComplete && allPagesRead`. CLOSED 를 확정 조회하지 않으면 `RETRY_SAME_KEY_ALLOWED` 에 **도달 불가**. B4 의 `getOrders(OPEN|CLOSED)` 가 이걸 가능하게 했다.
  - `allPagesRead` 는 스펙에 없던 추가 조건이다 — 페이지네이션 미완독도 "없음"의 근거가 못 된다는 같은 종류의 오판을 막는다.
  - 계좌 잠금은 **E4 의 `ACCOUNT` 범위 kill switch 재사용**. 새 메커니즘 없음. 판정·전이·잠금이 한 트랜잭션.
  - **자동 해제 경로 없음** — reconciler·controller 에 disengage 가 없고 스케줄러도 없다. 해제는 E4 의 `disengage`(step-up 소비 필수)로만.
  - 알림은 기존 notification outbox 재사용. 감사에 `open_query_status`/`closed_query_status` 기록. V30.
  - **장애 주입 검증**: `closedOnlyMatchIsFoundNotResent_reproducesMvpCriterionSeven` — CLOSED 에만 있는 체결 주문을 `BROKER_ORDER_FOUND` 로 판정(재전송 없음, 브로커 주문 정확히 1개). runbook §2~§4 절차를 테스트가 그대로 실행한다.
  - **잔여 1**: 프로브가 `BrokerOrderView.idempotencyKey == clientOrderId` 로 우리 주문을 식별한다. **E6 에서 토스가 client-order-id 를 echo 하는 필드와 이 매핑이 일치하는지 반드시 확인할 것.**
  - **잔여 2**: attempt 의 `brokerAccountId`(UUID)와 운영자가 넘긴 `BrokerAccountRef`(브로커 네이티브 ID)를 교차검증하지 않는다(영속 매핑 부재). 운영자 입력을 신뢰한다.
  - **잔여 3**: 타입 강제는 evidence 경계에서 구조적이나 `record` 생성자가 public 이라 도메인이 boolean 을 신뢰하는 계층은 규약이다. 단위 테스트로 고정해 뒀다.
  - 할 일: SPEC:1099-1100, SPEC:1121 — 수동 조정 API + 운영 알림 + 계좌별 신규 주문 잠금. `MANUAL_REVIEW_REQUIRED` 해제는 운영자 명시 행위로만. `docs/ops/order-reconciliation-runbook.md` 작성.
  - 완료 조건: MVP 완료 기준 7번 — "UNKNOWN attempt 는 CLOSED 미검색을 미접수로 오판하지 않고 조정된다"(SPEC:1055) 를 fault injection 으로 검증
  - 검증: `./mvnw -Dtest='Reconciliation*' test`

- [ ] **E6. 실거래 단계적 활성화** *(Q2 확정: 진행)*
  - 선행 필수: **A1, A2, B3, B4, E2, E4, E5 가 전부 끝난 뒤에만 착수한다.** Phase 0 Q2 의 고장 모드 표 참조.
  - 할 일: SPEC:1097-1102 — 소액 allowlist 계좌로 제한(계좌 화이트리스트를 DB 원장으로), 제출 직전 재검증, 1회 주문 금액·일일 누적 한도, `docs/ops/order-reconciliation-runbook.md` 작성 후 **장애 주입으로 실제 검증**. 한도 상향은 runbook 검증 통과 이후에만.
  - `TossInvestBrokerAdapter` 가 B4 의 포트를 실제로 구현해야 한다(현재 주문 전송 메서드 자체가 없음).
  - 완료 조건: allowlist 밖 계좌로 주문 시 차단되는 테스트, 한도 초과 시 차단되는 테스트, kill switch on 시 제출이 전부 멈추는 테스트(E4), 전송 결과 UNKNOWN 을 fault injection 으로 만들었을 때 중복 재전송이 일어나지 않는 테스트(E5)
  - 검증: `./mvnw -Dtest='Release*,KillSwitch*,Reconciliation*' test`

### Phase F — 성과·스트림

- [ ] **F1. 성과 API + 전략 자동 중단**
  - 할 일: SPEC:972-973 — `GET /api/v1/performance/predictions`, `GET /api/v1/performance/strategies`(전략별 성과·중단 상태). SPEC:1128 — walk-forward, calibration, 국면별 성과, 손실 한도 초과 시 전략 자동 중단 → Paper 전환. 기존 `prediction` 패키지의 평가 결과를 재사용.
  - 완료 조건: drawdown 한도 초과 전략이 자동 중단되고 신규 proposal 을 못 만드는 테스트
  - 검증: `./mvnw -Dtest='Performance*,Strategy*' test`

- [ ] **F2. SSE 스트림**
  - 할 일: SPEC:975-977 — `GET /api/v1/stream` 으로 portfolio·event·proposal·order 상태 힌트 전송. 이벤트는 **갱신 힌트일 뿐**이며 클라이언트는 REST 로 authoritative state 를 다시 읽는다. 사용자별 격리 필수.
  - 완료 조건: 타 사용자 이벤트가 새지 않는 테스트, 연결 끊김 후 재연결 테스트
  - 검증: `./mvnw -Dtest='Stream*' test`

### Phase G — 프론트엔드 (SPEC:981-1014)

현재 라우트 3개, 스펙은 8개 페이지 트리. `page.js` 597줄에 전부 뭉쳐 있다.

- [ ] **G1. 라우트 분해**
  - 할 일: SPEC:983-997 트리대로 `/login`, `/dashboard`, `/portfolio`, `/stocks/[symbol]`, `/events`, `/orders`, `/analysis-history`, `/settings/{broker,risk,security,notifications}` 생성. 기존 컴포넌트(`dashboard-view.js`, `event-workflow.js` 등)를 해당 라우트로 이동. 브로커 연결 선택은 전역 컨텍스트로.
  - 완료 조건: 각 라우트가 독립 렌더되고 기존 12개 테스트 파일이 전부 통과
  - 검증: `cd web-dashboard && npm test && npm run build`

- [ ] **G2. Stock Detail 페이지**
  - 할 일: SPEC:1003 — 차트, 재무, 밸류, 기술, 이벤트, 시나리오, 반대 논리, 무효화 조건. 필수 상태: "분석 중", "데이터 부족". C2/C8 응답 필드를 그대로 표면화.
  - 완료 조건: `missingData` 가 있으면 해당 섹션이 추정치가 아니라 "데이터 부족"으로 렌더되는 테스트
  - 검증: `cd web-dashboard && npm test`

- [ ] **G3. Event Radar 보강**
  - 할 일: SPEC:1004 — 공식 출처, 신뢰도(tier), 관련 기업, 직접/간접/피해 구분, 가격 반응. 미확인·검토 필요 상태 명시.
  - 검증: `cd web-dashboard && npm test`

- [ ] **G4. 주문 승인 재확인 UI**
  - 왜: 지금은 `orderAction` 이 확인 없이 바로 발사됨. SPEC:1012 는 종목·방향·수량·가격·예상 최대 손실을 **텍스트로 재확인**하도록 요구.
  - 할 일: 승인 전 재확인 다이얼로그(값을 텍스트로 명시) + E2 의 `proposalVersion`/`displayedQuantity`/`displayedMaxLoss`/`stepUpToken` 전송. 409 응답 시 새 확인 화면. `UNKNOWN` 강조·재승인 금지(SPEC:1005).
  - 완료 조건: 재확인 없이 승인 API 가 호출될 수 없는 테스트, 409 재확인 플로우 테스트
  - 검증: `cd web-dashboard && npm test`

- [ ] **G5. Dashboard 필수 정보 보강**
  - 할 일: SPEC:1001 — 오늘 손익, 통화별 buying power, 시장 국면, 장 상태, 데이터 stale, kill switch(E4). 미제공 잔액은 `UNKNOWN` 으로 표시하고 0 으로 렌더하지 않음.
  - 검증: `cd web-dashboard && npm test`

- [ ] **G6. Portfolio 위험 지표**
  - 할 일: SPEC:1002, SPEC:939 — 섹터·테마·팩터, 상관관계, beta/VaR/ES, 목표 비중. `GET /api/v1/portfolio/risk` 백엔드 구현 포함.
  - 완료 조건: 데이터 부족 시 숫자를 추정하지 않고 미제공으로 표시
  - 검증: `./mvnw -Dtest='PortfolioRisk*' test && cd web-dashboard && npm test`

- [ ] **G7. Settings/security 페이지**
  - 할 일: SPEC:995, SPEC:1007, SPEC:1159 — 재인증·step-up, 세션 관리, 자격증명 재표시 금지 명시. 기존 broker/risk/notifications 를 하위 라우트로 편입.
  - 검증: `cd web-dashboard && npm test`

- [ ] **G8. 접근성 통과**
  - 왜: SPEC:1009-1014. 현재 `aria-live` 사용처 0건.
  - 할 일: 실시간 상태에 `aria-live`, 수익·손실을 색상 단독으로 구분하지 않음(기호·텍스트 병기), 키보드 포커스 순서, 표 헤더 `scope`, 모바일에서 주문 확인 정보 축약 금지.
  - 완료 조건: 접근성 테스트 파일 추가 후 통과. 손익 표기가 색상 없이도 판별 가능함을 검증
  - 검증: `cd web-dashboard && npm test`

### Phase H — 운영·보안 (SPEC:1138-1162)

- [ ] **H1. 필수 메트릭 8종 구현**
  - 왜: SPEC:1140-1149 중 다음이 미구현 — 브로커 API group 별 latency/error/429/remaining limit, quote·account freshness, 주문 상태별 체류시간·UNKNOWN 수, outbox backlog 와 retry(A1 이후), FastAPI latency/model failure/schema error, 이벤트 수집 지연·중복률, 예측 calibration·전략별 drawdown, 사용자 격리 위반 탐지.
  - 제약: 민감정보를 메트릭 label 이나 로그에 넣지 않음(SPEC:1151)
  - 완료 조건: 8종 전부 `/actuator/prometheus` 에 노출되고, label 에 사용자 식별자·계좌번호가 없음을 검증하는 테스트
  - 검증: `./mvnw -Dtest='*Metrics*' test && ./scripts/prediction-evaluation-observability-drill.sh`

- [ ] **H2. Prometheus + 대시보드 + 알람** *(Q4 의존)*
  - 할 일: compose 로컬/스테이징 스택에 Prometheus scrape 설정 + Grafana 대시보드 JSON + 알람 규칙(UNKNOWN 주문 증가, outbox backlog 증가, 브로커 429, 분석 실패율). 현재 이 셋 전부 0개 파일.
  - 검증: `./scripts/smoke-local-stack.sh`

- [ ] **H3. CI 게이트 확장**
  - 할 일: `release-gates.yml` 에 추가 — 린트/포맷(Java: spotless 또는 checkstyle / JS: eslint+prettier / Python: ruff), 커버리지 임계값, 의존성 취약점 스캔(Java·Python도), 컨테이너 이미지 스캔, 그리고 현재 CI 에서 안 도는 드릴 3종(백업·복구, 스테이징 배포·롤백, 관찰성)을 스케줄 워크플로로.
  - 완료 조건: 새 게이트가 전부 통과하는 상태로 머지
  - 검증: `gh workflow run "Release Gates"` 또는 push 후 확인

- [ ] **H4. 사용자 격리 강제**
  - 왜: SPEC:1133, SPEC:1161, MVP 완료 기준 1번(SPEC:1049). 현재 RLS 정책 0개, `TenantAccessGuard` 없음.
  - 할 일: PostgreSQL RLS 보강 + `TenantAccessGuard`(SPEC:67) + object-level authorization 통합 테스트를 **모든 금융 aggregate 에 대해** 필수화. A5 아키텍처 테스트의 `findById` 금지 규칙과 짝.
  - 완료 조건: 사용자 A 의 세션으로 사용자 B 의 모든 리소스 ID 를 시도하는 파라미터화 테스트가 전부 404/403
  - 검증: `./mvnw -Dtest='*Isolation*,ReleaseSecurityRegression*' test`

- [ ] **H5. 백업 암호화 + 복구 훈련 자동화** *(Q4 의존)*
  - 할 일: SPEC:1162 — 백업 암호화(현재 `backup-postgres.sh` 는 평문 dump). 복구 훈련을 CI 스케줄로 자동 실행하고 결과를 기록.
  - 검증: `./scripts/backup-restore-drill.sh`

- [ ] **H6. 설정 드리프트 정리**
  - 할 일: `.env.example` 에 없는 소비 변수 추가 — `LOG_STRUCTURED_FORMAT`(`application.yml`), `DATABASE_URL`/`DATABASE_PASSWORD`(`compose.yaml`), `DATABASE_USERNAME`(`compose.staging.yaml`), `COMPOSE_PROJECT`(`scripts/backup-postgres.sh`). 그리고 `PREDICTION_*` 13개가 `.env.example` 과 `.env.staging.example` 에 **전량 중복 선언**(두 파일 간 중복이며 파일 내 중복은 아님) — 공통 베이스로 뽑고 스테이징 override 만 남길 것.
  - 검증: `./scripts/test-local-stack.sh`

- [ ] **H7. 워크스페이스 정리**
  - 왜: `.omc/state/sessions/**` 가 `trading-backend/src/main/java/com/jmj/trade/`, `order/`, `prediction/`, `broker/toss/`, `src/main/resources/db/migration/`, 테스트 트리 안에 들어가 있음. 소스 트리에 도구 상태 파일이 섞이면 빌드·아키텍처 테스트·이미지 빌드가 오염됨. **이미 오염됨**: `trading-backend/target/classes/db/migration/.omc/` 가 존재 — 리소스 복사 단계에서 빌드 산출물로 이미 넘어갔고, 컨테이너 이미지에도 들어간다.
  - 할 일: `.gitignore` 에 `.omc/`, `.omo/`, `graphify-out/` 추가하고 소스 트리 내부 상태 디렉터리 제거. 현재 미추적 상태인 `AGENTS.md` 다수는 커밋할지 여부를 결정.
  - 검증: `git status --short` 가 깨끗하고 `./mvnw clean verify` 통과

---

## 4. 완료 정의 (프로젝트 완성 판정)

스펙 SPEC:1047-1056 의 7개 기준을 **자동 테스트로** 증명해야 완료다.

| # | 기준 | 증명 책임 항목 |
|---|---|---|
| 1 | 두 사용자의 계좌·분석·주문 데이터가 API 와 DB 에서 섞이지 않는다 | H4 |
| 2 | 계좌 읽기 동기화가 실패·재시도·부분 실패 상태를 표현한다 | B3, 기존 `account` |
| 3 | 분석 입력과 결과를 같은 시점 기준으로 재현할 수 있다 | C6 |
| 4 | 이벤트가 기존 분석을 새 스냅샷으로 재계산한다 | D4 |
| 5 | Paper 주문은 승인 후 직전 재검증을 통과해야만 체결된다 | B3, E2 |
| 6 | 중복 승인·Inbox 재수신·worker 재실행에도 intent 와 broker order 가 중복 생성되지 않는다 | A1, A2, E2 |
| 7 | UNKNOWN attempt 는 CLOSED 미검색을 미접수로 오판하지 않고 조정된다 | E5 |

추가 완료 조건:
- SPEC:65-80 의존 규칙이 A5 아키텍처 테스트로 강제된다 (독립 `audit`/`outbox`/`inbox` **패키지명**은 필수 아님, 경계 강제가 필수)
- SPEC:1140-1149 필수 메트릭 전부 노출 (H1)
- SPEC:983-997 페이지 트리 전부 존재 (G1)

---

## 5. 전체 검증 명령

```bash
cd /Users/jjm/Desktop/trade

# 백엔드 (단위 + Testcontainers 통합)
cd trading-backend && ./mvnw clean verify && cd ..

# 분석 서비스
cd analysis-service && pip install -e ".[test]" && pytest -q && cd ..

# 프론트
cd web-dashboard && npm ci && npm test && npm run build && cd ..

# 스택 (정적 계약 + 스모크)
./scripts/test-local-stack.sh
./scripts/smoke-local-stack.sh

# 드릴
./scripts/backup-restore-drill.sh
./scripts/staging-deploy-rollback-drill.sh
./scripts/prediction-evaluation-observability-drill.sh
```

---

## 6. 규모 참고

- 총 45개 항목 (Phase 0 결정 4 + 실행 41)
- 최우선: **A1, A2, A5** — 이 셋이 없으면 MVP 완료 기준 6번을 만족할 구조가 없고, 모듈 경계를 안 만든 결정(SPEC:57)의 전제도 성립하지 않는다.
- 실거래 진행(Q2)이 확정되면서 **B3, B4, E2, E4, E5 가 E6 의 선행 필수로 승격**됐다. 이 5개는 Paper 전용일 때는 미뤄도 되지만 실제 체결이 나가는 순간 없으면 안 된다. 실거래 착수 전 체크리스트는 Phase 0 Q2 표를 쓴다.
- 최대 작업량: **Phase C** (분석 서비스가 사실상 미착수, Python 소스 2파일) 와 **Phase G** (라우트 3 → 12).
