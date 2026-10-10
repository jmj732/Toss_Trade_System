# Investment OS 상시 모니터링

이 작업은 계좌와 기존 Risk Policy의 읽기 전용 상태를 분석한다. 주문 API와 예약 LLM 호출은 사용하지 않는다. 위험 상태는 조사 우선순위이며 자동 매매 지시가 아니다.

## 입력과 출처

| 입력 | 기존 원천 | 범위와 주기 |
| --- | --- | --- |
| 보유 수량·평단·평가액 | 성공한 Toss 계좌 스냅샷 | 계좌 동기화 후 갱신. 오래되거나 다른 통화의 환산율이 없으면 합산 비중은 UNKNOWN. |
| 위험 한도 | Spring `risk_policies` | 동일 종목 합산 비중에 `max_concentration` 적용. 섹터·팩터 한도는 현재 정책에 없음. |
| SEC 공시 | 기존 SEC submissions 어댑터 | 보유·watchlist 종목의 신규 공시. CIK는 SEC 공개 `company_tickers.json`에서 자동 해석하며 `identifiers` 설정은 선택적 override로만 쓴다. SEC 공개 API는 키가 없지만 공정 접근 제한을 지켜야 함. |
| IR·정부 발표 | 기존 IR/FED/BLS/BEA 어댑터 | 설정한 공식 피드와 발표만 수집. |
| FRED 거시·금리 | 기존 FRED 어댑터 | 무료 API 키 필요. 원천 관측일 기준으로 평가하며 시장 가격처럼 5분마다 조회하지 않음. |
| 가격·거래량·상대강도·시장 내부 | 구성된 가격 제공자 | 미설정·지연·미제공이면 UNKNOWN. 무료로 제공되지 않는 세부 시장 데이터는 추정하지 않음. |

FRED 매핑에서 `DGS10`은 10년 명목금리, `DFII10`은 10년 실질금리, `T10YIE`는 10년 기대인플레이션 지표다. [HY OAS](https://fred.stlouisfed.org/series/BAMLH0A0HYM2)와 [IG OAS](https://fred.stlouisfed.org/series/BAMLC0A0CM)의 원천 단위는 `%`이므로 계산 엔진에 `bp`로 전달할 때 100을 곱한다. [DTWEXBGS](https://fred.stlouisfed.org/series/DTWEXBGS)는 광의의 무역가중 달러지수이며 DXY와 다른 지표다.

SEC의 [공개 submissions API](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)는 인증 키 없이 이용할 수 있다. 요청 심볼 중 `identifiers`에 매핑이 없는 종목은 SEC 공개 [`company_tickers.json`](https://www.sec.gov/files/company_tickers.json)에서 CIK를 자동 해석한다(24시간 메모리 캐시, 설정된 user-agent 전송, `.`/`-` 클래스주 정규화). 티커맵을 받지 못하면 provider 실패로 기록하되 설정된 identifiers 수집은 계속하고, 미확인 티커는 추정하지 않고 건너뛴다. 티커맵 URL은 `market-events.providers.sec.feed-urls.company-tickers`로 조정할 수 있고 기본값은 www.sec.gov다(submissions는 data.sec.gov). [SEC의 공정 접근 제한](https://www.sec.gov/files/privacy.htm)은 전체 요청량 10회/초 이하를 요구한다. [FRED observations API](https://fred.stlouisfed.org/docs/api/fred/series_observations.html)는 API 키를 요구한다. Telegram 전송은 [공식 Bot API](https://core.telegram.org/bots/api)를 사용한다.

## 운영 원칙

- 설정되지 않은 입력과 관측 시점이 지난 입력은 `UNKNOWN`으로 유지한다. 알 수 없는 데이터만으로 `NORMAL` 복귀나 P0를 기록하지 않는다.
- 취약성 단독 악화와 10Y 명목금리 5.3~5.5% 진입은 자동 매도 신호가 아니다. 실질금리·기대인플레이션·기간프리미엄을 분리해 확인한다.
- 신호는 직전 저장 상태와 비교한다. 같은 상태·같은 공식 이벤트는 반복 통지하지 않는다. 정상 상태의 주기 실행은 조용해야 한다.
- 자동 수집한 원시 공시·FRED 관측값은 일반 알림을 만들지 않는다. 검증된 material event와 위험 상태 전이만 monitoring alert를 만든다.
- Telegram 토큰·채팅 ID는 운영 환경 변수로만 제공한다. 개발·테스트 환경에서는 실제 전송을 켜지 않는다.
- SEC/IR 원문을 읽지 못해 중요한 세부 내용이 확인되지 않으면 자동으로 thesis를 무효화하지 않는다. 공식 자료의 material event는 재검토 대상으로만 표시한다.

## 활성화

Spring과 FastAPI, PostgreSQL을 먼저 실행한다. 기존 market-events 수집기가 공식 이벤트와 FRED 관측값을 `intelligence_events`에 저장한다. 해당 수집기와 모니터링 스케줄러는 별도 설정이며 기본값은 모두 꺼져 있다.

| 환경 변수 | 용도 |
| --- | --- |
| `MARKET_EVENTS_SCHEDULER_ENABLED=true` | 기존 공식 이벤트 수집 스케줄러 |
| `MARKET_EVENTS_SEC_ENABLED=true`, `MARKET_EVENTS_IR_ENABLED=true` | 설정된 SEC CIK·회사 IR 피드 수집 |
| `MARKET_EVENTS_FRED_ENABLED=true` | 설정된 FRED 시계열 수집. FRED 키가 필요하다. |
| `MONITORING_SCHEDULER_ENABLED=true` | 상태 평가 시작. 기본 15분 주기 |
| `MONITORING_PRICE_INTERVAL=PT10M` | 개장 중 가격·watchlist 평가 간격 |
| `MONITORING_MACRO_INTERVAL=PT1H` | 저장된 거시·신용 데이터 재평가 간격 |
| `TELEGRAM_ENABLED=true`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `TELEGRAM_USER_ID` | Telegram 전달. 네 값이 모두 있어야 해당 사용자 알림만 보낸다. |

Telegram 전송 큐는 PostgreSQL에 남고 실패 시 재시도한다. Telegram이 메시지를 받은 직후, DB에 전송 완료를 기록하기 전에 서버가 종료되면 한 번 더 전송될 수 있다. 처음 활성화하기 전에도 설정을 점검한다. 이미 처리한 알림은 Telegram을 나중에 켜도 소급 전송하지 않는다.

인증된 사용자는 `PUT /api/v1/monitoring/watchlist/{symbol}`에 `levels.prepare/confirm/pullback/invalidate`의 `min`·`max`를 저장하고 `GET /api/v1/monitoring/watchlist`로 조회한다. 포지션별 thesis, primary alpha, 섹터·팩터와 add/reduce/exit/invalidation 조건은 `PUT /api/v1/monitoring/positions/{symbol}/context`에 저장한다. 내부 계산 API는 `POST /internal/v1/monitoring/evaluations`이며 영속 상태와 알림은 Spring이 담당한다.

현재 시세 공급자에 지표가 없거나 최근 계좌 스냅샷·환율이 없으면 해당 값과 정책 판단은 `UNKNOWN`으로 남는다. 기존 Risk Policy가 정의한 `max_concentration`만 한도로 적용한다. 섹터·팩터 노출은 계산하지만 정책에 한도가 없어 위반을 임의 생성하지 않는다. SEC/IR 수집은 현재 연결된 브로커 계정의 이벤트 저장 구조를 사용한다. 연결이 없는 watchlist 전용 사용자는 공식 이벤트를 자동 수집할 수 없다.

공식 이벤트는 thesis 재검토를 요청한다. 사건과 사용자가 적은 무효화 조건이 실제로 일치했다는 별도 근거가 없으면 `THESIS_INVALIDATED`를 자동 생성하지 않는다. 시장 내부 지표, CRE, private credit, 기간프리미엄처럼 현재 공급자가 제공하지 않는 값은 엔진에 필드를 남겨 두되 운영 상태에서는 `UNKNOWN`이다.

Watchlist 거래량 배수는 당일 누적 거래량을 직전 20개 완성 거래일의 일간 평균과 비교한다. 시간대별 보정은 없어서 장 초반에는 `PREPARE`·`ACTION_CANDIDATE` 조건이 늦게 충족될 수 있다.

## Telegram thesis 2단계 승인

V59부터 Telegram 인라인 버튼으로 투자 논리(thesis)의 무효화 트리거를 `CONFIRMED`로 승인할 수 있다. 기본값은 꺼져 있고, 켜도 주문은 만들지 않으며 MCP 도구는 여전히 `CONFIRMED`를 쓸 수 없다. 아래 명령은 운영자가 직접 실행할 절차이며 저장소 변경으로 실행된 적은 없다.

### 활성화 조건

| 환경 변수 | 용도 |
| --- | --- |
| `TELEGRAM_ENABLED=true`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `TELEGRAM_USER_ID` | 기존 Telegram 전달 설정. `TELEGRAM_CHAT_ID`는 반드시 숫자 chat id여야 한다(그룹은 `-100…`). `@username`은 거부되어 승인 기능이 꺼진 상태로 남는다. |
| `TELEGRAM_APPROVAL_ENABLED=true` | 승인 기능 스위치. 기본 `false`. |
| `TELEGRAM_APPROVER_ID` | 버튼을 누를 수 있는 Telegram 사용자 숫자 id(`from.id`). |
| `TELEGRAM_WEBHOOK_SECRET` | 선택. `[A-Za-z0-9_-]{1,256}`만 허용하며 형식이 틀리면 모든 웹훅을 거부한다. 비우면 봇 토큰에서 파생한다. |
| `TELEGRAM_APPROVAL_TTL=PT24H`, `TELEGRAM_CONFIRM_TTL=PT5M` | 요청 만료와 최종 승인 대기 시간. |

위 값이 하나라도 없거나 숫자가 아니면 기능은 꺼진 것으로 취급한다. 웹훅은 404, 요청 생성 REST는 409 `THESIS_APPROVAL_NOT_READY`를 돌려준다. 요청은 `TELEGRAM_USER_ID` 사용자 것으로만 만들어진다. 생성 경로는 두 가지다. 로그인 사용자 REST(`POST /investment/securities/{ticker}/thesis/approval-requests`)와 승인자의 Telegram `/review` 명령(아래)이다. 운영 Vercel은 `/investment/...` 사용자 REST를 프록시하지 않으므로 운영에서는 `/review`가 실제 생성 경로다.

### 웹훅 시크릿과 등록

명시 시크릿이 없으면 시크릿은 `hex(HMAC-SHA256(key=봇 토큰, msg="telegram-webhook-v1"))`(소문자 hex 64자)이다. 값은 출력이 셸 기록이나 로그에 남지 않는 환경(예: Doppler 주입 셸)에서 계산한다.

```sh
printf '%s' 'telegram-webhook-v1' | openssl dgst -sha256 -hmac "$TELEGRAM_BOT_TOKEN" | awk '{print $NF}'
```

`setWebhook`로 등록하고 `getWebhookInfo`로 확인한다. `allowed_updates`는 `["callback_query","message"]`다. 버튼만 쓰던 기존 등록(`["callback_query"]`)은 `message`를 받지 않아 `/review`가 동작하지 않으므로 아래 명령으로 `setWebhook`를 다시 실행해야 한다.

```sh
curl -sS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -H 'Content-Type: application/json' \
  -d "{\"url\":\"https://web-dashboard-phi-lac.vercel.app/api/v1/telegram/webhook\",\"secret_token\":\"${WEBHOOK_SECRET}\",\"allowed_updates\":[\"callback_query\",\"message\"]}"
curl -sS "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/getWebhookInfo"
```

`getWebhookInfo`의 `url`, `allowed_updates`, `pending_update_count`, `last_error_message`를 본다. 봇 토큰을 교체하면 파생 시크릿도 바뀌므로 새 시크릿으로 `setWebhook`를 다시 실행한다. 명시 시크릿을 바꿀 때도 같다. 이 URL은 Vercel `web-dashboard`를 거친다. Vercel이 `X-Telegram-Bot-Api-Secret-Token` 헤더와 POST 본문을 백엔드까지 그대로 전달하는지는 아직 검증하지 않았다. `last_error_message`에 401이 보이면 헤더가 전달되지 않은 것이다.

### 웹훅 판정 순서

1. 기능 꺼짐: 404
2. 시크릿 헤더 없음: 401. 불일치(상수 시간 비교)이거나 사용 가능한 시크릿이 없으면 403
3. `callback_query`도 텍스트 `message`도 아니거나 형식이 깨진 update(`update_id`·`chat.id`·`from.id`·`text` 누락 포함): 200, 아무것도 하지 않음
4. `message.chat.id` 또는 `from.id`가 설정과 다름: 200. 상태를 바꾸지 않고 Telegram 호출(answerCallbackQuery·답장 포함)도 하지 않음
5. 명령이 아닌 텍스트나 `/review`·`/pending` 외의 명령: 200, 답장 없음, `update_id`도 기록하지 않음
6. `update_id` 중복 제거와 상태 전이(또는 `/review` 요청 생성). 인증된 정상 update에는 항상 2xx를 돌려준다. 후속 Telegram 호출 실패는 사유 코드만 로그에 남긴다.

### Telegram 명령

승인 채팅에서 승인자만 쓸 수 있다. 그룹에서는 `/review@봇이름` 형식도 받는다(봇 이름은 검사하지 않는다).

| 명령 | 동작 |
| --- | --- |
| `/review TICKER [ATR\|SUPPORT\|PROPOSAL]` | `TELEGRAM_USER_ID` 사용자의 해당 종목 승인 요청을 만들고 [상세 검토]·[승인]·[보류] 버튼 메시지를 보낸다. 출처는 대소문자 무관이며 기본값은 `PROPOSAL`(=`EXISTING_PROPOSAL`, 저장된 제안 트리거)이다. `ATR`=`COMPUTED_ATR`, `SUPPORT`=`COMPUTED_SUPPORT`. 종목은 REST와 같은 정규화(대문자, `[A-Z0-9._-]{1,32}`)를 거친다. |
| `/pending` | 대상 사용자의 열린(`PENDING`·`AWAITING_CONFIRM`, 미만료) 요청을 종목·출처·상태·만료 시각만으로 최대 20건 보여준다. 읽기 전용이다. |

- `/review`는 REST와 같은 서비스 메서드·전제조건으로 요청을 만든다. `expectedThesisUpdatedAt`은 생략한 것과 같아 현재 DB 값을 쓴다. 메시지에서 가격이나 트리거를 받지 않는다. 생성은 thesis를 바꾸지 않으며, `CONFIRMED`는 여전히 [승인] → [최종 승인] 두 단계 버튼으로만 된다. 같은 종목의 열린 요청은 새 요청으로 대체된다.
- 인자가 없거나 많거나, 출처·종목 형식이 틀리면 사용법 한 줄을 답장한다. 생성 거부는 한국어 사유와 오류 코드만 답장한다. 예: `투자 논리(thesis) 없음 (THESIS_APPROVAL_THESIS_NOT_FOUND)`, `후보 UNVERIFIED (THESIS_APPROVAL_CANDIDATE_UNVERIFIED/INSUFFICIENT_HISTORY)`, `이미 같은 무효화 가격으로 CONFIRMED (THESIS_APPROVAL_ALREADY_CONFIRMED)`, `승인 기능 준비 안 됨 (THESIS_APPROVAL_NOT_READY)`. 거부 답장에는 가격·계좌 값이 없다.
- 보안: 버튼과 같은 관문이다. `message.chat.id == TELEGRAM_CHAT_ID`이고 `message.from.id == TELEGRAM_APPROVER_ID`일 때만 처리한다. 그 밖의 발신자는 200을 받고 상태 변화·답장·`update_id` 기록이 모두 없다. 처리하는 명령은 먼저 `telegram_webhook_updates`에 `update_id`를 선점하므로 Telegram이 같은 update를 다시 보내도 요청이 두 번 생기거나 답장이 두 번 가지 않는다. 명령 본문은 로그에 남기지 않는다.

### 상태 머신과 2단계 규칙

`PENDING →[승인]→ AWAITING_CONFIRM →[최종 승인]→ APPROVED`. `PENDING`/`AWAITING_CONFIRM`에서 [보류]/[취소]를 누르면 `HELD`(사유 `CANCELLED`)가 된다. 만료되면 `EXPIRED`, 같은 종목의 새 요청이 생기면 `SUPERSEDED`, 최종 승인 시 thesis가 요청 이후 바뀌었으면 `CONFLICT`, 그 밖의 쓰기 거부와 Telegram 전송 실패는 `FAILED`다. `CONFIRMED`로 가는 모든 전이는 중요 승인이므로 항상 두 단계를 거친다. [승인]만 누르면 thesis는 바뀌지 않는다. 최종 승인은 thesis 상태를 `CONFIRMED`로, 트리거를 요청 행의 값으로 바꾸며 나머지 필드와 `priceRiskTrigger` 문구는 그대로 둔다. 이 때문에 숫자와 문구가 다를 수 있다. revision에는 actor `TELEGRAM`, 요청 `sourceAsOf`, 사유 `TELEGRAM_APPROVAL request=<id>`가 남는다.

모든 전이는 한 트랜잭션에서 처리한다. 순서는 요청 소유자 `users` 행 잠금, `telegram_webhook_updates`에 `update_id` 선점(`ON CONFLICT DO NOTHING`), 조건부 `UPDATE … RETURNING`이다. 요청 생성도 같은 사용자 행을 먼저 잠근다. 버튼 토큰은 32바이트 난수(base64url 43자)이며 DB에는 SHA-256 hex만 저장한다. 1단계 토큰은 [상세 검토]·[승인]·[보류]가 공유하고, 2단계 토큰은 [최종 승인]·[취소] 전용이다. `callback_data`는 `<동작 1자>:<토큰>`으로 45바이트다. [상세 검토]는 읽기 전용이고 토큰을 소모하지 않는다. 결정이 나면 키보드를 제거한다. 메시지는 `parse_mode` 없이 4096자 이내로 보내고, 위험은 비율(%)로만 표시하며 계좌 금액·수량은 넣지 않는다. 최종 승인 후에는 context를 다시 읽어 `sizingEligible`, `eligibilityReasons`, 무효화 하락폭, 계획 손실 기여를 보낸다. 보유·관심 대상이 아니면 `리스크 미산출(보유·관심 대상 아님)`으로 표시한다.

### 후보 계산

후보는 저장된 일봉만 사용하고 외부 데이터를 가져오지 않는다. `bar_date < 오늘(America/New_York)`인 완료 봉만 쓰며 가격은 수정주가가 아니다(`UNADJUSTED`).

- `COMPUTED_ATR`: 최근 완료 봉 60개 창을 쓴다. `TR_i = max(H_i−L_i, |H_i−C_{i−1}|, |L_i−C_{i−1}|)`(두 번째 봉부터 계산)이다. 첫 14개 TR의 단순평균을 시드로 쓰고 이후 `ATR_t = (13·ATR_{t−1} + TR_t)/14`로 갱신한다. 봉이 15개 미만이면 `INSUFFICIENT_HISTORY`다. 후보는 `lastClose − 2·ATR`이며 DECIMAL128로 계산한 뒤 마지막에만 `setScale(4, FLOOR)`한다.
- `COMPUTED_SUPPORT`: 최근 완료 봉 20개의 최저 저가다. 20개 미만이면 `INSUFFICIENT_HISTORY`다.
- 가격 신선도는 최신 저장 스냅샷을 다시 판정한(refresh) 값으로 확인한다. 현재가(quote) 상태가 `OK`이고 기준 시각이 있으면 이를 근거로 쓴다(`priceFreshnessBasis=QUOTE`, 가격 기준일 = quote 기준 시각의 뉴욕 날짜). 그렇지 않으면 같은 스냅샷에서 따로 판정한 정규장 종가 상태(`regularCloseStatus`)가 `OK`이고 세션 날짜가 있을 때 이를 근거로 쓴다(`priceFreshnessBasis=REGULAR_CLOSE`, 가격 기준일 = 정규장 종가 세션 날짜). 그래서 정규장 외 시간·주말에 quote가 `STALE`이어도 직전 정규장 종가가 유효하면 후보를 만든다. 정규장 종가는 신선도 확인에만 쓰며 quote나 현재가로 복사하지 않고, 후보 계산은 언제나 저장된 완료 봉(`lastClose`도 봉 종가)으로 한다. inputs에는 `priceFreshnessBasis`와 `priceStatus`·`regularCloseStatus`·`regularCloseSessionDate`가 함께 남는다. 리스크 엔진·가정 리스크·`sizingEligible`의 가격 판정은 여전히 quote 기준이다.
- 다음 경우에는 후보를 만들지 않고 `UNVERIFIED`로 둔다. 창 안에 소스 충돌 봉이 있으면 `SOURCE_CONFLICT`다. quote와 정규장 종가가 모두 신선도를 통과하지 못하면 quote 상태로 사유를 나눈다. `STALE`이면 `PRICE_STALE`, `SOURCE_CONFLICT`면 `PRICE_SOURCE_CONFLICT`, `DATA_MISSING`이거나 가격이 없으면 `PRICE_MISSING`, 그 밖(`UNVERIFIED`, 기준 시각 없는 `OK` 등)은 `PRICE_UNVERIFIED`다. 마지막 완료 봉이 가격 기준일보다 4일 넘게 오래되면 `STALE_BARS`다. 이 4일은 주말·휴일을 감안한 경험값이며 거래소 달력이 아니다. 창 봉에 저장 시각(`captured_at`)이 하나도 없으면 `SOURCE_AS_OF_MISSING`, 그 최댓값이 현재보다 미래면 `SOURCE_AS_OF_IN_FUTURE`다. 후보가 0 이하이거나 `lastClose` 이상이면 `CANDIDATE_OUT_OF_RANGE`다.
- 계산 후보의 `sourceAsOf`는 창 봉들의 저장 시각 최댓값이다(`sourceAsOfBasis=MAX_BAR_CAPTURED_AT`). 저장 시각은 `investment_tactical_overlay_bar_snapshots.captured_at`, 즉 그 봉 값이 수집 실행에서 처음 관측·저장된 시각이며, 같은 값의 재수집은 행을 새로 만들지 않으므로 갱신되지 않는다. 봉의 `source_as_of`는 Toss가 붙인 거래일 라벨(뉴욕 자정, 예: `2026-10-09T04:00:00Z`)이라 장 시작 전 시각이 되므로 쓰지 않는다. 거래일 라벨은 inputs의 `windowStart`·`windowEnd`로 따로 남는다. 최댓값이므로 창 안의 오래된 봉이 최근에 정정·재수집되면 마지막 봉의 저장 시각보다 늦어질 수 있다. 이 값이 요청 행 `source_as_of`와 최종 승인 revision의 `sourceAsOf`에 그대로 기록된다. `EXISTING_PROPOSAL`은 종전대로 제안 기록 시각(`PROPOSAL_RECORDED_AT`)이다.
- 가정 리스크는 thesis를 메모리에서만 `CONFIRMED`+후보 트리거로 바꾼 사본으로 기존 위험 계산(`riskContributions`, 같은 soft budget과 사유)을 다시 실행한 값이다. 저장하지 않는다.

## 보유 종목 CONFIRMED thesis 무효화 가격 재검토 알림

### 역할 분리

| 테이블 | 역할 | 이 기능에서의 사용 |
| --- | --- | --- |
| `investment_thesis_states` (V52, V56, V57) | canonical thesis. 상태(`AI_PROPOSED`/`UNVERIFIED`/`INVALIDATION_UNDEFINED`/`CONFIRMED` 등)와 숫자 무효화 트리거 `price_risk_trigger_price`. 승인은 사용자 REST 또는 Telegram 2단계 최종 승인으로만 가능하고 모든 쓰기는 `investment_thesis_revisions`에 남는다. | **무효화 가격의 유일한 기준.** `CONFIRMED` 행의 양수 트리거만 읽는다. |
| `monitoring_position_contexts` (V47) | 모니터링 메타데이터. 섹터·팩터·베타·상관, 자유 텍스트 `thesis`·`primary_alpha`·`conditions.{add,reduce,exit,invalidation}`. FastAPI 평가기 입력으로 쓰인다. | 사용하지 않는다. 기존 API와 데이터는 그대로 둔다(마이그레이션·삭제 없음). |

### 탐지 규칙

모니터링 주기마다 사용자별로 다음을 실행한다. 평가 입력 fingerprint 중복 생략과 FastAPI 평가기 호출 **이전**에 독립적으로 실행하므로, thesis 승인이나 가격 변화가 fingerprint에 없어도, 평가기가 실패해도 탐지가 빠지지 않는다. 탐지 실패는 경고 로그만 남기고 기존 위험 평가는 계속한다.

1. **보유 판정**: 이번 주기의 최신 보유 스냅샷(`monitoring.portfolio.max-age`, 기본 15분 이내)에서 수량이 양수인 종목만 대상이다. 스냅샷이 오래되었거나 없으면 대상이 없고 알림도 없다. 이 15분 기준은 바꾸지 않았다. 시트 동기화가 마지막 선언 구간 종료 뒤에 한 번 만드는 계좌 스냅샷([시트 동기화 문서](investment-os-sheet-sync.md))도 다른 스냅샷처럼 15분 동안만 보유 판정에 쓰인다. 가격 검증(아래 3) 역시 그대로이므로 장외 시세로는 알림을 만들지 않는다. **미보유 종목은 `CONFIRMED` thesis가 있어도 알림을 만들지 않는다**(자본이 노출되지 않음, investment context의 `INVALIDATION_PRICE_BREACHED` 사유로는 계속 보인다).
2. **thesis**: `investment_thesis_states.invalidation_status = 'CONFIRMED'`이고 `price_risk_trigger_price > 0`인 행만 본다. `AI_PROPOSED`·`UNVERIFIED`·`INVALIDATION_UNDEFINED` 등 미승인 트리거는 가격이 아무리 낮아도 BREACH로 취급하지 않는다.
3. **가격 검증**: investment context 읽기 경로와 같은 freshness 재판정을 거친 최신 `investment_security_snapshots` 가격만 쓴다. 신뢰 조건은 위험 엔진과 같다(상태 `OK`, 기준 시각 있음, 가격 양수). 여기에 정규장 가격(`LIVE_REGULAR`, `REGULAR_CLOSE`)만 허용한다. `STALE`·`UNVERIFIED`·`SOURCE_CONFLICT`·`DATA_MISSING`, 세션 미분류, 프리·애프터마켓 가격은 건너뛰고 알림을 만들지 않는다. 계좌 평가 단가로 대체하지 않는다. 가격 스냅샷은 investment data 스케줄러(`INVESTMENT_DATA_SCHEDULER_ENABLED`, 장중 기본 5분)가 갱신하므로 이 스케줄러가 꺼져 있으면 가격이 곧 `STALE`이 되어 알림이 나오지 않는다.
4. **수준**: `가격 ≤ 트리거`이면 `BREACH`, `트리거 < 가격 ≤ 트리거 × 1.03`(트리거 위 3% 이내)이면 `NEAR`. 그 위는 알림 없음.

### 알림과 중복 제거

- 기존 `notification_outbox_events`에 `MONITORING_ALERT`로 기록하므로 알림 센터와 Telegram 전달 경로를 그대로 탄다. payload는 `scope=THESIS_REVIEW`, `alertType=REVIEW`, 종목, 수준, 트리거 대비 거리 %(`distancePct`, 부호 포함 소수 2자리), 가격의 뉴욕 세션 날짜, `thesisStatusChanged=false`, `orderAction=NONE`만 담는다. **가격·트리거 원값은 payload와 메시지에 넣지 않는다.**
- 메시지: 제목 `[THESIS REVIEW] {종목} {수준}`, 본문 `투자 논리 재검토 필요 — 자동 무효화·주문 없음` / 대상 / 수준 / 무효화 기준 대비 거리 %.
- 중복 제거: `사용자 + 종목 + 트리거 + 수준 + 가격의 뉴욕 세션 날짜`로 결정적 `source_id`(UUID v3)를 만들고 outbox의 기존 `ON CONFLICT (event_type, source_id) DO NOTHING`에 맡긴다. 같은 세션에서 반복 주기는 알림을 다시 만들지 않는다. 같은 날 `NEAR` 다음 `BREACH`는 수준이 달라 각각 한 번씩 알린다. 사용자가 트리거를 바꾸면 새 키가 된다. 정규장 종가가 주말 동안 `OK`로 남아도 세션 날짜가 같으므로 다시 알리지 않는다. 마이그레이션은 추가하지 않는다(`event_type`에는 CHECK 제약이 없다).

### 자동 무효화·주문 없음

- 이 탐지는 thesis 상태나 트리거를 바꾸지 않고 `investment_thesis_states`·`investment_thesis_revisions`에 쓰지 않는다. 주문을 생성·제출·취소하지 않는다.
- **가격 하락만으로 thesis를 무효화하지 않는다.** 알림은 사용자가 thesis를 다시 검토하라는 요청이다. 상태 변경은 사용자 승인 경로(`PUT /investment/securities/{ticker}/thesis` 또는 Telegram 2단계 최종 승인)로만 한다.

### 데이터 일관성 점검 결과(구조 분석, 데이터 이전 없음)

- `monitoring_position_contexts.thesis`와 `investment_thesis_states.core_thesis`는 서로 동기화되지 않는 별개의 자유 텍스트다. 같은 종목에서 내용이 다를 수 있다. 무효화 가격 판단에는 canonical 숫자 트리거만 쓴다.
- `monitoring_position_contexts.conditions.invalidation`은 자유 텍스트이며 숫자 트리거와 다른 가격 수준을 적을 수 있다. 이 문장은 트리거 판정에 쓰지 않는다.
- Spring은 FastAPI 평가기에 `invalidationEvidence`를 항상 빈 목록으로 보낸다. 평가기는 `PRICE` 종류 근거를 무효화에서 제외한다. 따라서 현재 운영 경로에서 PORTFOLIO 상태 `THESIS_INVALIDATED`는 만들어지지 않는다. 만들어지더라도 모니터링 상태일 뿐이며 `investment_thesis_states`는 바뀌지 않는다.
- `monitoring_watchlist.levels.invalidate`와 watchlist 상태 `INVALIDATED`는 진입 셋업용 가격대 상태다. thesis 상태가 아니며 thesis 트리거와도 무관하다.

## 확인

로컬 fake data 테스트는 취약성 단독, 전염축 동시 악화, 금융기관 사고와 강제 디레버리징, 정책 위반, material event, watchlist 상태 전이, 반복 실행 중복 제거를 포함한다. 실제 API 운영 전에는 사용 계정의 CIK·피드·시장 지표 매핑과 관측 지연을 확인한다.
