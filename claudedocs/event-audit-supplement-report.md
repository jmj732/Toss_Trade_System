# 실적 이벤트 데이터 감사 보완 결과

감사일: 2026-11-24
기준: [`event-audit-supplement-protocol.md`](event-audit-supplement-protocol.md)
선행 결과: [`event-audit-report.md`](event-audit-report.md)

> 본 문서는 기존에 이미 확인한 SEC·IR·가격 구간의 보완 감사다. 독립 수익률 검증이 아니며, 기존 59개 실험을 변경하지 않는다.

## 1. 최종 결론

**전체 유니버스 확장을 제안하지 않는다.**

보수적 SEC 관측 규칙을 적용하면 데이터에는 다음 결함이 남는다.

1. SEC 원자료에 사건별 실제 `observed_at`이 없다.
2. GAAP 희석 EPS와 전년 동기 EPS는 원문에서 추출 가능하지만, 후속 XBRL 교차검증은 17/20에 한정된다.
3. 추가 공시 5건 중 CAT·NKE는 별도 가격정보 또는 기업사건이어서 순수 실적 사건으로 합치면 안 된다.
4. Toss 공식 수수료 일정은 확인했지만, 계좌별 적용률과 기존 체결 내역은 확인하지 못했다.
5. 5거래일 군집 보정 후 계획상 유효 독립 사건 수가 약 1,090건으로, 예시 검정력 기준 1,100건보다 작다.

판정: **`HOLD_FOR_DATA_FIX`보다 강한 보류 — 전체 확장 금지.** 수익률·백테스트·주문 연결은 실행하지 않았다.

## 2. 정보 수집 지연과 첫 실행 가능한 시가

### 보수 정의

```text
t_ready = max(SEC acceptanceDateTime, collector observed_at)
```

- `t_ready < 09:30 ET`이고 `observed_at`이 실제로 그 전에 기록된 경우에만 당일 시가를 인정한다.
- `09:30 ET <= t_ready`면 다음 거래일 시가다.
- `observed_at`이 없으면 당일 시가를 인정하지 않는다. 보수적 기록 가능 시점은 다음 거래일 시가다.

### 현재 자료 결과

- SEC `manifest.json`에는 전체 수집 배치 시각 `collected_at = 2026-11-24T05:05:12Z`만 있다.
- 사건별 SEC 응답을 처음 관측한 시각은 없다.
- 기존 `sec-ir-audit.json`에도 `acceptanceDateTime`은 있지만 사건별 `observed_at`은 없다.
- 따라서 기존 20개 사건에서 같은 날 시가를 확정할 수 없다.
- 다음 거래일 `open` 행은 20/20 존재한다. **보수 규칙상 20/20을 다음 거래일 시가로만 기록**한다.

결론: SEC 접수시각만으로 “그날 아침 투자자가 이미 알 수 있었다”고 추정하면 안 된다. 앞으로 전진 기록을 하려면 SEC 응답 수신 직후 `observed_at`을 서버 시계(UTC)로 원자적으로 저장해야 한다. 이 기록 없이는 당일 시가 사건을 만들지 않는다.

## 3. GAAP 희석 EPS·전년 동기 EPS 정확성

### 추출 규칙

- SEC Exhibit 99.1 및 첨부 실적 보도자료의 표 헤더까지 읽는다.
- `EarningsPerShareDiluted` 또는 동등한 `diluted loss/profit per share`만 GAAP 값으로 선택한다.
- 조정 EPS·가이던스·분기 누적 EPS·기본 EPS를 희석 분기 EPS로 대체하지 않는다.
- 표의 열 순서를 먼저 확인한다. 숫자 중 첫 번째 숫자를 현재 값으로 고정하지 않는다.
- 음수 괄호 표기 `($0.26)`는 `-0.26`으로 정규화하되, 원문 표기를 보존한다.

### 결과

- **20/20:** 원문에서 현재 분기 GAAP 희석 EPS와 전년 동기 GAAP 희석 EPS 쌍을 확인할 수 있었다.
- **17/20:** 사건 뒤 제출된 10-Q/10-K의 `us-gaap:EarningsPerShareDiluted`와 현재·전년 값이 일치했다.
- **3/20:** 저장된 SEC 자료에 사건 뒤 분기 XBRL 교차검증용 제출이 없거나 분기값이 없었다.
  - AAPL: 후속 10-K에는 연간 EPS만 있어 해당 분기 XBRL 교차검증 불가
  - NVDA: 사건 뒤 10-Q가 현재 raw panel에 없음
  - WMT: 사건 뒤 10-Q가 현재 raw panel에 없음
- 17개 교차검증 값 중 BA는 원문 `($0.26), ($7.14)`와 XBRL `-0.26, -7.14`의 부호 표기 차이를 정규화한 뒤 일치했다.

### 표 헤더 오류 위험 사례

- **AMZN:** current 1.89, prior 1.95. 보도자료 표 열 순서를 읽지 않으면 1.95를 current로 잘못 선택할 수 있다.
- **JPM:** current 5.57, prior 5.07.
- **CVX:** current 5.06, prior 1.82.
- **BA:** current -0.26, prior -7.14.
- **WMT:** current 0.62, prior 0.77.

EPS 파서는 후보를 찾는 용도다. 실제 전략 입력으로 쓰려면 `period_end`, `form`, `accession`, `gaap/diluted`, `current/prior column`을 함께 저장하는 별도 정합성 행이 필요하다. 이번 감사에서는 그 값을 수익률과 결합하지 않았다.

## 4. 추가 공시 항목의 실제 분류

| 종목 | Item | 원문 내용 | 분류 |
|---|---|---|---|
| ORCL | 8.01 | 이사회가 Mandatory Convertible Preferred Stock와 보통주 배당을 선언 | `PRICE_RELEVANT_OTHER_EVENT` — 실적과 별도 보존 |
| BAC | 7.01 | 실적 컨퍼런스콜·웹캐스트, 프레젠테이션·보충자료 제공 | `DUPLICATE_INFORMATION` — 새 기업사건으로 중복 계산하지 않음 |
| SLB | 7.01 | 동일 실적 보도자료를 Regulation FD로 제공; Item 2.02와 중복 | `DUPLICATE_INFORMATION` |
| CAT | 7.01 | 기계·파워시스템 최종수요자/OEM 소매판매 보충 통계 Exhibit 99.2 | `NEW_INFORMATION_CONFOUNDER` — 별도 정보 충격 |
| NKE | 2.05 | 다년간 비용 재편·Pace 프로그램, 비용 구조·공급망 최적화 | `MATERIAL_CORPORATE_CONFOUNDER` — 실적 사건과 분리 |

- 5/20은 Item 2.02만 있는 순수 후보가 아니다.
- BAC·SLB는 중복 정보로 분리하면 보존 가능하지만, CAT·NKE·ORCL은 별도 사건으로 표시하거나 제외해야 한다.
- 이 분류를 과거 전체 후보에 적용하지 않았으므로 전체 확장 게이트는 실패다.

## 5. Toss 공식 수수료와 기존 체결 내역

### 공식 수수료

공식 Toss Securities 자료를 확인했다.

- 공식 Open API 검색 노출 내용: 미국주식 매매수수료 **0.1%**, 주문별 총 체결금액 10달러 이하 수수료 면제라는 안내.
- 공식 고객센터: 개인별 적용 수수료·혜택은 앱의 증권 수수료 화면에서 확인하도록 안내.
- 미국주식 매도 시 SEC Fee 등 별도 제비용이 존재한다는 공식 FAQ 검색 결과.
- 공식 웹 페이지는 client-rendered라 정적 HTML 수집에서는 요율 본문이 노출되지 않았고, 적용일·사용자 계정별 이벤트 여부까지 독립적으로 확정하지 못했다.

따라서 엔진의 수수료 10bp는 **공개 기본 요율 0.1%와 숫자는 맞지만**, 다음을 포함하지 않는다.

- 주문별 10달러 면제 조건
- 계좌별 이벤트·혜택
- 매도 SEC Fee 등 별도 제비용
- 환전 스프레드
- 실제 체결 가격과 호가 스프레드

이번 감사에서는 엔진 기본값을 변경하지 않았다.

### 기존 체결 내역

저장소에서 확인한 읽기 경로:

- `/api/v1/connector/fills?since=...`
- `execution_snapshots`의 `filled_quantity`, `average_filled_price`, `commission`, `tax`, `captured_at`
- `broker_orders` 및 `order_intents` 원장

현재 확인 결과:

- `TRADE_BACKEND_URL`, `TRADE_ACCESS_TOKEN`, `TRADE_BROKER_CONNECTION_ID`가 모두 환경에 없다.
- `research-data/`에 체결 내역 산출물도 없다.
- 따라서 기존 계좌의 체결 수·commission·tax·filled price·filled_at을 확인하지 못했다.
- 읽기용 connector endpoint를 호출하지 않았고, 주문·승인·브로커 연결을 수행하지 않았다.

판정: **실제 비용 실측 실패.** 공식 수수료 표기만으로 개인 계좌의 적용 비용을 확정하지 않는다.

## 6. 군집 효과와 검정력 재평가

기존 20개 SEC submissions에서 Item 2.02 후보 1,924건을 재분류했다.

| 측정 | 결과 |
|---|---:|
| raw 후보 | 1,924 |
| `(issuer, reportDate)` unique | 1,885 |
| 동일 issuer·보고기간 중복/재제출 초과 | 39 |
| unique 사건 날짜 | 1,423 |
| 3거래일 블록 군집 | 921 |
| 5거래일 블록 군집 | 731 |
| 10거래일 블록 군집 | 467 |
| 20거래일 블록 군집 | 299 |

5거래일 블록은 동일 날짜 근처 발표를 하나의 독립 정보 블록으로 묶은 보수적 proxy다. 실제 회귀에서는 issuer·날짜·보유기간에 대한 cluster-robust 또는 block bootstrap이 필요하다.

계획용 수치:

- 500개 유니버스, 약 22년, unique 사건 빈도를 단순 외삽.
- IR 정확한 시각 7/20 = 35%, 추가 정보 충격 없는 후보 15/20 = 75%를 적용.
- 5년 raw 유효 후보: 약 **2,811건**.
- 5거래일 군집비율 `731/1,885` 적용 후 유효 독립 proxy: 약 **1,090건**.
- 10거래일: 약 697건. 20거래일: 약 446건.

프로토콜의 예시 최소 경제효과(+0.30%p), 순초과 변동성 4%p, 단측 5%, 검정력 80%에 필요한 독립 사건은 약 1,100건이다. 5거래일 기준 proxy가 **약 1,090건으로 기준보다 작고**, 10–20거래일 보유에서는 더 작다.

이는 수익률이 없는 계획용 검정력이다. 발표일 군집이 실제 초과수익 공분산을 얼마나 키우는지는 아직 추정하지 않았다. 따라서 “전체 확장 후 검정력이 충분하다”고 말할 수 없다.

## 7. 보완 감사 판정

| 보완 게이트 | 결과 |
|---|---|
| S1 관측시각 | 실패 — 사건별 `observed_at` 없음 |
| S2 EPS | 부분 통과 — 원문 20/20, 후속 XBRL 17/20 |
| S3 추가 공시 | 실패 — 5건 별도 분류 필요 |
| S4 비용 | 실패 — 공식 기본요율 외 계좌별 적용·체결내역 없음 |
| S5 검정력 | 실패 — 5일 군집 proxy 약 1,090 < 1,100 |

**전체 유니버스 확장 제안: 하지 않음.**

다음 단계는 별도 승인 없이는 실행하지 않는다. 필요한 조건은 다음과 같다.

1. 전진 수집기에 사건별 `observed_at`을 저장하고, `t_ready` 기준으로 다음 시가만 산출.
2. GAAP 희석 EPS·전년 동기·기간·열 순서를 하나의 provenance 행으로 저장.
3. CAT/NKE/ORCL 같은 추가 사건을 제외·분리하고, BAC/SLB 중복을 deduplicate.
4. 계좌별 공식 수수료 적용률과 기존 체결 내역을 읽기 전용으로 확인하거나, 확인 불가로 고정하고 슬리피지 스트레스를 사전 지정.
5. 군집 보정 후 검정력 기준을 충족하는 독립 사건 수가 확보될 때만 확장을 재검토.

기존 59개 실험·Q-1·LL-1 보고서와 주문 시스템은 변경하지 않았다.

## 출처와 산출물

- 기준: `claudedocs/event-audit-supplement-protocol.md`
- 선행 감사: `claudedocs/event-audit-report.md`
- SEC raw: `research-data/sec/` (gitignored)
- IR/SEC raw: `research-data/event-audit/raw/` (gitignored)
- SEC 접수·EPS metadata: `research-data/event-audit/sec-ir-audit.json` (gitignored)
- [Toss 공식 고객센터 수수료 안내](https://support.toss.im/faq/3669?from=10&page=1)
- [Toss 공식 업무수수료 안내](https://corp.tossinvest.com/ko/business?tab=businessFee)
- [Toss Securities Open API](https://p.tossinvest.com/ko/open-api)
- [SEC EDGAR API 문서](https://www.sec.gov/search-filings/edgar-application-programming-interfaces)

이 문서는 연구용 데이터 감사 보고서이며 투자 자문이 아니다.
