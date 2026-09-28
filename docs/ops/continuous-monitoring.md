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

## 확인

로컬 fake data 테스트는 취약성 단독, 전염축 동시 악화, 금융기관 사고와 강제 디레버리징, 정책 위반, material event, watchlist 상태 전이, 반복 실행 중복 제거를 포함한다. 실제 API 운영 전에는 사용 계정의 CIK·피드·시장 지표 매핑과 관측 지연을 확인한다.
