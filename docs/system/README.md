# 시스템 핸드북

이 문서는 저장소에 구현된 투자 시스템의 구조와 데이터·계산·운영 경계를 한곳에서 찾아가기 위한 안내서다. 설명은 **기준 코드 시점**의 정적 조사다. 실행 중인 배포, 계정 상태, 데이터 신선도, 외부 제공자 연결 여부를 확인한 운영 점검표로 사용하지 않는다.

## 시작점

| 문서 | 다루는 범위 | 이런 질문에 먼저 읽기 |
|---|---|---|
| [01 · 아키텍처와 기능 경계](01-architecture-and-capabilities.md) | 서비스 구성, 요청 경로, 코드 구현과 런타임 확인의 차이 | 화면·Spring·분석 서비스·DB·외부 연동은 어떻게 이어지나? |
| [02 · 데이터와 계산](02-data-and-calculations.md) | 입력 출처, 스냅샷, provenance, 상태, 계산 규칙 | 값은 어디서 오고 어떤 조건에서 계산되나? |
| [03 · Thesis, 위험과 이벤트](03-thesis-risk-and-events.md) | thesis 상태, 위험 정책, 모니터링, 이벤트 및 알림 흐름 | 검토 결과와 위험 상태는 어떻게 저장·전환되나? |
| [04 · API, MCP와 Sheets](04-api-mcp-and-sheets.md) | REST, connector/MCP 권한, Google Sheets 입출력 경계 | 사람·외부 assistant·시트가 어떤 인터페이스를 쓰나? |
| [05 · 운영과 검증](05-operations-and-verification.md) | Compose, 스케줄러, 배포, 백업, 드릴과 검증 근거 | 로컬 실행·운영 점검·복구는 어디서 확인하나? |

큰 그림만 빠르게 볼 때는 이 안내와 [01 문서](01-architecture-and-capabilities.md)를 읽는다. 특정 값을 해석할 때는 02, thesis/위험 흐름은 03, 외부 연결은 04, 배포와 점검은 05로 이동한다. 저장소의 기존 [README](../../README.md)는 로컬 실행·테스트와 Investment OS 데이터 안내를 계속 제공한다.

## 이 시스템이 맡는 일

현재 코드에는 다음 역할이 있다.

- 브라우저용 Next.js 대시보드가 계좌, 보유 종목, 시장, 분석 결과, 위험 정책, 주문과 운영 화면을 제공한다.
- Java/Spring 백엔드가 인증된 요청, Toss 연동, 포트폴리오·분석·thesis·이벤트·위험·주문 레코드와 애플리케이션 API를 소유한다.
- Python/FastAPI 분석 서비스가 버전이 지정된 요청 계약으로 포트폴리오·종목 분석과 예측 계산을 수행한다. Spring과의 계약 fixture는 `contracts/analysis/`에 있다.
- PostgreSQL은 애플리케이션이 생성하는 스냅샷, 분석 실행, 사용자 결정, 위험/주문 상태 및 감사 이력을 저장한다. Redis는 Toss 토큰 조정 및 예측 수집 API 키 rate limit 같은 코드 경로에서 사용된다.
- 별도 어댑터와 설정을 통해 MCP connector, Google Sheets, Telegram, Toss 및 외부 데이터 제공자를 연결할 수 있다.

이는 기능의 코드가 있다는 뜻이다. 운영에서 연동이 활성화됐거나, 자격 증명이 유효하거나, 최신 자료가 들어오고 있거나, 실계좌 주문이 가능한지를 뜻하지 않는다. 상세한 경계는 [01의 기능/상태 표](01-architecture-and-capabilities.md#기능-범위와-확인-경계)를 기준으로 읽는다.

## 읽을 때 기억할 데이터 원칙

- 값을 설명할 때 출처, 관측 시각, 상태와 누락 사유를 함께 본다. `null`, `PARTIAL`, `STALE`, `UNVERIFIED`는 임의의 숫자나 긍정 상태로 바꾸지 않는다.
- 화면/API의 계산 값은 입력이 충분한 경우에만 산출된다. 필드가 없거나 통화가 맞지 않을 때 0이나 임의 환율로 보정하지 않는 계약이 여러 서비스에 구현돼 있다.
- PostgreSQL이 애플리케이션 저장소라고 해도 모든 외부 사실의 원천은 아니다. 브로커 응답, 제공자 자료, 사용자가 관리하는 계좌·thesis 입력은 서로 다른 입력 경계다.
- Google Sheets는 사람이 관리하는 계좌 입력 일부와 생성된 미러를 함께 담는 연동 표면이다. 모든 탭이 원본 데이터이거나 모든 셀 편집이 백엔드로 되돌아가는 것은 아니다.
- 결정지원·위험 계산 결과는 사용자가 검토할 근거다. 그 자체로 매매 권고, thesis 확정, 주문 승인 또는 주문 실행을 뜻하지 않는다.

## 유지보수 책임과 갱신 조건

문서 내용은 소스와 기존 계약 문서를 따라간다. 아래 변경이 발생한 PR에서는 관련 행의 문서도 코드 변경과 함께 갱신한다.

| 문서 | 주 책임 | 함께 갱신할 때 |
|---|---|---|
| 이 README | 문서 내비게이션, 범위와 용어, 유지보수 경로 | 핸드북 파일 구성이나 문서 목적·읽기 경로가 바뀔 때 |
| 01 · 아키텍처와 기능 경계 | 서비스 연결, 역할, 전체 capability matrix | 서비스/모듈 경계, 데이터 저장 위치, 사용자·외부 연동 경로, 실행 게이트가 바뀔 때 |
| 02 · 데이터와 계산 | provider, 입력 필드, 상태 의미, 계산 공식 | 출처, freshness, 누락 처리, 변환/계산 공식을 바꿀 때 |
| 03 · Thesis, 위험과 이벤트 | thesis revision/approval, risk mark·gate, event transition | 상태 머신, 위험 공식/정책, 알림 또는 event-to-decision 연결을 바꿀 때 |
| 04 · API, MCP와 Sheets | REST/MCP 도구 계약, scope, 시트 탭·열·쓰기 경계 | API/tool schema, scope, connector 등록, Sheets mapping/ownership을 바꿀 때 |
| 05 · 운영과 검증 | 설정, Compose, scheduler, deploy/rollback/backup/drill | 기본값, 운영 절차, 복구·검증 커맨드, CI/CD gate를 바꿀 때 |

구현을 대표하는 1차 근거는 `trading-backend/src/main/java/`, `analysis-service/app/`, `web-dashboard/app/`, `contracts/`, `compose*.yaml`, `trading-backend/src/main/resources/application.yml`이다. 기존 운영 세부는 [`docs/ops/`](../ops/)의 런북과 계약 문서를 우선 연결한다. 문서만 수정한 변경의 처리 방식과 검증 한계는 저장소 루트 [README](../../README.md)의 CI / CD 안내를 참조한다.

## 조사 기준

이 핸드북의 코드 기준은 **2026-10-18**, commit **`3e9aacafa72bd0315d14b4ac5c30912ac6b541fd`**다. 이후 코드에 차이가 있다면 이 문서는 현재 구현을 대신하지 않으므로 해당 소스와 변경 이력을 확인한다. 이 기준에는 실제 운영 환경에 접속하거나 실계좌·외부 제공자를 호출한 검증이 포함되지 않는다.
