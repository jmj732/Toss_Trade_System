# 04. API, MCP, 컨텍스트와 Google Sheets 경계

이 문서는 `main`의 코드·설정이 정의하는 HTTP API, Toss MCP 도구, 저장된 투자 context, Google Sheets 소유권을 정리한다. 애플리케이션 코드의 계약과 특정 배포의 활성 기능은 구분한다. 여기 적힌 MCP 도구가 어느 ChatGPT 연결이나 Scheduled Task에서 실제로 활성화되어 있다는 뜻은 아니다.

## 시스템 경계

```text
ChatGPT / Toss MCP 연결 ── OAuth Bearer ──> Spring Boot API
                                             ├── PostgreSQL: 정식 상태·이력·스냅샷
                                             ├── Toss: 계좌·시세·주문 provider
                                             ├── 내부 Analysis Service: 계산 API
                                             └── 선택적 주기 작업 ──> Google Sheets projection
Browser ── 로그인 access token ─────────────> Spring Boot API
Browser <───────────────────────────────────> Next.js dashboard
```

- `trading-backend`는 사용자 인증, broker 연결, API/MCP, 상태 저장·계산, 주문 경계를 소유한다. `analysis-service`는 버전이 지정된 계산 요청/응답을 받고 계산 결과를 반환하는 내부 FastAPI 서비스다. 기본 Compose 구성은 백엔드가 `analysis:8000`으로 호출하게 하고, 사용자 계정·thesis·결정의 정식 저장소로 쓰지 않는다. 요청/응답 계약은 [`contracts/analysis`](../../contracts/analysis/)와 [Analysis Service 구현](../../analysis-service/app/main.py)에 있다.
- `web-dashboard`는 Spring API를 사용하는 Next.js 사용자 인터페이스다. 자체 정식 투자 저장소나 Toss/MCP provider가 아니다. 이미지 빌드 시 backend URL이 전달된다. [dashboard 구성](../../web-dashboard/package.json), [Compose 구성](../../compose.yaml)
- PostgreSQL은 context가 읽는 canonical 저장소다. Sheets는 일부 계좌 입력을 받고 출력 탭을 투영하는 혼합 문서다. `GET /investment/context`와 `get_investment_context`는 Sheets를 읽거나 갱신하지 않는다.
- MCP 주문 도구는 일반 context와 분리된다. `prepare_order`는 주문 제안·검증 단계이고, `submit_order`만 명시적으로 승인된 제안을 broker로 제출한다. 사용자에게 별도 REST paper/live-order workflow도 있지만 이들 API를 MCP가 대신 호출하지 않는다.

## 인증, HTTP, MCP 프로토콜

### 인증 경계

| 경로 | 인증과 범위 | 비고 |
|---|---|---|
| `/investment/**` | 로그인 사용자 access token | `Principal`의 사용자 ID로 scope를 제한한다. connector API key는 받지 않는다. |
| 대부분의 `/api/**` | 로그인 사용자 access token | 예외는 아래 public/OAuth 및 connector 경로다. |
| `/api/v1/connector/**` | `Authorization: Bearer …` connector key, 최소 `connector:read` | 키는 소유 사용자 및 활성 broker connection에 바인딩된다. `connector:trade`는 키에 저장된 기존 범위다. |
| `/.well-known/**`, `/api/v1/connector/oauth/**` | OAuth discovery/등록/승인·token 교환 경로 | MCP OAuth가 access token을 connector key로 발급한다. 개별 tool 호출은 다시 connector key로 인증된다. |
| `POST /api/v1/telegram/webhook` | Spring 로그인 대신 webhook secret header 검증 | 제한된 webhook 경로다. |
| 기타 dashboard/static 및 health | Spring security 설정 기준 공개 | 노출 가능 여부는 배포 네트워크와 reverse proxy에도 달려 있다. |

Security filter는 access token의 사용자 범위를 검증하고 connector key 요청에는 `SCOPE_CONNECTOR_READ`를 준다. trade-scoped key에만 `SCOPE_CONNECTOR_TRADE`를 더한다. connector key가 누락·만료·폐기되면 `401 CONNECTOR_UNAUTHORIZED`, key rate limit 초과는 `429 CONNECTOR_RATE_LIMITED` 및 `Retry-After`, Redis rate limiter 오류는 `503 CONNECTOR_RATE_LIMIT_UNAVAILABLE`이다. 자세한 매처는 [SecurityConfiguration](../../trading-backend/src/main/java/com/jmj/trade/security/SecurityConfiguration.java), key filter는 [ConnectorApiKeyAuthenticationFilter](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorApiKeyAuthenticationFilter.java)에 있다.

### MCP transport와 응답

- 서버 경로는 `/api/v1/connector/mcp`다. 현 ChatGPT용 streamable HTTP는 JSON-RPC POST를 사용하고, 같은 경로 아래 SSE 호환 transport도 구현돼 있다. SSE session은 프로세스 메모리에 최대 30분 유지되며 해당 인스턴스/인증 사용자·connection에 묶인다. 현재 streamable 요청은 세션 ID 없이 key로 소유 범위를 확인한다. [ConnectorMcpController](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpController.java)
- 처리하는 JSON-RPC method는 `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`이다. 서버는 `2025-06-18`, `2025-03-26`, `2024-11-05`를 협상하고, 그 외 요청은 기본 프로토콜 버전으로 응답한다. 지원 tool과 schema는 서버의 dependency/configuration 및 key scope에 따라 달라질 수 있다.
- Tool 성공 결과는 `result.isError=false`, `structuredContent`, JSON 문자열을 담은 `content[0].text`를 함께 돌려준다. 오류는 `isError=true`, `structuredContent={ok:false,errorCode,retryable,reauthorizationRequired}`와 안전한 텍스트 설명을 돌려준다. JSON-RPC 자체 오류는 표준 오류 객체(`-32600` invalid request, `-32601` method/tool not found, `-32602` invalid params)를 사용한다. 잘못된 JSON body는 HTTP 400 `MALFORMED_MCP_REQUEST`다.
- 도구 입력의 `additionalProperties=false`가 설정된 schema는 선언되지 않은 필드를 허용하지 않는다. schema를 배포한 뒤 ChatGPT 연결 쪽 tool 목록이 갱신됐다고 추정하면 안 된다. 연결 UI에서 실제 도구 검색 결과를 별도로 확인한다.

## MCP 도구 카탈로그

`connector:read`가 있으면 account read 네 개와 context/readiness 도구들이 목록에 나타난다. 선택적 service가 활성화되지 않으면 해당 tool은 목록에 없을 수 있다. trade tool은 `connector:trade` 권한이 있을 때만 공개된다. 이름·입력은 [tool 선언, schema 및 dispatch](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java)를 기준으로 한다.

| 범주 / tool | 입력 계약 | 반환 / 효과 경계 |
|---|---|---|
| Broker 읽기 — `get_portfolio` | `{}` | Toss 계좌·보유·현금의 최신 저장 snapshot. 실제로 read-through 계좌 동기화를 시도하며 성공 시 새 PostgreSQL snapshot을 저장한다. 실패 시 마지막 사용 가능한 snapshot을 stale로 표시해 제공할 수 있다. |
| Broker 읽기 — `get_orders` | `group`: `OPEN` 기본값 또는 `CLOSED` | broker의 열린 또는 종료 주문 목록. Toss 주문 조회 provider 호출. |
| Broker 읽기 — `get_recent_fills` | 선택 `since` ISO-8601 instant | OPEN/CLOSED 주문 snapshot의 양수 누적 `filledQuantity`로 fill view를 만든다. 동일 `brokerOrderId`는 중복 축약하며, 양 그룹에 있으면 주문 identity와 filled quantity 단조성을 확인한 뒤 CLOSED cumulative row를 쓴다(합산하지 않음). 별도 execution fill ledger가 아니며 `since` 지정 시 `filledAt=null`인 항목은 제외한다. Toss 오류는 `TOSS_API_ERROR`와 retryable, snapshot conflict는 `CONNECTOR_ERROR`로 실패한다. 실패를 빈 목록으로 해석하지 않는다. |
| Broker 읽기 — `get_order` | `brokerOrderId` 또는 `clientOrderId` 중 하나 이상 | 주문의 최신 broker 상태. client ID는 소유 사용자·connection 범위 DB mapping으로 broker ID를 찾은 뒤 provider를 조회한다. |
| 저장 context — `get_investment_context` | 인수 생략 또는 선택 `ticker` 하나 | 아래 [Context 계약](#저장된-investment-context)을 반환한다. DB read/계산만 하며 Toss·시장 데이터·Sheets를 조회하거나 refresh하지 않고 쓰기도 하지 않는다. |
| 저장 risk — `get_investment_risk_events` | 선택 `ticker`, `since`, `includeUnresolved`(기본 `true`), `limit`(기본 50, 1–100), opaque `cursor`(최대 512자) | 아래 [risk event 계약](#risk-event-페이지네이션-v62)을 반환한다. 사용자 소유 PostgreSQL 이력을 조회하며 provider 호출/refresh나 저장 변경이 없다. |
| 방어 매도 read — `get_investment_sell_readiness` | 필수 UUID `decisionId`, `eventId` | 이벤트에 연결된 caller-authored `REDUCE`/`EXIT` decision, account-local 수량, OPEN/prepared 중복, Toss order book·미국 calendar gates를 평가한다. typed readiness와 `blockers`를 돌려주며 action/target을 만들거나 저장/주문하지 않는다. 연결된 target contract는 아래 [방어 매도 계약](#방어-매도-준비-v63v64)을 본다. |
| 검증 준비 — `get_investment_thesis_verification_context` | 필수 `ticker` | 현재 thesis revision, 유효 policy, trust 상태가 붙은 가격 view, 최신 verification, 후보 trigger 가격. provider refresh나 변경 없음. |
| 후보 등록 — `get_investment_onboarding_readiness` | 필수 `ticker` | 저장 collection 상태·시각, thesis receipt/적격성, risk mark, 후보와 blocker. 데이터 수집을 시작하거나 투자 승인을 하지 않는다. |
| 사전 위험 — `preflight_investment_risk` | `ticker`, 양수 `quantity` | 서버 선택 risk input으로 현재·제안 수량 게이트를 계산한다. 실행 가격을 받지 않고 주문을 만들거나 승인하지 않는다. |
| 관찰 대상 쓰기 — `register_investment_candidate` | 필수 `ticker` | 사용자 watchlist에 idempotent 등록. 다음 정상 collection 대상만 추가하며 provider를 즉시 호출하지 않는다. Trade scope. |
| 전술 테마 쓰기 — `upsert_investment_theme` | `themeId`, `name`, `ticker`, `effectiveDate` 필수 | append-only PostgreSQL tactical input에 USER_INPUT provenance를 기록하고 파생 Tactical V1 snapshot을 다시 계산한다. thesis/decision/order는 변경하지 않는다. Trade scope. |
| thesis 제안 — `put_investment_thesis` | `ticker`, `thesis` 필수; `expectedUpdatedAt`, `proposalRunId` 선택 | 외부 작성 제안 상태(`AI_PROPOSED`, `UNVERIFIED`, `INVALIDATION_UNDEFINED`)를 저장. 기존 항목 수정은 현재 context의 정확한 `expectedUpdatedAt`이 필요하다. `CONFIRMED` 입력/덮어쓰기는 거부된다. Trade scope. |
| decision — `append_investment_decision` | `decisionId` UUID, `asOf`, `asset`, `action`(`ADD/HOLD/REDUCE/EXIT/REPLACE`), 양수 `referencePrice`, `priceSession`(`REGULAR_CLOSE/LIVE_REGULAR/AFTER_HOURS/PREMARKET`), `horizon`, `alphaThesis`, `invalidation`, `nextReviewTrigger`, `confidence`(0–1); 선택 `riskEventIds`(최대 20 UUID), paired `targetRemainingQuantity` / `targetBrokerConnectionId` | caller-authored 값을 append-only ledger에 저장하고 backend `riskPolicyCheck`를 덧붙인다. target pair는 `REDUCE`/`EXIT` + 단일 risk event에만 허용되며 선택 계좌와 남길 수량을 나타낸다. 서버는 action/target을 고르지 않고 주문을 만들지 않는다. 상세는 [방어 매도 계약](#방어-매도-준비-v63v64). Trade scope. |
| review — `append_investment_review` | 필수 `recordKey`(1–128자), 미래가 아닌 `asOf`, `scope`(`SECURITY`/`PORTFOLIO`); 선택 `asset`, `rawAction`, `outcome`, `rationale`, `nextReviewTrigger`, nullable 양수 `referencePrice`, `rawPriceSession`, `sourceAsOf` | PostgreSQL에 review/audit note만 append한다. raw label은 보존하고 `priceSession`은 정확히 지원되는 값일 때만 파생한다. 동일 `(user, source, recordKey)` 및 body 재전송만 idempotent다. decision/thesis/order는 생성하지 않는다. Trade scope. |
| thesis 검증 — `verify_investment_thesis` | `verificationEventId`, `ticker`, `thesisRevisionId`, `verificationRunId`, `provider`, `model`, `verdict`(`PASS/REJECT`), `sourceAsOf`, HTTPS `sourceUrls`(1–10), `rationale`, `counterevidence`, `selectedTriggerPrice`(양수 또는 null) 모두 필수 | caller가 제출한 검증 주장과 provenance를 PostgreSQL에 보존하고 현재 사용자 policy를 적용한다. `AUTO_APPROVED`, `BLOCKED`, `REJECTED` 결과가 가능하며 policy 통과 시 thesis가 `CONFIRMED`로 전환될 수 있다. 이는 최신 V60 코드의 자동 정책 경로다. 서버는 모델 실행, URL fetch, evidence 진위 확인을 하지 않는다. 주문은 하지 않는다. Trade scope 및 authenticated connector trade key 필요. |
| 주문 workflow — `prepare_order` | `symbol`, `side`(`BUY/SELL`), `orderType`(`LIMIT/MARKET`), 양수 `quantity`; LIMIT에는 양수 `price` 필수, MARKET에는 `price` 허용 안 됨 | 서버 검증·위험 확인 후 제안 ID를 반환. 여기서는 broker에 주문을 제출하지 않는다. Trade scope와 live order service 필요. |
| 주문 실행 — `submit_order`, `cancel_order` | `submit_order`: 승인된 `proposalId` 하나. `cancel_order`: `brokerOrderId` 하나 | 각각 기존 제안 제출 또는 열린 주문 취소를 broker에 요청하며 실제 provider side effect다. `connector:trade`와 `REAL_ORDER_ENABLED=true` 조건이 모두 필요하다. `REAL_ORDER_ENABLED=false`면 도구는 보일 수 있지만 호출이 disabled error로 거부된다. |

### 저장된 Investment Context

MCP의 `get_investment_context` 인수는 선택 `ticker` 한 개뿐이다. 허용 문자 `[A-Za-z0-9._-]`, 최대 32자이며 정확한 일치로 `securities` 배열만 좁힌다. `portfolio`, `watchlist`, 정책, ledger, pipeline, overlays를 ticker 범위로 바꾸지는 않는다. REST counterpart는 로그인한 사용자의 `GET /investment/context`이며 query parameter 없이 전체 view를 반환한다.

Top-level 응답은 다음 레코드들로 구성된다.

| 필드 | 의미 / 경계 |
|---|---|
| `portfolio` | 합산 portfolio snapshot, 계좌 범위, 시각, stale/partial/status, 누락·출처 정보를 포함한다. 값이 없을 수 있다. Toss의 broker 전용 `get_portfolio`와는 다르며 수동 `ACCOUNT_2`가 합산될 수 있다. |
| `securities[]` | 종목별 가격·기술·재무·컨센서스·revision·valuation·readiness, thesis revision/approval provenance, 위험 기여·eligibility와 tactical overlay. 원시 제공자 데이터를 직접 조회해 채우지 않는다. |
| `watchlist[]` | 사용자 관찰종목과 저장된 collection 상태. |
| `riskPolicy` | 현재 사용자 위험 정책과 그 적용 기준. |
| `decisionLedger[]` | 최신 최대 50개 저장된 결정. context는 50개만 포함한다. 별도 `GET /investment/decisions?limit=`는 1–200 범위로 요청 가능하다. |
| `pipeline` | 수집·분석 워크플로우 상태 및 시각. 상태 표시는 수집 실행을 트리거하지 않는다. |
| `tacticalOverlay`, `decisionOverlays` | PostgreSQL tactical input에서 계산된 portfolio/security 및 decision별 overlay. |
| `reviewLog[]` | 최근 최대 50개 review note. review는 decision ledger와 별도 저장된다. |

Context의 부분 데이터도 성공 응답이 될 수 있다. 각 하위 view의 `status`, `stale`, `missingFields`, source/as-of와 reason을 숫자보다 우선해 읽는다. 알 수 없는 값은 `null`/누락 상태로 나타나며 임의로 0 처리하지 않는다. 인증된 `GET` 자체는 provider를 호출하지 않는다. 저장·계산 계약 상세는 [Investment Context MCP 계약](../ops/investment-context-mcp-contract.md), [정식 데이터 출처](../ops/canonical-investment-data.md), [context API 계약](../ops/investment-context-contract.md)에 있다.

### Risk event 페이지네이션 (V62)

`get_investment_risk_events`는 `monitoring_state_history` 및 현재 상태 테이블에서 `scope=THESIS_REVIEW`인 사용자별 전이만 읽는다. `events`는 `(observedAt DESC, id DESC)` 순서로 요청 `limit+1`개를 읽어 한 페이지를 반환한다. 더 있으면 URL-safe Base64 opaque `nextCursor`와 `hasMore=true`를 준다. 이후 페이지는 cursor를 그대로 전달한다. cursor의 관측 시각과 ID는 정렬 tuple을 이어 가는 기준이다.

응답 주요 필드는 `events`, 페이지 내 `latestEventsByTicker`, 별도 bounded `unresolvedEvents`, `nextCursor`, `hasMore`, `asOf`, `status`(`OK/PARTIAL`), `source=SPRING_POSTGRESQL`, `missingFields`다. 기본 `includeUnresolved=true`는 최신 미해결 state(`NEAR`, `INTRADAY_BREACH`, `PRICE_RISK_BREACH`)를 별도로 최대 `limit`건 포함한다. 이 list는 이벤트 페이지 cursor와 별도로 조회되며 별도 page cursor는 없다. event에는 관측 가격·trigger·출처·session·risk mark 상태·thesis revision·상태 전이와 저장 시각이 포함될 수 있다. `missingFields`가 비어 있지 않으면 `PARTIAL`일 수 있다.

각 event에 붙는 `relatedDecisions`는 명시적으로 연결된 최신 최대 5건이고 더 있으면 `relatedDecisionsTruncated=true`다. 이전 decision을 ticker나 날짜로 자동 연관하지 않는다. 연결이 없을 때 `decisionLinkStatus=PRIOR_DECISION_UNAVAILABLE`은 과거에 사람/AI가 판단하지 않았음을 증명하지 않는다. 연결 규칙 및 위험 상태 전이의 의미는 [상시 모니터링 운영 계약](../ops/continuous-monitoring.md), [V62 tool contract](../ops/investment-context-mcp-contract.md#persisted-risk-event-read-tool-v62)을 본다.

### 방어 매도 준비 (V63/V64)

`append_investment_decision`의 선택 `targetRemainingQuantity`와 `targetBrokerConnectionId`는 함께 제공한다. target을 저장하려면 action은 `REDUCE`/`EXIT`, risk event link는 정확히 하나여야 한다. `EXIT` target은 0, `REDUCE`는 양수이며 scale은 최대 6자리다. 연결 ID는 caller 사용자 소유의 active Toss connection이어야 한다. 이는 caller가 쓴 결정 목표다. 시스템은 위험 점수나 심각도로 action/target을 만들어내지 않는다. V63은 기존 decision 행을 수정하지 않고 optional target column을 추가한다.

`get_investment_sell_readiness`는 read scope 도구지만 현재 계좌, Toss `/api/v1/orderbook`, US market calendar를 실제 조회한다. 입력 schema는 `decisionId`와 `eventId`만 요구하고 다른 인수를 거부한다. 출력은 `actionStatus`, `sizingStatus`, `pricingStatus`, `prepareStatus`, 목표/계산 수량, `bid`, `ask`, `limitPrice`, spread, quote `orderBookAsOf`와 provider `orderBookObservedAt`, source/session, price-increment provenance, `blockers`, `asOf`를 갖는 typed readiness DTO다. 상태는 각 경계마다 `READY`/`BLOCKED`(pricing에는 `NOT_EVALUATED` 추가)이고 부족한 단계의 수치 필드는 null일 수 있다. 실패 사유는 `blockers`에 안정된 code로 실린다.

수량은 연결된 현재 decision/event/thesis revision, 신선도, account ownership, 선택 계좌 snapshot의 신선도 및 `sellableQuantity`를 확인한 뒤 `current quantity - targetRemainingQuantity`로 계산한다. 이 계좌별 조회에는 `COMBINED_PARTIAL`인 다른 계좌 노출이 들어가지 않는다. 계산 수량은 양수이고 sellable 이하여야 하며, fractional LIMIT 수량은 반올림 없이 `FRACTIONAL_LIMIT_SELL_UNSUPPORTED`로 막는다. OPEN 및 유효한 PREPARED 주문의 ticker 중복도 검사한다.

Pricing은 provider `TOSS`의 symbol/USD 일치 order book, 양수 bid/ask, quote timestamp와 observation timestamp의 신선도(기본 최대 30초), 조회일 US market calendar, 현재와 quote 시각의 `LIVE_REGULAR`, 설정된 max spread bps, 검증된 ticker별 increment/출처 및 bid 정렬을 요구한다. limit은 bid다. policy나 tick 출처를 알 수 없으면 `MAX_SPREAD_POLICY_UNCONFIGURED` 또는 `PRICE_INCREMENT_UNVERIFIED` 등으로 차단한다. provider contract는 [Toss OpenAPI specification](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)을 참조한다. 코드가 다른 실시간 시세 공급자로 자동 대체하지는 않는다.

`prepare_order`의 기존 fields(`symbol`, `side`, `orderType`, `quantity`, LIMIT의 `price`)는 유지된다. 새 `decisionId`/`eventId`를 하나라도 보내면 둘 다 요구되고, risk-linked 경로에서는 `SELL LIMIT` 및 허용된 fields만 받는다. 준비 경로는 transaction/owned-connection lock 안에서 readiness를 재평가하고 exact ticker·quantity·limit을 비교한 뒤 V64의 order-intent decision/event audit link와 `proposalId`를 생성한다. `submit_order` 직전에도 해당 링크의 readiness를 다시 계산해 target/action, event, thesis revision, 계좌 수량과 가격이 여전히 맞는지 확인한다. `proposalId` 발급은 제출·체결이 아니다. Toss adapter의 생성 요청은 `DAY` time-in-force로 매핑된다. 두 ID를 모두 생략하면 기존 generic risk-unlinked `prepare_order` contract가 유지되고, 이 decision/event audit link를 증명하지 않는다.

현재 저장소는 ChatGPT Task 설정을 읽거나 편집하는 API, prompt/schedule revision을 backend에 보관하는 registry가 없다. `OPENING v1.5` 실행 보고서와 `v1.5.1` 편집 prompt가 실제 현재 task 버전이라는 증거는 없어 `UNVERIFIED`로 남긴다. 외부 run record에는 task ID, `scheduleRevision`, 실제 `promptVersion`/가능하면 hash, `runId`, UTC 시작·종료시각과 ET 변환, connection, tool calls, 각 결과/error/retryability를 남길 것을 권장한다. 이 기록 형식은 운영 권고며 이 repository가 이를 자동 저장한다는 뜻은 아니다. 자세한 구분은 [OPENING 실행 provenance 계약](../ops/investment-context-mcp-contract.md#opening-예약-실행-provenance-p07)을 본다.

## REST API 표면

표는 controller route와 핵심 효과 경계를 요약한다. 요청/응답은 해당 controller의 Java record/DTO이며 인증된 사용자/connection ID는 server principal/path에서 결정된다. 이 표는 OpenAPI 스펙 전체를 대체하지 않는다. connector read REST schema는 [connector OpenAPI](../connector-openapi.yaml)와 [ConnectorController](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorController.java)를, 나머지는 링크한 controller를 본다.

| Route family | 주요 route/method | 인증 / 입력·출력 / provider 효과 |
|---|---|---|
| 로그인 / 세션 | `GET /login`, `GET /api/v1/session`; `POST /api/v1/auth/refresh`, `/logout`, `/logout-all` | OIDC 로그인 후 짧은 access token과 refresh cookie를 사용한다. session은 현재 로그인 사용자. refresh/logout은 cookie/origin 계약을 따른다. [AuthController](../../trading-backend/src/main/java/com/jmj/trade/security/AuthController.java) |
| Broker 연결 / 사용자 API key | `/api/v1/broker-connections`의 create/list/credential update/verify/portfolio/sync/delete; `/api/v1/connector-api-keys` create/list/revoke | 로그인 사용자 및 소유권 검사. Toss 자격 증명은 앱 계층 암호문으로 저장되고 검증·sync route는 provider를 호출할 수 있다. API key 관리 응답은 secret을 재노출하지 않는다. [BrokerConnectionController](../../trading-backend/src/main/java/com/jmj/trade/broker/connection/BrokerConnectionController.java), [ConnectorApiKeyController](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorApiKeyController.java) |
| MCP OAuth / connector REST | OAuth `POST /register`, `GET /authorize`, `POST /token`; connector REST `GET /api/v1/connector/portfolio`, `/portfolio/state`, `/orders`, `/fills`; MCP routes 위 참조 | OAuth metadata/등록은 discovery contract, endpoint의 최종 API access는 user/connection에 bound bearer key. `/portfolio`는 broker sync read-through 가능, `/portfolio/state`도 current portfolio와 open orders를 함께 읽는다. [OAuth controller](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpOAuthController.java) |
| Broker market / account data | `GET /api/v1/broker-connections/{connectionId}/buying-power`, `/account-identity`, `/prices`, `/sellable-quantity`, `/orderbook`, `/candles`, `/exchange-rate`, `/market-calendar/{market}`, `/rankings` | 로그인 사용자와 owned connection. Toss broker provider를 읽는다. [BrokerSurfaceController](../../trading-backend/src/main/java/com/jmj/trade/account/BrokerSurfaceController.java) |
| Dashboard / portfolio history | `GET /api/v1/broker-connections/{connectionId}/dashboard`, `/portfolio-history`, `/portfolio/state` | 저장 snapshot/read model. Dashboard query와 history는 backend에 구현되어 있다. `portfolio/state`는 broker portfolio + open order read를 best effort로 구성할 수 있다. [DashboardReadModelController](../../trading-backend/src/main/java/com/jmj/trade/dashboard/DashboardReadModelController.java), [PortfolioHistoryController](../../trading-backend/src/main/java/com/jmj/trade/account/PortfolioHistoryController.java) |
| Investment context, thesis, review | `/investment/context`; `/investment/securities/{ticker}/thesis`, `/thesis/revisions`, `/thesis/verification-context`; `/investment/decisions`; `/investment/reviews`; `/investment/thesis/ai-policy` | 로그인 사용자, PostgreSQL read/write. input validation·revision timestamp·idempotency 충돌을 typed error로 응답한다. `PUT thesis`는 승인 actor/tracking 정보가 있는 별도 계약이며 MCP proposal 제한과 혼동하지 않는다. [InvestmentContextController](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextController.java), [InvestmentReviewController](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentReviewController.java) |
| Tactical input / onboarding / risk | `/investment/tactical-overlay/inputs`, `PUT/DELETE /investment/securities/{ticker}/avwap-anchors`, `/investment/tactical-overlay/themes…`, `/investment/securities/{ticker}/performance-entries…`; `/investment/onboarding/readiness`, `/register`; `POST /investment/risk/preflight` | 로그인 사용자. tactical input routes는 PostgreSQL append-only inputs와 derived snapshot에 쓰며, readiness/preflight는 저장된 data 기반 조회/계산이다. [InvestmentContextController](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextController.java), [InvestmentOnboardingController](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentOnboardingController.java), [InvestmentRiskPreflightController](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentRiskPreflightController.java) |
| Thesis approval | `POST /investment/securities/{ticker}/thesis/approval-requests`; `GET /investment/thesis/approval-requests` | 로그인 사용자 thesis approval request 기록/조회. Telegram 2단계 approval은 별도 webhook workflow다. [ThesisApprovalRequestController](../../trading-backend/src/main/java/com/jmj/trade/investment/ThesisApprovalRequestController.java) |
| Monitoring / notifications / risk policy | `/api/v1/monitoring/watchlist`, `/positions/{symbol}/context`; `/api/v1/notifications`, `/unread-count`, `/{id}/read`; `/api/v1/risk-policy` 및 `/history` | 로그인 사용자. watchlist/context·정책 변경은 PostgreSQL; monitoring context는 read path. 알림 읽음 처리는 사용자 범위 DB update. [MonitoringController](../../trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringController.java), [NotificationController](../../trading-backend/src/main/java/com/jmj/trade/notification/NotificationController.java), [RiskPolicyController](../../trading-backend/src/main/java/com/jmj/trade/risk/RiskPolicyController.java) |
| 수집/분석/forecast | `/api/v1/stock-analyses/{symbol}`와 `/history`, `/runs/{runId}`; broker connection 아래 portfolio analyses, analysis-predictions; `/api/v1/stock-forecasts/{symbol}`, `/stock-analysis-explanations/{symbol}`, `/prediction-operations`, model versions | 로그인 사용자와 broker connection 소유권. `POST`는 provider/analysis workflow, 저장, 또는 prediction write를 시작할 수 있다. 각 POST를 단순 read로 취급하지 않는다. [StockAnalysisWorkflowController](../../trading-backend/src/main/java/com/jmj/trade/analysis/StockAnalysisWorkflowController.java), [PortfolioAnalysisWorkflowController](../../trading-backend/src/main/java/com/jmj/trade/analysis/PortfolioAnalysisWorkflowController.java), [StockForecastController](../../trading-backend/src/main/java/com/jmj/trade/prediction/StockForecastController.java) |
| 이벤트 / prediction ingestion | connection 아래 `/events`의 POST/GET/detail/reanalyze/comparison/review; `/api/v1/broker-connections/{id}/analysis-predictions` 단건/배치/read; `/api/v1/prediction-ingestion-api-keys` manage | 로그인 사용자 경로는 소유 connection을 확인한다. reanalyze/ingest는 외부 provider 또는 저장 workflow를 실행할 수 있다. API key로 인증하는 전용 ingestion 경로와 사용자 key 관리 경로를 구별한다. [EventIntelligenceController](../../trading-backend/src/main/java/com/jmj/trade/intelligence/EventIntelligenceController.java), [AnalysisPredictionController](../../trading-backend/src/main/java/com/jmj/trade/prediction/AnalysisPredictionController.java) |
| Order systems | `/api/v1/paper-orders` create/preview/read/approval/cancel/withdraw; `/api/v1/live-orders` approve/step-up/dispatch/cancel/modify; `/api/v1/trading/kill-switch`, `/order-reconciliation`, `/live-order-canary` | 로그인·소유권·step-up·risk/kill switch 검증. paper routes는 paper workflow, live dispatch/modification/cancel은 실제 주문 경로에 도달할 수 있다. 별도 `REAL_ORDER_ENABLED`, user confirmation, risk check가 적용된다. MCP 주문 tool은 이 route 그룹을 직접 노출하지 않고 `McpOrderExecutionService` boundary를 따른다. [PaperOrderWorkflowController](../../trading-backend/src/main/java/com/jmj/trade/order/PaperOrderWorkflowController.java), [LiveOrderActivationController](../../trading-backend/src/main/java/com/jmj/trade/order/LiveOrderActivationController.java), [MCP execution service](../../trading-backend/src/main/java/com/jmj/trade/order/McpOrderExecutionService.java) |
| Sheets sync / readiness | `POST /api/v1/investment-os/sheet-sync`; `GET/POST /api/v1/operations/readiness` | Sheets sync는 로그인 사용자 endpoint이자 provider/Sheet write side effect다. readiness는 사용자별 stored data check; `provider-check`는 provider를 실제 호출한다. 일반 MCP context read와 구별한다. [InvestmentOsSheetController](../../trading-backend/src/main/java/com/jmj/trade/sheets/InvestmentOsSheetController.java), [OperationalReadinessController](../../trading-backend/src/main/java/com/jmj/trade/observability/OperationalReadinessController.java) |

Spring request error JSON은 API별 public `code`로 정규화된다. 전형적으로 malformed/invalid input은 400 또는 `422`, 인증 실패 401, 소유권/범위 거부 403, 미발견 404, optimistic conflict 409, provider/state 일시 이용 불가 503다. 실제 status와 code는 해당 controller의 `@ExceptionHandler`를 확인한다. Connector REST는 세부 provider 예외를 `CONNECTOR_UNAVAILABLE` 등 안정된 public code로 축약하고, MCP는 `TOSS_API_ERROR` 및 `retryable` 필드를 사용한다. [connector error mapping](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorController.java), [MCP error mapping](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java)

## Google Sheets와 PostgreSQL 열 소유권

현재 sync source에는 계좌·운영 탭 7개와 연구 projection 8개가 선언돼 있다. 사람은 **계좌 입력 영역**을 관리하고, backend는 계좌 projection 및 research mirror를 갱신한다. Sheets research tab을 canonical 투자 입력으로 읽어 들이는 일반 write route는 없다. 구체적인 cell/header contract는 [Sheet sync runbook](../ops/investment-os-sheet-sync.md), [ResearchSheetSync](../../trading-backend/src/main/java/com/jmj/trade/sheets/InvestmentOsResearchSheetSync.java), [Sheet model](../../trading-backend/src/main/java/com/jmj/trade/sheets/InvestmentOsSheetModel.java)를 본다.

| 탭 | 사람이 소유하는 입력 | backend가 쓰거나 다시 계산하는 부분 |
|---|---|---|
| `Account State` | `ACCOUNT_2`의 수량, 평균원가, 수동 원본/날짜 (`asOf`; 유효한 값이 없으면 계약에 따라 `Synced At`) | `ACCOUNT_1` 계좌/보유/현금 입력; 두 계좌 보유의 현재 시세·시세 출처·`Price Synced At`·market value. 사용자 수량/원가/수동 원본/`asOf`는 자동 갱신 가격과 다른 소유 영역이다. |
| `Account Registry` | `ACCOUNT_2` 수동 레코드 `Sync Mode=MANUAL`, `Enabled=TRUE` 승인/관리 | `ACCOUNT_1`의 마지막 sync 상태/시각. 수동 계좌가 정확히 한 개의 enabled manual row로 확인되지 않으면 합산 snapshot 갱신을 실패 처리하고 직전 accepted snapshot을 보존한다. |
| `Portfolio Aggregate` | 없음 — 출력 | 합산 계좌·보유·원가·market value/source 범위. 유효 입력이 있을 때 계산한다. |
| `Portfolio Metrics` | 없음 — 출력 | 계좌별·합산 평가액, USD cash, weight, HWM/drawdown 등 파생 값. 검증 FX가 없으므로 KRW cash를 USD로 바꾸지 않는다. |
| `Orders`, `Order History` | 없음 — broker projection | Toss 열린 주문 및 종료 주문. |
| `Reconciliation Log` | 사람이 canonical 원본을 입력하는 곳이 아님 | sync 시도·성공/실패·coverage·불일치 행을 기록/갱신한다. |
| `Security Snapshot` | 없음 — 연구/context 미러 | PostgreSQL context로부터 가격·기술·재무·컨센서스·thesis·risk·readiness·tactical 값을 투영한다. 사용자 cell edit를 backend 입력으로 사용하지 않는다. |
| `Thesis State` | 없음 — 현 thesis의 mirror | 저장된 thesis/current approval provenance를 투영. canonical leading header가 호환되지 않으면 기존 tab/archive를 보존하고 mirror를 건너뛴다. 호환된 trailing extension 영역은 existing row 위치/수동 열 보존 규칙을 따른다. |
| `Consensus History`, `Watchlist`, `Alpha State`, `Risk Policy` | 없음 — 저장된 DB view | 각각 저장된 컨센서스 이력, watchlist, alpha 상태, risk policy/mirror. Sheet 편집은 이 영역의 database 값을 바꾸지 않는다. |
| `Decision Ledger` | 없음 — append-only DB ledger mirror | 저장 결정과 정책 점검·overlay. 호환된 trailing manual columns는 보존/기록되지만 결정 생성은 `/investment/decisions` 또는 MCP에서만 한다. 선행 header가 맞지 않으면 탭을 보호한다. |
| `Review Log` | 없음 — review record mirror | V58 user-scoped append-only review records. `Decision Ledger`와 분리된 tab이며 Sheet row는 import/write source가 아니다. |

시트 갱신은 선택적 scheduler/API 경로다. service account는 해당 spreadsheet에 Editor로 공유되어야 하고 Google Sheets API도 활성화돼야 한다. 합산 계좌 snapshot은 DB에 저장된다. 실패하거나 malformed한 `ACCOUNT_2` read는 이전 accepted combined snapshot을 유지하고 `ACCOUNT_1` 단독으로 조용히 낮추지 않는다. quote 입력 일부가 불완전하면 기존 가격/aggregate를 보존하고 새로 계산 가능한 결과만 갱신한다. [Sheet 운영 상세](../ops/investment-os-sheet-sync.md)

## ChatGPT Scheduled Task 계약과 실제 설정의 차이

Repository에는 prompt-contract 문서와 예시 prompt가 있고, MCP server에는 OAuth scope 및 read/write 도구가 구현돼 있다. 하지만 repo에는 ChatGPT에서 Scheduled Task를 생성하거나 편집하는 API, 작업 ID, 실행 시간, prompt, app selection, connection scope 상태를 조회하는 코드/설정이 없다.

- README/connector runbook의 prompt 예제는 사람에게 보여주는 예시다. 실제 task 저장 설정이나 실제 run의 증거가 아니다.
- 기존 [connector runbook](../ops/connector-runbook.md)은 task에 read scope를 사용하라는 운영 지침을 적고 있으나, 이것은 ChatGPT task 설정의 저장 증거나 backend enforced rule이 아니다. 현재 MCP 서버는 trade scope에 thesis proposal/review/decision/theme/verification write 도구를 구현한다. 특히 V60 `verify_investment_thesis`는 user policy가 통과하면 thesis를 자동 `CONFIRMED`할 수 있다. 따라서 과거 runbook의 "CONFIRMED는 사람의 최종 승인만" 문구는 최신 V60 경계와 맞지 않으며, 실제 write capability는 현재 server code 및 활성 scope로 판정한다.
- Scheduled Task가 특정 Toss MCP 연결을 선택하거나 tool을 호출할 수 있는지는 **실제 해당 계정의 task run이 없으면 미검증**이다. task에 trade scope나 write tool이 있다고 추정하지도, backend가 task write를 전부 불가능하게 막는다고 추정하지도 않는다.
- 새로운 connector tool deploy 후 MCP `tools/list`가 바뀌어도 기존 ChatGPT connection, 이미 저장된 Scheduled Task의 app/tool cache·scope·prompt가 자동 갱신됐다고 단정하지 않는다. connection UI 또는 실행 artifact에서 직접 확인해야 한다.
- task가 실제로 호출 가능한지는 해당 ChatGPT 계정/app, 선택된 connection, 권한, 플랫폼 지원에 달린 외부 앱 설정이다. 이 문서와 README는 현재 사용자 설정을 추론하거나 repo에 없는 task 설정을 만들지 않는다.

참고: [connector operations runbook](../ops/connector-runbook.md), [prompt contract](../superpowers/specs/2026-10-08-investment-prompt-contracts.md), [AI verification policy contract](../ops/investment-context-mcp-contract.md#thesis-verification-policy-and-externally-authored-verification-v60).
