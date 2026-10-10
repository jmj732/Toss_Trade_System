# Toss Trade System

미국 주식 트레이딩 플랫폼. Toss 증권 계좌를 연동해 포트폴리오를 분석하고, 시장 이벤트로 재평가한 뒤,
**사용자 승인을 거친 주문만** 실행한다.

> 설계 원본은 [`DESIGN.md`](DESIGN.md), 에이전트 작업 지침은 실행 환경의 `AGENTS.md` 를 따른다.

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

## Investment OS 데이터 안내

ChatGPT의 Toss 커넥터는 MCP를 통해 Spring 백엔드에 읽기를 요청한다. `get_investment_context`는 PostgreSQL의 저장된 스냅샷과 사용자별 입력/이력을 읽어 context DTO를 구성한다. 합산 포트폴리오는 스냅샷으로 저장되고, 나머지 context는 저장된 레코드를 결합하거나 계산해 반환한다. 별도의 선택적 동기화 작업이 계좌 입력을 받아 PostgreSQL 스냅샷을 저장한 뒤 Google Sheets에 계좌 데이터와 context 미러를 반영한다. MCP context 조회가 Google Sheets를 직접 읽거나 갱신하지는 않는다.

```text
ChatGPT → Toss Connector MCP → Spring Boot → PostgreSQL (스냅샷·입력·이력)
                                      │
                                      └─ 선택적 동기화 → Toss 계좌 / Google Sheets
                                                           (입력 + 생성된 미러)
```

### 계좌, 데이터 출처와 소유권

- `ACCOUNT_1`은 설정된 Toss 계좌다. `ACCOUNT_2`는 사람이 관리하는 수동 계좌이며, `Account Registry`에 `ACCOUNT_2` 행이 정확히 하나 있고 `Sync Mode=MANUAL`, `Enabled=TRUE`일 때만 합산 대상이다. 수락된 합산 포트폴리오 스냅샷은 PostgreSQL에 저장되며, context는 그 스냅샷을 읽는다. 잘못되거나 실패한 수동 계좌 읽기는 직전 합산 스냅샷을 보존한다.
- 정식 입력 계약상 Toss는 계좌와 가격, SEC CompanyFacts는 공시 재무 사실과 검증된 기본 주식 수, Alpha Vantage는 애널리스트 추정치를 제공한다. SEC에 유효한 희석 주식 수가 없을 때 Alpha Vantage 값을 사용할 수 있다. FMP는 선택 제공자이며 코드 기본값은 비활성이다. 이는 코드의 기본값과 출처 계약 설명이지, 현재 배포 설정이나 특정 데이터 값에 대한 주장이 아니다. 자세한 기준은 [정식 투자 데이터 안내](docs/ops/canonical-investment-data.md)를 본다.
- 가격·재무·컨센서스·분석 입력은 소스, 관측 시각, 상태와 사유를 가능한 범위에서 함께 유지한다. 누락값은 0으로 대체하지 않으며 `null`과 네이티브 상태를 그대로 읽는다. 제공자 상태나 데이터의 부분성은 [MCP 계약 감사](docs/ops/investment-context-mcp-contract.md)에 정리돼 있다.

### Google Sheets 탭

현재 동기화 코드가 사용하는 탭은 계좌·운영 7개와 연구 미러 7개다. Google Sheets는 혼합 입력/출력 화면이며, 모든 셀이 정식 데이터 저장소인 것은 아니다.

| 탭 | 내용과 편집·덮어쓰기 경계 |
|---|---|
| `Account State` | 계좌별 보유 종목·현금·수량·평균단가·가격·출처·시각. 사람이 입력한 `ACCOUNT_2` 수량·평균단가·출처·원본 시각은 보존한다. Toss 동기화가 `ACCOUNT_1` 행을 관리하고, 보유 종목의 `Current Price`, `Price Source`, `Price Synced At`, `Market Value`는 두 계좌 모두 새 시세로 갱신될 수 있다. |
| `Account Registry` | 계좌 라벨, 동기화 방식, 출처, 사용 여부와 마지막 동기화. `ACCOUNT_2`의 수동 설정은 사람이 관리하고, 동기화는 `ACCOUNT_1`의 `Last Sync`를 갱신한다. |
| `Portfolio Aggregate` | 계좌 합산 종목·수량·평균원가·시장가치·출처 범위. 계좌 및 시세 입력이 유효할 때 다시 계산하는 출력이다. |
| `Portfolio Metrics` | 계좌별·합산 평가액, USD 현금, 비중, 고점 및 낙폭 등 파생 지표. 필수 금액과 시세가 확인될 때 계산한다. 검증된 환율이 없으므로 KRW 현금을 USD 합계로 바꾸지 않는다. |
| `Orders` | Toss의 열린 주문을 보여주는 동기화 출력이다. |
| `Order History` | 동기화한 종료 주문 이력이다. 주문 정보는 브로커 동기화가 관리한다. |
| `Reconciliation Log` | 동기화 시각, 계좌, 보유·현금·주문·체결·가격 상태와 불일치/오류 기록이다. 동기화가 추가·갱신한다. |
| `Security Snapshot` | 아래 항목의 넓은 종목별 생성 미러다. PostgreSQL/context에서 만들어지며 사용자가 입력하는 원본 탭은 아니다. |
| `Thesis State` | 종목별 논지, 상승 요인, 기대 차이, 무효화 조건, 위험 가격, 상태와 분류. 저장된 사용자 thesis의 미러다. 관리 열 A:N에 `Sizing Eligible`을 포함하고, 호환되는 후속 수동 열은 행 위치와 함께 보존한다. |
| `Consensus History` | 시점·기간별 매출/EPS/EBITDA/FCF 추정치, 출처, 추정 유형, 통화와 애널리스트 수를 보여주는 이력 미러다. |
| `Watchlist` | 관심 종목 상태, 가격 레벨, 근거와 관측 시각의 미러다. |
| `Decision Ledger` | 저장된 사용자 결정과 당시 위험 점검의 미러다. 전술 열은 `EntrySetup`, `InitialRiskPrice`, `OverlayEffect` 세 개이며 `Decision ID`로 해당 결정에 연결한다. 수동 확장 열은 보존하고, 해당 형식의 탭에서는 전술 셀만 갱신한다. |
| `Alpha State` | 저장된 종목별 섹터·팩터·베타·상관·thesis·조건 정보의 미러다. |
| `Risk Policy` | 현재 위험 정책과 저장된 정책 이력의 미러다. |

연구 탭은 백엔드의 저장된 context/DB 결과를 미러링하므로 시트 편집을 canonical thesis, decision, risk, tactical 입력으로 가져오지 않는다. 헤더가 맞는 탭은 동기화 시 생성 표가 갱신된다. `Thesis State` 또는 `Decision Ledger`의 선행 헤더가 맞지 않으면 해당 탭은 보존하고 미러를 건너뛴다. 계좌 입력과 헤더/갱신 동작은 [시트 동기화 런북](docs/ops/investment-os-sheet-sync.md), [시트 모델](trading-backend/src/main/java/com/jmj/trade/sheets/InvestmentOsSheetModel.java), [계좌 동기화](trading-backend/src/main/java/com/jmj/trade/sheets/InvestmentOsSheetSyncService.java), [연구 미러](trading-backend/src/main/java/com/jmj/trade/sheets/InvestmentOsResearchSheetSync.java)에 있다.

### Security Snapshot 필드

`Security Snapshot`은 종목별 한 행에 다음 실제 필드 그룹을 펼친다.

- 보유 수량·비중·통화, 포지션 출처와 포함 계좌, 계좌/수량/가격 기준 시각
- 정규장 종가와 시각, 최신 가격과 시각, 세션, 주·보조 출처, 가격 상태
- 기술 상태, SMA20/SMA50, RSI14
- 재무 기간·보고/관측 시각·출처, 시가총액·기업가치·현금·부채·주식 수, 매출·성장률·EBITDA·EPS·FCF 및 계산/필드 출처 정보
- 컨센서스 기준 시각·기간·출처·추정 유형/통화·애널리스트 수와 매출/EPS/EBITDA/FCF 값, 매출/EPS 30일·90일 리비전 값과 상태
- 밸류에이션 배수·수익률·정규화 FCF, 데이터 준비 상태와 누락 필드
- thesis와 위험 기여, 위험/무효화 스트레스 상태, 소프트 예산 상태, `Risk Sizing Eligible`
- 전술 오버레이의 `ThemeId`, 문자열 `TrendStage`

열 값에는 적용 가능한 출처·기준 시각·상태·사유가 함께 있으며, 빈 값이나 JSON `null`은 알 수 없거나 제공되지 않은 값이다. `invalidationStatus`는 `NOT_REVIEWED`, `SUSPECTED`, `CONFIRMED`, `CLEARED`, `AI_PROPOSED`, `UNVERIFIED`, `INVALIDATION_UNDEFINED` 중 하나인 저장 상태 문자열이다. V56은 기존 상태를 유지하며 외부 제안 상태를 추가한다. `sizingEligible`/`Risk Sizing Eligible`은 enum이 아닌 boolean이며, `true`가 되려면 thesis 무효화가 `CONFIRMED`이고 포트폴리오가 최신이며 입력을 신뢰할 수 있고, 비중·하락폭·손실 기여값이 있으며, 포트폴리오 `thesisFailureStress`가 `softRiskBudget` 이내(`WITHIN_SOFT_BUDGET`)여야 한다. 예산 미설정·미평가·초과는 `RISK_BUDGET_NOT_CONFIGURED`·`RISK_BUDGET_UNEVALUATED`·`RISK_BUDGET_EXCEEDED` 사유로 구분한다. `CONFIRMED` 승인은 인증된 사용자 REST `PUT /investment/securities/{ticker}/thesis` 또는 V59 Telegram 2단계 승인(아래 쓰기 경계 참조)으로만 가능하고 MCP 도구로는 여전히 불가능하며, 승인(신규·미확정→확정·트리거 가격 변경)에는 `sourceAsOf`와 이전 행의 `expectedUpdatedAt`이 필요하다. 모든 thesis 쓰기(MCP 제안 포함)는 V57 `investment_thesis_revisions`에 actor·이전/새 상태·트리거·`sourceAsOf`·사유를 같은 트랜잭션으로 append하고, `GET /investment/securities/{ticker}/thesis/revisions`로 조회한다. V57 이전 이력은 backfill하지 않는다. `invalidationDownside`·`plannedLossContribution`과 이로부터 파생되는 `thesisFailureStress`·`top2CorrelatedStress`는 오직 승인된(`CONFIRMED`) 무효화 트리거에서만 계산한다. 미확정 thesis(`AI_PROPOSED`·`UNVERIFIED`·`INVALIDATION_UNDEFINED`)가 트리거 가격을 갖고 있어도 위험 숫자에 사용하지 않으며 `status=UNVERIFIED`에 값은 `null`이다. 트리거가 아예 없으면 `NOT_CONFIGURED`다. `CONFIRMED`이지만 신뢰 가격이 이미 트리거를 하회(트리거가 현재가보다 높음)하면 값을 지어내지 않고 `null`·`status=DATA_MISSING`을 유지하며 `INVALIDATION_PRICE_BREACHED` 사유로 구분한다. 추가 필드 `securities[].sizingEligibility`와 `Thesis State`의 `Sizing Eligible`은 `YES / NO / CONDITIONAL` 문자열이다. `CONFIRMED`와 기존 위험 적격 조건이 모두 충족되면 `YES`, `CONFIRMED`지만 위험 입력이 불충분하면 `CONDITIONAL`, 미확정 또는 thesis가 없으면 `NO`다. 기존 boolean은 유지한다. `risk.eligibilityReasons`는 적격이 아닌 이유 코드를 결정적 순서로 담는 추가 필드이며 적격일 때만 비어 있다(`THESIS_MISSING`, `INVALIDATION_NOT_CONFIRMED`, `INVALIDATION_PRICE_NOT_CONFIGURED`, `INVALIDATION_PRICE_BREACHED`, `PORTFOLIO_STALE`, `PORTFOLIO_PARTIAL`, `PORTFOLIO_UNAVAILABLE`, `PRICE_STALE`, `PRICE_MISSING`, `PRICE_SOURCE_CONFLICT`, `PRICE_UNVERIFIED`, `POSITION_WEIGHT_MISSING`). 이는 계산 상태 설명일 뿐 감사 이력이 아니다. 부분 데이터에서도 context 호출은 성공할 수 있으므로 숫자만 보고 신뢰도를 추정하지 말고 원래 상태와 사유를 함께 본다.

상시 모니터링은 보유 종목의 `CONFIRMED` thesis 무효화 트리거만 감시한다. 검증된 정규장 가격이 트리거 이하(`BREACH`)이거나 트리거 위 3% 이내(`NEAR`)이면 `MONITORING_ALERT`(`scope=THESIS_REVIEW`)로 "투자 논리 재검토 필요 — 자동 무효화·주문 없음" 알림을 종목·수준·트리거 대비 거리 %만 담아 세션당 한 번 보낸다. thesis 상태·트리거·이력은 바꾸지 않고 주문도 만들지 않는다. 미승인 트리거와 `STALE`/`UNVERIFIED`/누락 가격은 알리지 않는다. 규칙과 `monitoring_position_contexts`와의 역할 분리는 [상시 모니터링 런북](docs/ops/continuous-monitoring.md#보유-종목-confirmed-thesis-무효화-가격-재검토-알림)에 있다.

`top2CorrelatedStatus`는 유효한 보유 위험 입력이 있는 종목쌍의 30개 이상 paired 일수익률로 계산한다. 두 종목 미만이거나 모든 계산 가능한 상관이 0 이하이면 `NOT_APPLICABLE`, 양의 상관 후보가 없고 후보 쌍 이력이 부족하면 `INSUFFICIENT_HISTORY`, 충분한 이력에서 상관을 계산할 수 없으면 `UNVERIFIED`이며, 양의 상관 쌍은 기존 `OK`/`STALE` 상태를 따른다. `price.status`는 최신 시세(`latestPrice`)의 상태다. 분류된 세션(`PREMARKET`/`LIVE_REGULAR`/`AFTER_HOURS`) 시세는 기본 15분 창, 저장 종가 fallback(`REGULAR_CLOSE`)은 기본 7일 창을 그대로 쓴다. 세션이 `null`인 시세는 wall-clock 창만으로 `STALE`이 되지 않는다. `sessionReason=TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS`이면 캡처 시 Toss 공식 캘린더에서 구한 `nextDeclaredIntervalStartsAt` 전까지 저장 상태(`PARTIAL`)를 유지하고 그 시각이 지나면 `STALE`이다. 캘린더 근거가 없으면 15분 경과 후 `UNVERIFIED`, 7일 경과 후 `STALE`이다. 정규장 종가는 별도 필드로 판정한다. `regularCloseSessionDate`(Toss 일봉 라벨의 뉴욕 거래일)와 `lastCompletedSessionDate`(캡처 시각 기준 Toss 캘린더가 선언한 마지막 완료 정규장)가 같으면 `regularCloseStatus=OK`, 더 이르면 `STALE`이다. `regularCloseValidUntil`(다음 정규장 종료 시각)이 지나면 `STALE`, 캘린더를 확인할 수 없으면 `UNVERIFIED`, 종가가 없으면 `DATA_MISSING`이다. 종가가 `latestPrice`·`price.status`·위험 입력에 들어가는 유일한 경로는 기존 저장 종가 fallback이다. 채택된 시세가 하나도 없을 때만 선택되며 `session=REGULAR_CLOSE`로 명시되고 `price.status`는 7일 창을 쓴다. 이 경우 캘린더 사실은 채택된 Toss 시세가 있을 때만 캡처되므로 `regularCloseStatus`는 `UNVERIFIED`다. 그 외에는 종가가 시세를 대체하지 않는다. 따라서 장 마감 후 마지막 캡처가 애프터마켓 시세라면 15분 뒤 `price.status=STALE`, `regularCloseStatus=OK`가 함께 나올 수 있다. `regularCloseAsOf`는 거래일 00:00(뉴욕) 라벨이며 정확한 거래소 종가 시각이 아니다. 상태와 입력 게이트는 [context MCP 계약](docs/ops/investment-context-mcp-contract.md)을 본다.

시트 합산 포트폴리오의 신선도(`ACCOUNT_1` 기준 시각, 보유 종목별 `Price Synced At`, `PORTFOLIO_STALE`)는 기본 15분 창으로 판정한다. 시트 동기화가 Toss 공식 캘린더를 확인해 스냅샷의 가장 이른 시각이 선언된 모든 거래 구간(데이마켓·프리·정규·애프터) 밖임을 기록한 경우에만 예외를 둔다. 이 경우 다음 선언 구간이 시작되기 전까지 그 스냅샷을 최신으로 본다. 그래서 마지막 구간이 끝난 뒤 캡처한 주말·휴장일 스냅샷이 장이 닫혔다는 이유만으로 `STALE`이 되지 않는다. 마지막 구간 종료 뒤 계좌 스냅샷이 없으면 시트 동기화가 기존 읽기 전용 계좌 동기화를 실행한다(장외 구간당 최대 3회 시도, 주문 없음). 캘린더 사실이 없으면 기존 15분 규칙을 그대로 쓴다. 자세한 내용은 [시트 동기화 문서](docs/ops/investment-os-sheet-sync.md)를 본다.

전술 오버레이의 세부 EMA9/EMA21/EMA50, RVOL20, 20/60 거래일 수익률과 SPY 대비 상대강도, 이벤트/cohort, 앵커 VWAP, `currentR`/`realizedR`/MAE/MFE, 테마·시장 집계는 DB/API context 출력이다. `Security Snapshot`은 그중 `ThemeId`와 `TrendStage`만 미러링하고, `Decision Ledger`는 `EntrySetup`, `InitialRiskPrice`, `OverlayEffect`를 미러링한다. `DailyVWAP20Proxy`는 최근 20개 완료 일봉에서 `[(고가+저가+종가)/3 × 거래량]` 합을 거래량 합으로 나누고, `DailyAVWAP`는 명시된 앵커일부터 계산한다. 둘 다 일봉 지표이며 장중 VWAP나 체결 가격이 아니다. 계산 및 세션 인증 입력 API 경로는 [전술 오버레이 계산 안내](docs/ops/tactical-overlay-v1-calculations.md)를 본다.

### MCP 읽기 도구와 Investment context

`connector:read`는 아래 다섯 읽기 도구를 노출한다. 첫 네 도구는 Toss 브로커 계좌/주문 읽기이고, `get_investment_context`는 PostgreSQL에 저장된 합산 context 읽기다. 따라서 브로커 포트폴리오 응답을 `ACCOUNT_2`까지 포함한 합산 context로 간주하지 않는다.

커넥터 MCP 전송 경로는 `POST /api/v1/connector/mcp`이며 OAuth scope가 도구 목록을 정한다. 동일 context는 로그인 사용자용 Spring REST `GET /investment/context`에서도 읽는다.

도구를 새로 배포한 뒤에는 기존 ChatGPT 연결에서 **Settings → Plugins → Toss Plugin → Tools Refresh (도구 새로 고침)**를 선택하고, 새 채팅에서 도구 목록을 확인한다. 기존 **Allow all** 설정과 `connector:trade` 범위는 그대로 사용하며 범위 변경이 없으면 재연결이나 새 권한 승인은 필요하지 않다. 새 채팅 GUI에서 `put_investment_thesis`가 발견되고 기존 `AI_PROPOSED` Sheet 행 6개에 호출됐다. 독립 MCP 재조회에서 반환된 11개 필드가 각 행과 일치했고, 자동 Sheet 미러도 확인했다. 이 검증은 새 채팅 GUI에 한정되며 Scheduled Task의 쓰기 실행을 검증한 것은 아니다. 자세한 절차는 [connector 런북](docs/ops/connector-runbook.md)을 참고한다.

| 도구 | 읽는 데이터 |
|---|---|
| `get_portfolio` | Toss Invest의 최신 브로커 포트폴리오 스냅샷 |
| `get_orders` | Toss 브로커 주문 (`OPEN` 또는 `CLOSED`) |
| `get_recent_fills` | 열린/종료 주문에서 산출한 체결 수량 |
| `get_order` | 브로커 주문 ID 또는 클라이언트 주문 ID의 최신 상태 |
| `get_investment_context` | 현재 인증 사용자에 대해 저장된 Investment OS context |

MCP 요청은 인자를 생략하거나 선택적 ticker만 줄 수 있다.

```json
{"name":"get_investment_context","arguments":{}}
{"name":"get_investment_context","arguments":{"ticker":"AAPL"}}
```

Ticker는 1~32자의 영문자·숫자·`.`, `_`, `-`만 허용하고 대문자로 정규화한다. ticker 필터는 `securities` 배열만 줄이며 나머지 context는 전체 사용자 범위 그대로다. 모르는 ticker나 다른 인자는 `INVALID_ARGUMENT`다. context service가 없거나 DB 읽기에 실패하면 재시도 가능한 `INVESTMENT_CONTEXT_UNAVAILABLE`, 예기치 않은 실패는 `INTERNAL_ERROR`를 반환한다. 인증 실패는 HTTP 401/403이다. 이 MCP 도구는 provider 호출, 브로커/시트 refresh, 데이터 쓰기를 하지 않는다.

`ContextView`의 정확한 최상위 필드는 8개다.

| 필드 | 들어 있는 내용 |
|---|---|
| `portfolio` | PostgreSQL에 저장된 합산 포트폴리오, 계좌/수동 계좌 기준일, 상태·stale·누락 정보와 위험 숫자 사용 가능 여부 |
| `securities` | 보유/추적 종목의 가격·기술·재무·컨센서스/리비전·밸류에이션·준비 상태, thesis, 위험, 종목 전술 오버레이 |
| `watchlist` | 저장된 관심 종목과 수준·근거 |
| `riskPolicy` | 현재 위험 정책 |
| `decisionLedger` | 사용자가 저장한 결정과 해당 결정의 위험 정책 점검 |
| `pipeline` | 수집 상태, 마지막 시도/성공 시각과 오류 |
| `tacticalOverlay` | 포트폴리오 수준 시장/테마 집계와 상태·기준 시각·출처 |
| `decisionOverlays` | `Decision ID` UUID로 키가 정해진 결정별 setup, 초기 위험 가격, 효과, 출처/버전과 성과 |

합산 포지션의 수량은 포함 계좌의 수량을 합친 값이며, `accountsIncluded`와 `sourceCoverage`가 기여 계좌/출처를 표시한다. 비중은 양수인 합산 USD 평가액과 USD 종목 시장가치가 있을 때만 계산된다. 혼합 통화나 누락값은 0 또는 임의 환산으로 메우지 않는다. `riskNumbersAvailable`은 숫자 계산에 필요한 현재 가격·평가액·비중을 사용할 수 있는지 나타내며, stale 수동 출처가 있어도 이미 알려진 숫자는 남을 수 있다. `sizingEligible`은 그보다 엄격한 별도 판정이다.

부분 데이터도 보통 MCP 성공 응답(`isError=false`)으로 반환되며 JSON `null`과 각 필드의 네이티브 상태를 유지한다. `asOf`, `sourceAsOf`, 그리고 성과에 제공되는 `evaluationAsOf`, `barAsOf`, `markAsOf`는 서로 다른 관측 시각이므로 합치지 않는다. `DataStatus` 예시는 `OK`, `PARTIAL`, `DATA_MISSING`, `SOURCE_CONFLICT`, `STALE`, `INSUFFICIENT_HISTORY`, `UNVERIFIED`, `NOT_APPLICABLE`, `NOT_CONFIGURED`다. `TrendStage`도 enum이 아닌 문자열이다. 미확인을 `0`, `NO`, `CONFIRMED` 등으로 임의 변환하지 않는다. 출력과 오류 코드는 [context MCP 계약](docs/ops/investment-context-mcp-contract.md), 연결/범위 설정은 [connector 런북](docs/ops/connector-runbook.md), [MCP 프로토콜 코드](trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java), [Context DTO/API 코드](trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java)에 정리돼 있다.

### 갱신 주기와 쓰기 경계

다음은 저장소 코드의 기본값이며 배포 환경에서 실제 적용된 값이나 현재 데이터 시각을 뜻하지 않는다.

- Portfolio refresh sweep는 `portfolio.refresh.enabled=true`일 때만 켜진다. 실행 시 기본 반복 간격은 `PT15M`, 초기 지연은 `PT1M`이며 `portfolio.refresh.interval`, `portfolio.refresh.initial-delay`로 바꿀 수 있다. [스케줄러와 속성](trading-backend/src/main/java/com/jmj/trade/refresh/ScheduledPortfolioRefreshScheduler.java) 참조.
- Investment data scheduler는 기본 비활성이다. 활성화하면 기본 시간대 `America/New_York` 기준 평일 16:15 후장 일봉 캡처, 주말 16:15 전체 캡처, 평일 08:00 장전 포트폴리오 갱신/캡처가 설정돼 있다. 장중에는 평일 09:30~16:00 사이 `PT5M` 간격으로 quote-only 캡처하고 초기 지연은 `PT1M`이다. 이 시간 판정에는 거래소 휴장일 달력이 적용되지 않는다. 기본값은 `INVESTMENT_DATA_TIME_ZONE`, `INVESTMENT_DATA_AFTER_CLOSE_CRON`, `INVESTMENT_DATA_WEEKEND_CAPTURE_CRON`, `INVESTMENT_DATA_PRE_MARKET_CRON`, `INVESTMENT_DATA_INTRADAY_INTERVAL`, `INVESTMENT_DATA_INITIAL_DELAY`로 덮어쓸 수 있다.
- Google Sheets 동기화도 기본 비활성이다. 활성화된 경우 기본 반복 간격 `PT5M`, 초기 지연 `PT1M`이며 `INVESTMENT_OS_SHEET_INTERVAL`, `INVESTMENT_OS_SHEET_INITIAL_DELAY`로 변경할 수 있다. 속성은 [application.yml](trading-backend/src/main/resources/application.yml), [데이터 scheduler](trading-backend/src/main/java/com/jmj/trade/investment/InvestmentDataScheduler.java), [시트 scheduler와 속성](trading-backend/src/main/java/com/jmj/trade/sheets/InvestmentOsSheetScheduler.java)에 정의돼 있다.
- `get_investment_context` / `GET /investment/context`는 저장된 레코드와 스냅샷만 읽고 context를 구성한다. 이 주기를 실행하거나 refresh하지 않는다. 후장 캡처는 일봉 분석 입력을 수집하고, 5분 주기는 quote-only 입력을 수집한다. 시트 자동 동기화는 계좌 입력을 읽고 생성된 값/미러를 반영한다. 미러는 Sheets 셀에서 MCP로 직접 기록하는 통로가 아니다.
- 기존 `connector:trade` 쓰기 범위에는 `put_investment_thesis`와 `append_investment_decision`이 있다. Thesis proposal은 외부 작성 값만 저장하며, decision 도구는 caller가 제공한 전체 `DecisionInput`을 기존 append-only decision ledger에 기록한다. 인자는 `decisionId`, `asOf`, `asset`, `action`, `referencePrice`, `priceSession`, `horizon`, `alphaThesis`, `invalidation`, `nextReviewTrigger`, `confidence`다. `decisionId`는 같은 레코드의 안전한 재시도 키이며, 같은 ID에 다른 내용이 오면 `DECISION_CONFLICT`를 반환한다. `riskPolicyCheck`와 `createdAt`은 기존 서비스가 계산/기록한다. 현재 Sheet의 7개 legacy decision 행은 `REVIEW` action, UUID가 아닌 식별자, 지원하지 않는 price session, 그리고 7개 중 6개 행의 가격 누락 때문에 이 입력 계약과 호환되지 않는다. 도구는 이 행을 자동 import/변환하지 않으며 action, session, 가격을 추정하지 않는다. 두 도구 모두 주문을 생성하거나 제출하지 않고, 새 앱 권한도 추가하지 않는다. Read scope만 가진 키는 쓸 수 없다. 세부 계약은 [context MCP 계약](docs/ops/investment-context-mcp-contract.md)과 [connector 런북](docs/ops/connector-runbook.md)을 참조한다.
- `append_investment_review`(같은 `connector:trade` 범위)와 `POST /investment/reviews`, `GET /investment/reviews?limit=`는 V58 `investment_review_records`에 검토/감사 메모만 append한다. Decision과 달리 투자 action이 아니며 decision·주문·thesis를 만들지 않는다. `rawAction`(예: `REVIEW`), `rawPriceSession`, `recordKey`(UUID 아니어도 됨), 원본 `rawPayload`를 그대로 보존하고, `priceSession`은 원본이 지원 세션과 정확히 일치할 때만 채운다(추정 금지). `referencePrice`는 선택이며 `PORTFOLIO` scope는 asset이 없다. 같은 (user, source, `recordKey`)와 같은 본문 재전송은 저장된 행을 그대로 돌려주고, 다른 본문은 충돌이다. Context는 최근 50건을 `reviewLog`로 노출하고, 시트는 별도 `Review Log` 탭에만 미러링하며 `Decision Ledger` 탭에는 쓰지 않는다. 7개 legacy 행 import(`source=SHEET_LEGACY_IMPORT`)는 사용자 원본 행 제공 전까지 보류다. 새 도구는 ChatGPT에서 Tools Refresh가 필요하다. 세부는 [review record 계약](docs/ops/investment-context-mcp-contract.md#review-records-v58)을 참조한다.
- Telegram 2단계 승인(V59, 기본 비활성): 로그인 사용자가 `POST /investment/securities/{ticker}/thesis/approval-requests`(본문 `{candidateSource, expectedThesisUpdatedAt?}`, 가격 입력 없음)로 요청을 만들고 `GET /investment/thesis/approval-requests`로 조회한다. 운영에서는 이 REST가 Vercel로 프록시되지 않으므로, 승인 채팅에서 승인자가 Telegram `/review TICKER [ATR|SUPPORT|PROPOSAL]`로 같은 생성 경로를 호출하고 `/pending`으로 열린 요청을 본다(같은 chat·from 관문, 가격 입력 없음). `candidateSource`는 `EXISTING_PROPOSAL`(저장된 제안 트리거), `COMPUTED_ATR`, `COMPUTED_SUPPORT`(저장된 완료 일봉으로 서버가 계산) 중 하나이며 후보가 없으면 거부한다. Telegram에서 [승인] 후 5분 안에 [최종 승인]을 눌러야만 thesis가 `CONFIRMED`(트리거=요청 행 값, 다른 필드와 `priceRiskTrigger` 문구는 불변, revision actor `TELEGRAM`)가 된다. AI_PROPOSED가 자동으로 확정되는 경로는 없고 주문도 만들지 않는다. 웹훅·상태 머신·후보 공식·운영 절차는 [상시 모니터링 문서](docs/ops/continuous-monitoring.md#telegram-thesis-2단계-승인)를 참조한다.
- `get_investment_context`는 읽기 전용이다. Decision append는 위 MCP 도구와 기존 로그인 사용자 REST API 양쪽에서 가능하다. 전술 입력은 기존 로그인 사용자 REST API에 남아 있다. Thesis proposal 저장은 별도의 actor/evidence append-only audit 이력이나 사용자 확인 이벤트를 추가하지 않는다. 실제 예약 세션에서 도구가 보이는지는 별도 검증이 필요하다. 장중 체결 VWAP feed와 Calibration Log도 이 변경에 포함되지 않는다. [컨트롤러 코드](trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextController.java)와 [계약 감사](docs/ops/investment-context-mcp-contract.md)를 참조한다.
- 현재 스테이징의 이미지·readiness·Flyway·저장된 스냅샷 fingerprint를 확인할 때는 GitHub Actions의 수동 [read-only runtime diagnostic](.github/workflows/runtime-readonly-diagnostic.yml)을 사용한다. 범위와 fingerprint 필드는 [CD 런북](docs/ops/backend-cd.md#read-only-runtime-diagnostic)에 있다.

### 문서 갱신 규칙

- Sheets 탭·열·소유권, MCP 도구·입출력·상태, 데이터 출처·계산 의미 또는 갱신 주기가 바뀌면 관련 계약/운영 문서와 함께 같은 PR에서 이 README도 갱신한다.
- 내용을 실제 코드와 대조해 검증한다. 무관한 일반 변경은 이 안내에 덧붙이지 않는다.

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
