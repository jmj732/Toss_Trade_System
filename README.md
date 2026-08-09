# Toss Trade System

미국 주식 트레이딩 플랫폼. Toss 증권 계좌를 연동해 포트폴리오를 분석하고, 시장 이벤트로 재평가한 뒤,
**사용자 승인을 거친 주문만** 실행한다.

> 설계 원본은 [`DESIGN.md`](DESIGN.md), 에이전트 작업 지침은 [`AGENTS.md`](AGENTS.md).

## 아키텍처

```
web-dashboard :3000  ──▶  trading-backend :8080  ──▶  analysis-service :8000
                              │
                              ├──▶ postgres :5432   (system of record)
                              └──▶ redis :6379      (캐시 / 세션)
```

| 서비스 | 경로 | 스택 |
|---|---|---|
| 대시보드 | `web-dashboard/` | Next.js |
| 백엔드 | `trading-backend/` | Java 21 · Spring Boot 4.1 |
| 분석 | `analysis-service/` | Python · FastAPI |

주문·위험·감사 원장은 **백엔드가 단독 소유**한다. 분석 서비스는 분석 결과만 반환하고, 주문에 관여하지
않는다. 백엔드↔분석 서비스 계약은 `contracts/analysis/v1/` 의 JSON fixture 로 고정돼 있다.

Toss API 는 읽기 전용 어댑터로만 접근한다. 실거래 주문은 `REAL_ORDER_ENABLED` 뒤에 있고 기본값은 비활성이다.

## 저장소 구조

```
trading-backend/     Spring Boot 모듈러 모놀리스. 주문·위험·감사 원장 소유
analysis-service/    FastAPI 포트폴리오/이벤트 분석
web-dashboard/       Next.js 대시보드

contracts/           서비스 간 JSON 계약 fixture (백엔드 ↔ 분석)
mocks/               WireMock 기반 Toss 브로커 API 목
scripts/             로컬 스택 · 배포 · 드릴 셸 스크립트

docs/
  ops/               운영 런북 (배포, 롤백, 카나리)
  superpowers/
    specs/           기능별 delta 스펙 — 구현 전 여기에 먼저 쓴다
    plans/           구현 플랜
claudedocs/          E2E 실행 시 생성되는 감사·분석 리포트

DESIGN.md            제품·UX·디자인 원본
AGENTS.md            AI 에이전트 작업 지침 (디렉터리마다 하나씩 더 있음)
```

### compose 파일

전부 루트에 있다. `compose.yaml` 이 베이스이고 나머지는 오버레이다.

| 파일 | 용도 |
|---|---|
| `compose.yaml` | 전체 로컬 스택 (베이스) |
| `compose.dev.yaml` | 개발 전용 오버라이드 |
| `compose.mock.yaml` | Toss WireMock 목 스택 |
| `compose.staging.yaml` | 스테이징 배포 |
| `compose.staging.credentialed.yaml` | 암호화 Toss 온보딩 활성 스테이징 |

## 실행

환경 변수는 `.env.example` / `.env.staging.example` 참고.

```bash
./scripts/smoke-local-stack.sh    # 목 스택 기동 + 스모크 테스트
./scripts/test-local-stack.sh     # compose 계약 검증
```

## 테스트

```bash
cd trading-backend  && ./mvnw clean verify   # Testcontainers Postgres 필요 (Docker + JDK 21)
cd analysis-service && pytest
cd web-dashboard    && npm test && npm run build
```

백엔드 통합 테스트는 `PostgresIntegrationTest` 를 상속해 JVM fork 당 Postgres 컨테이너 하나를 공유한다.
로컬에서 Colima 를 쓴다면 `trading-backend/AGENTS.md` 의 환경 변수 항목을 볼 것.

## CI / CD

```
.github/workflows/
  ci.yml            오케스트레이터 — 변경 경로 판별 + 게이트 결과 집계
  ci-backend.yml    Spring
  ci-analysis.yml   FastAPI
  ci-dashboard.yml  Next.js + npm audit
  ci-stack.yml      compose 스모크
  cd.yml            배포 (CI 성공 후 발동)
```

`ci.yml` 의 `changes` job 이 변경된 경로를 보고 필요한 게이트만 호출한다. 문서만 고치면 아무것도 안 돈다.
`CI gate` job 이 전체 결과를 집계하며, 이것이 `main` 의 유일한 required status check 다.

`main` 에 머지되면 CI 통과 직후 `cd.yml` 이 스테이징에 자동 배포한다.

## 기여

- `main` 직접 push 금지. feature 브랜치 → PR → squash merge
- 브랜치 접두사: `feature/` `fix/` `chore/` `refactor/` `docs/` `test/`
- 기능은 `docs/superpowers/specs/` 에 delta 스펙을 먼저 쓴다. **커밋 1개 = 델타 1개**
- 커밋 메시지는 한국어 `타입 :: 설명` 형식
