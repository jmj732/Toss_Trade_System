# 실적 이벤트 스윙 데이터 감사 결과

감사일: 2026-11-24
프로토콜: [`event-audit-protocol.md`](event-audit-protocol.md)
대상: 미국주식 대표 20개
범위: SEC·발행사 IR 원문/시각·EPS·최초 실행 가능한 시가·기업행사·비용 입력 감사

## 1. 결론

**전체 유니버스 확장 불승인.** 소표본 감사는 SEC·EPS·가격 행 수집성은 통과했지만, **IR 실적 발표의 정확한 게시시각은 7/20(35%)만 기계적으로 확인**됐다. 기업행사 순수성도 15/20에 그쳤고, 실제 주문 비용은 관측하지 못했다.

판정:

- **IR 정확한 시각을 이용한 당일 시가 진입 경로:** `STOP_NO_RESEARCH_VALUE`
- **SEC 접수시각 + 보수적 다음 실행 시가 경로:** `HOLD_FOR_DATA_FIX`
- **전체 유니버스 SEC/IR 확장·수익률 결합·백테스트·주문 연결:** 실행하지 않음

이는 전략 수익성 판정이 아니다. 시각·EPS·가격·기업행사·비용을 실제로 결합한 수익률 계산은 하지 않았다.

기존 59개 실험, Q-1 사전 등록과 보고서, 재현 환경은 보존했다.

## 2. 사전 고정 기준

프로토콜은 결과를 보기 전 커밋 `4828cd5`로 고정했다.

- 표본 20개: AAPL, MSFT, NVDA, AMZN, CSCO, ORCL, JPM, BAC, SLB, CVX, JNJ, PFE, AMGN, PG, WMT, MCD, CAT, BA, HD, NKE.
- 사건: SEC `8-K` Item `2.02`.
- SEC 시각: `acceptanceDateTime` UTC를 ET로 변환. 최초 발행사 발표시각으로 간주하지 않음.
- IR 시각: 공식 발행사 페이지의 구조화된 게시시각 또는 원문에 명확한 게시시각+시간대가 있는 경우만 `EXACT`. 컨퍼런스콜 시각은 제외.
- EPS: SEC Exhibit 99.1/실적 보도자료에서 원문 문구를 기계 추출. GAAP·조정 EPS가 여러 개면 후보를 모두 보존하고 하나를 임의 선택하지 않음.
- 시가: SEC 시각 기준, 장 시작 전이면 당일, 그 외 다음 거래일의 기존 일봉 `open`. 수익률은 계산하지 않음.
- 확장 승인에는 G1–G8 모두 통과 필요.

## 3. 게이트 결과

| 게이트 | 결과 | 판정 | 근거 |
|---|---:|---|---|
| G1 SEC CIK·가격 입력 | 20/20 | 통과 | SEC raw manifest와 Toss 일봉 존재 |
| G2 SEC Item 2.02 사건 | 20/20 | 통과 | accession·접수시각·원문 확보 |
| G3 SEC EPS 문구 | 20/20 | 통과(수집성) | SEC 첨부에서 EPS 후보 발견; 값 선택은 미실시 |
| G4 공식 IR 동일자료 | 20/20 | 통과 | 공식 IR/뉴스룸/issuer PDF 확인; CVX·BAC은 공식 대체 URL 사용 |
| G5 IR 정확한 게시시각 | **7/20** | **실패** | 11개 날짜만, WMT 시간대 없음, CAT 시각 의미 불명 |
| G6 실행 시가 행 | 20/20 | 통과(보수 기준) | 20개 모두 보수적 다음 실행일 `open` 존재 |
| G7 기업행사 분리 | 15/20 순수 | **실패** | 5개 최신 8-K에 추가 Item 7.01/8.01/2.05 존재 |
| G8 실제 비용 | 0/20 실측 | **실패** | 주문을 만들지 않았고 OHLCV에는 스프레드·충격비용 없음 |

G5 기준은 프로토콜의 `15/20`이다. 7/20은 95% Wilson 구간 약 18.1%–56.7%다. 소표본에서 전체 유니버스의 정확한 시각 커버리지를 35%로 확정하지는 않지만, 현재 자료만으로 당일 시가 연구를 확장할 근거는 없다.

## 4. 20개 결과

`SEC ET`는 SEC 접수시각이다. `IR`의 `EXACT`만 게시시각으로 인정했다. `다음 open`은 SEC 시각만 사용한 보수적 결과다. `EPS`는 원문 후보 존재 여부이며 수익률 계산에 사용하지 않았다.

| 종목 | SEC ET | IR 시각 관측 | EPS | 보수적 다음 open | 과거 2.02 후보* |
|---|---|---|---|---|---:|
| AAPL | 2026-11-02 16:30 | 날짜만 | FOUND | 2026-11-03 | 95 |
| MSFT | 2026-10-28 16:04 | 날짜만 | FOUND | 2026-10-29 | 91 |
| NVDA | 2026-11-17 16:20 | 날짜만 | FOUND | 2026-11-18 | 95 |
| AMZN | 2026-10-29 16:05 | 날짜만 | FOUND | 2026-10-30 | 89 |
| CSCO | 2026-11-12 16:06 | **EXACT 2026-11-12 15:05Z** | FOUND | 2026-11-13 | 89 |
| ORCL | 2026-09-10 16:16 | 날짜만 | FOUND | 2026-09-11 | 85 |
| JPM | 2026-10-13 06:30 | **EXACT 07:08:30 ET** | FOUND | 2026-10-13 | 90 |
| BAC | 2026-10-14 06:44 | 날짜만 | FOUND | 2026-10-14 | 94 |
| SLB | 2026-10-23 07:04 | **EXACT 06:50:26 ET** | FOUND | 2026-10-23 | 89 |
| CVX | 2026-10-30 06:16 | **EXACT 06:15:23 ET** | FOUND | 2026-10-30 | 123 |
| JNJ | 2026-10-13 08:12 | 날짜만 | FOUND | 2026-10-13 | 92 |
| PFE | 2026-11-03 08:07 | 날짜만 | FOUND | 2026-11-03 | 93 |
| AMGN | 2026-11-03 16:05 | **EXACT 16:02:11 ET** | FOUND | 2026-11-04 | 91 |
| PG | 2026-10-22 07:02 | **EXACT 06:00Z** | FOUND | 2026-10-22 | 89 |
| WMT | 2026-11-19 06:58 | 06:00, 시간대 없음 | FOUND | 2026-11-19 | 92 |
| MCD | 2026-11-04 07:01 | 날짜만 | FOUND | 2026-11-04 | 106 |
| CAT | 2026-10-29 06:33 | 07:30 CT 표시, 게시시각 불명 | FOUND | 2026-10-29 | 93 |
| BA | 2026-10-27 07:30 | 날짜만 | FOUND | 2026-10-27 | 94 |
| HD | 2026-11-17 06:18 | 날짜만 | FOUND | 2026-11-17 | 89 |
| NKE | 2026-10-01 16:15 | **EXACT 16:15 ET** | FOUND | 2026-10-02 | 145 |

\* `과거 2.02 후보`는 기존 SEC submissions raw에서 수집된 기간의 후보 수다. 모두 독립 사건이라는 뜻이 아니며, 같은 기업·분기·날짜 군집을 포함할 수 있다.

### SEC와 IR 시각의 차이

- SEC 접수시각은 최초 회사 발표시각이 아니다. SEC 공식 API 설명도 submissions/companyfacts의 지연을 EDGAR dissemination 이후의 지연으로 설명하며, issuer의 최초 발표시각을 제공한다고 하지 않는다.
- AAPL·MSFT·NVDA·AMZN·CSCO·ORCL·AMGN·NKE는 SEC 접수가 장 마감 후였다. 그중 CSCO는 IR 페이지의 구조화 게시시각이 장 시작 전이므로, **SEC만 보면 다음 날이지만 IR을 사용하면 같은 날 가능성**이 생긴다. 이는 두 채널의 정보 도착 정의를 먼저 고정해야 하는 이유다.
- 페이지의 `2:00 p.m. PT`, `8:30 a.m. ET` 등은 컨퍼런스콜 시각인 경우가 많았다. 이를 발표시각으로 세지 않았다.

## 5. EPS 수집성

- 20/20 SEC 첨부에서 EPS 관련 문구를 찾았다.
- 표현이 통일되지 않았다: `diluted earnings per share`, `earnings per diluted share`, `GAAP/non-GAAP EPS`, `profit per share`(CAT), `diluted loss per share`(BA), 표 형태(`$/Share`)가 함께 사용된다.
- 한 문서에 GAAP·조정 EPS, 당기·전년·가이던스가 같이 있어 **첫 번째 숫자를 전략 입력으로 쓰면 안 된다.** 보고기간·GAAP 여부·희석 여부를 원문 문맥으로 별도 확정해야 한다.
- 따라서 G3는 “EPS 값을 수집할 수 있음”만 통과했다. EPS surprise 계산이나 조건부 수익률은 하지 않았다.

## 6. 기업행사·가격·시가

### 기업행사

최신 후보의 Item에 2.02 외 항목이 같이 붙은 종목은 5개다.

- ORCL: 8.01
- BAC: 7.01
- SLB: 7.01
- CAT: 7.01
- NKE: 2.05

Item 7.01/8.01이 항상 가격을 오염시킨다고 단정하지는 않는다. 그러나 실적 사건만의 효과라고 주장하기 전에 원문으로 분리해야 한다. NKE의 2.05는 특히 별도 기업사건 검토가 필요하다. 5/20은 프로토콜의 `UNRESOLVED 2개 초과` 중단 기준을 넘는다.

### 가격·시가

- 20/20에 조정 일봉과 비조정 일봉이 있다.
- 기존 가격 manifest의 adjustment basis는 `SPLIT_AND_DIVIDEND`다.
- SEC 접수시각만 사용한 보수적 다음 `open` 행은 20/20 존재한다. SEC 기준으로 같은 날 open 12개, 다음 거래일 open 8개다.
- 다만 무료 일봉은 호가·스프레드·부분체결·장중 거래정지·합병 대가를 제공하지 않는다. `open` 행이 있다는 사실은 체결 가능성을 보증하지 않는다.

## 7. 실제 거래비용

실제 주문은 만들지 않았다. 따라서 표본에서 관측한 비용은 0건이다.

- 기존 연구 엔진의 기본값은 편도 수수료 10bp + 슬리피지 5bp다.
- 이 값은 기존 문서에도 **Toss 실제 수수료 미검증 가정**으로 기록되어 있다.
- OHLCV만으로는 실제 스프레드·시장충격·시가 체결 편차·부분체결을 확인할 수 없다.
- 비용을 확인하지 않은 상태에서 순수익이나 복리성장을 계산하지 않는 것이 맞다. G8 실패는 전략 실패가 아니라 데이터 감사 중단 사유다.

## 8. 전체 확장 시 사건 수와 검정력

계획용 계산이다. 수익률을 사용하지 않았고, 전체 유니버스 데이터를 수집한 결과가 아니다.

1. 기존 raw submissions에서 20개 종목의 Item 2.02 후보는 합계 1,924건이다. 대략 2004–2026의 22년 구간으로 나누면 **20개에서 연 87.5건**이다.
2. 500개 규모로 단순 외삽하면 gross 약 **연 2,186건**이다. 산업별 발표 빈도·상장기간·중복·생존편향을 반영하지 않은 상한성 계획값이다.
3. 현재 감사의 보수적 커버리지를 적용하면:
   - IR 게시시각 EXACT: 7/20 = 35%
   - 추가 기업행사 없는 후보: 15/20 = 75%
   - 단순 곱: 약 **연 574건**, 5년 약 **2,870건**
4. 95% Wilson 구간을 기계적으로 곱한 계획 범위는 5년 약 **1,051–5,504건**이다. 이 범위는 독립성·산업 차이·미래 커버리지 변화를 무시한 참고치이며, 검정력 증거가 아니다.
5. 프로토콜의 최소 경제효과 예시(+0.30%p), 사건별 순초과 변동성 4%p, 단측 5%·80% power에는 독립 사건 약 **1,100건**이 필요하다. 같은 날짜 발표·기업별 반복·보유기간 겹침 때문에 raw 2,870건이 1,100개의 독립 관측이라는 보장은 없다. 독립 유효율을 측정하기 전에는 검정력이 충분하다고 말할 수 없다.

## 9. 최종 판정과 다음 조건

### 판정

**`HOLD_FOR_DATA_FIX` — 전체 확장 금지.**

- SEC·EPS·가격 수집 파이프라인은 대표 표본에서 작동했다.
- IR 정확한 시각은 일반화할 수 없었다. 정확한 시각을 전제로 한 당일 진입 연구는 중단한다.
- 기업행사 5개와 비용 관측 불가 문제를 해결하지 않았다.
- 신규 전략·파라미터·수익률·백테스트·주문 연결은 하지 않았다.

### 확장 전 필요한 사용자 승인 항목

1. 5개 동시 기업행사의 원문 분리 규칙과 `UNRESOLVED` 처리 규칙 확정.
2. IR 날짜만 있는 사건을 제외할지, SEC 기준 보수적 다음 시가만 허용할지 결정.
3. 실제 비용을 무료 데이터의 보수 가정으로만 둘지, 비용 미확인 상태에서 연구를 중단할지 결정.
4. 위 항목을 통과한 경우에만 전체 유니버스 SEC·IR 수집을 별도 승인.

현재는 1–3을 해결하지 않았으므로 전체 확장을 실행하지 않는다.

## 산출물·재현성

- 사전 기준: `claudedocs/event-audit-protocol.md` (커밋 `4828cd5`)
- SEC/원문 수집 helper: `scripts/audit-sec-ir-events.py`
- SEC 감사 메타데이터: `research-data/event-audit/sec-ir-audit.json` (gitignored)
- IR URL·게시시각 분류·원문 해시: `research-data/event-audit/ir-audit.json` (gitignored)
- 원문: `research-data/event-audit/raw/` (gitignored)
- SEC 연락처: Doppler `trade/stg`의 `SEC_USER_AGENT`를 실행 시 환경변수로만 주입. 출력·로그·파일에 기록하지 않음.

### 공식 IR 원문 링크

[AAPL](https://www.apple.com/newsroom/2026/11/apple-reports-fourth-quarter-results/) · [MSFT](https://www.microsoft.com/en-us/Investor/earnings/FY-2027-Q1/press-release-webcast) · [NVDA](https://investor.nvidia.com/news/press-release-details/2026/NVIDIA-Announces-Financial-Results-for-Third-Quarter-Fiscal-2027/) · [AMZN](https://ir.aboutamazon.com/news-release/news-release-details/2026/Amazon-com-Announces-Third-Quarter-Results/) · [CSCO](https://investor.cisco.com/news/news-details/2026/CISCO-REPORTS-FIRST-QUARTER-EARNINGS/default.aspx) · [ORCL PDF](https://investor.oracle.com/files/content_files/1q27-pressrelease-September_FINAL.pdf)

[JPM](https://jpmorganchaseco.gcs-web.com/news-releases/news-release-details/jpmorganchase-reports-third-quarter-2026-financial-results/) · [BAC PDF](https://investor.bankofamerica.com/regulatory-and-other-filings/select-sec-filings/content/0000070858-26-000481/attachments/The%20Press%20Release_3Q26_ADA.pdf) · [SLB](https://investorcenter.slb.com/news-releases/news-release-details/slb-announces-third-quarter-2026-results) · [CVX](https://chevroncorp.gcs-web.com/news-releases/news-release-details/chevron-reports-third-quarter-2026-results) · [JNJ](https://www.investor.jnj.com/investor-news/news-details/2026/Johnson--Johnson-reports-Q3-2026-results-raises-2026-guidance/default.aspx) · [PFE PDF](https://s206.q4cdn.com/795948973/files/doc_financials/2026/q3/Q3-2026-PFE-Earnings-Release-FINAL.pdf)

[AMGN](https://investors.amgen.com/news-releases/news-release-details/amgen-reports-third-quarter-2026-financial-results) · [PG](https://www.pginvestor.com/news/news-details/2026/PG-Announces-Fiscal-Year-2027-First-Quarter-Results/default.aspx) · [WMT](https://corporate.walmart.com/news/2026/11/walmart-releases-q3-fy27-earnings) · [MCD](https://corporate.mcdonalds.com/corpmcd/our-stories/article/Q3-2026-results.html) · [CAT](https://www.caterpillar.com/en/news/corporate-press-releases/h/3q26-results-caterpillar-inc.html) · [BA](https://investors.boeing.com/investors/news/press-release-details/2026/Boeing-Reports-Third-Quarter-Results/default.aspx)

[HD](https://ir.homedepot.com/news-releases/2026/11-17-2026-110221331) · [NKE](https://investors.nike.com/news-events/press-releases/detail/575/nike-inc-reports-fiscal-2027-first-quarter-results)

이 문서는 연구용 데이터 감사 보고서이며 투자 자문이 아니다.
