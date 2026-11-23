# Investment OS 퀀트 연구·검증 시스템 — 1차 보고

작성일: 2026-11-23 · 브랜치: `feat/quant-research-lab` (기준: `origin/main` 91e61a6)

> 백테스트 결과는 과거 데이터에 대한 가상 시뮬레이션이며 미래 수익 예측이 아니다.
> 이 보고 시점에 **실데이터 백테스트는 아직 실행되지 않았다.** 아래 성과 수치는 없다.

## 1. 현재 상태 (기존 시스템에 이미 있는 것)

| 영역 | 기존 구현 | 퀀트 연구에 주는 의미 |
|---|---|---|
| 시장 데이터 | Toss `GET /api/v1/candles` (`1d`/`1m`, 요청당 ≤200, `before` 페이징, `adjusted` 플래그) — `TossApiClient.getCandles`, `BrokerSurfaceController` `GET /api/v1/broker-connections/{id}/candles` (Bearer 인증, 읽기 전용) | 과거 일봉 수집 경로로 재사용. 과거 깊이·`adjusted` 의미는 **문서화돼 있지 않음** |
| 저장된 일봉 | `investment_tactical_overlay_bar_snapshots` (V55), `withTossCandles`는 100봉·`adjusted=false` | tactical 용 단기 창. 문서상 `UNADJUSTED`, 분할 메타데이터 없음 → 백테스트용으로 부족 |
| 지표 | Tactical overlay: EMA9/21/50, SMA20/50, RSI14, 20/60일 수익률, SPY 대비 상대강도 | 같은 계열 지표지만 **현재 시점 스냅샷** 계산. 과거 시점 재생(point-in-time replay)과 비용·체결 모델이 없음 |
| 위험 관리 | thesis 무효화, risk policy, `preflight_investment_risk`, 상시 모니터링 | 연구 신호가 반드시 거쳐야 할 기존 게이트 |
| 주문 | `prepare_order` → 사용자 승인 → `submit_order`, `REAL_ORDER_ENABLED` 기본 off | 연구 시스템은 접근하지 않음 |
| 예측 평가 | `analysis_predictions` / `analysis_prediction_outcomes` (D1/D5/D20 채점, ingestion API key) | 모의운용(검증 7단계)의 재사용 대상 |
| analysis-service | FastAPI, 의존성 3개(fastapi·pydantic·uvicorn), Decimal 기반 분석 | 연구 패키지를 둘 위치. 주문 권한 없음 |

## 2. 문제 정의

기존 시스템은 "현재 종목 상태"를 잘 설명하지만 **"이 매매 규칙이 과거에 비용 차감 후 S&P 500 보유보다 나았는가, 그 차이가 우연이 아닌가"** 에 답할 수단이 없다. 필요한 것:

1. 시점 정합(look-ahead 없음)한 과거 재생과 현실적 체결·비용 모델
2. 반복 실험(data snooping)을 반영한 통계적 유의성 판정
3. 시간순 분리(walk-forward + 잠긴 holdout)로 과최적화 차단
4. 데이터 출처·시각·해시 보존으로 재현 가능성

## 3. 기술 선택

| 프로젝트 | 결정 | 근거 (출처: 각 GitHub LICENSE/pyproject, 2026-11-23 확인) | 재검토 조건 |
|---|---|---|---|
| **자체 stdlib 엔진** | **채택** | 문제 규모: ETF 8종 × 수천 봉. 순수 Python으로 합성 2000봉 54개 설정 전체 검증 ≈0.7초. 신규 의존성 0, 기존 CI pytest 그대로. 체결 시점·비용·검증 규칙을 테스트로 고정 가능 | 종목 수백 개·분봉으로 확장 시 |
| VectorBT | 보류 | Apache-2.0 **+ Commons Clause**(유료 판매 서비스 제한). numba·pandas 3·plotly 등 무거운 의존성. 현재 규모에선 속도 이점 불필요 | 대규모 파라미터 탐색이 실제 병목이 될 때 |
| QuantStats | 보류 | Apache-2.0이나 **yfinance가 필수 의존성**(외부 데이터 경로 유입), pandas·matplotlib·seaborn. 필요한 지표(CAGR·Sharpe·Sortino·Calmar·MDD·회복기간·월별)는 ~250줄로 구현·검증 | HTML tearsheet가 필요할 때 결과 JSON을 입력으로 오프라인 사용 |
| Microsoft Qlib | 제외(현 단계) | MIT. 기본 데이터 중국 A주, **공식 데이터셋 다운로드 일시 중단**, mlflow·lightgbm·cvxpy 등 무거움. ML 팩터는 단순 규칙 전략이 검증되기 전엔 판단 근거가 없음 | 검증 통과 전략이 생기고 종목 단위 point-in-time 데이터 확보 후 |
| Microsoft RD-Agent | 제외(현 단계) | MIT이나 **LLM API 키 필수(유료)**, Docker 필수, Linux 전용. AI 생성 가설은 시행 횟수를 폭증시켜 DSR 기준을 더 어렵게 만듦 | 수동 가설 파이프라인이 안정화되고 비용 승인 후 |

도입하지 않아 잃는 것: 차트형 리포트, 대규모 벡터화 속도. 현재 질문(3개 전략의 유효성)에는 영향 없음.

## 4. 설계

```
Toss candles (기존 REST, 읽기 전용)
   └─ scripts/collect-toss-candles.py  → research-data/<set>/ (git 제외)
        manifest.json: provider·endpoint·adjusted·collected_at·page별 observed_at·sha256
        adjusted + unadjusted 두 시계열 + 원본 응답 JSON + adjustment_audit
   └─ python -m app.research --data <set> --out report.json --markdown report.md
        data.py        무결성(sha·OHLC·날짜) 검증, dataset_hash
        strategies.py  A 모멘텀 / B 추세 / C 평균회귀 (t 이하 데이터만 접근, 접두사 뷰로 강제)
        backtest.py    종가 t 판단 → 시가 t+1 체결, 드리프트 비중, 회전율×비용
        metrics.py     성과·위험 지표, PSR, DSR
        validation.py  walk-forward(3y 학습/1y 테스트) + 마지막 20% 잠긴 holdout, 1×/2× 비용, 판정
        report.py      정규 JSON(바이트 동일 재현), config_hash
   └─ (미래, 검증 통과 시에만) 신호 → 기존 prediction ingestion(D1/D5/D20 채점) → 기존 risk preflight → 기존 주문 승인
```

**주문 경계:** `app/research`는 fastapi·httpx·urllib·socket·`app.main`·`app.monitoring`을 import하지 않음(AST + `python -I` 서브프로세스 테스트로 강제). HTTP 라우트 추가 없음. 수집 스크립트는 GET candles만 호출. 신호 연동(5단계)은 **검증 통과 전략이 없으므로 구현하지 않음** — YAGNI이자 원칙 5(검증되지 않은 수익 주장 금지).

### 실험 범위 (실데이터 실행 전에 코드에 고정)

| 전략 | 규칙 | 그리드 | 포지션 |
|---|---|---|---|
| A 모멘텀 | 8개 ETF(SPY QQQ IWM EFA EEM TLT IEF GLD) L일 수익률(최근 skip일 제외) 상위 K, 월초 리밸런싱, 선택적 절대모멘텀(>0) 필터 | L{126,252}×skip{0,21}×K{1,2,3}×abs{T,F} = 24 | 동일가중, 나머지 현금 |
| B 추세 | SPY: 종가>SMA(N) 보유 / Donchian 종가 N일 돌파 진입·M일 이탈 청산 | SMA{100,150,200} + (N,M){(55,20),(100,50),(200,100)} = 6 | 100% 또는 현금 |
| C 평균회귀 | SPY: Wilder RSI(n)<임계 진입, 종가>SMA5 또는 max_hold일 청산, 선택적 SMA200 위 체제필터(과매도 반등 vs 구조적 하락 구분) | n{2,3}×임계{5,10,20}×hold{5,10}×filter{T,F} = 24 | 100% 또는 현금 |

총 시행 N=54 (DSR에 반영). 비용 기본: 수수료 10bp + 슬리피지 5bp/편도(Toss 실제 수수료 미검증 가정, CLI 변경 가능), 2× 스트레스. 현금 수익률 0(rf=0, 추세·평균회귀 전략에 보수적).

### 판정 기준 (사전 선언)

- `INSUFFICIENT_DATA`: stitched OOS < 3년, holdout < 1년, 또는 fold < 2 (전체 대략 ≥1920봉 필요)
- `VALIDATED_EDGE`: OOS DSR ≥ 0.95 **그리고** OOS Sharpe > SPY OOS Sharpe **그리고** 초과일수익 PSR(0) ≥ 0.95 **그리고** holdout Sharpe > 0 **그리고** 2× 비용 OOS Sharpe > 0
- 그 외 `NOT_VALIDATED` + 실패 기준 목록. MDD·Calmar·변동성 비교는 정보용(판정 아님)
- holdout은 파라미터 선택에 쓰지 않음(적대적 holdout 교란 테스트로 확인)

## 5. 변경 사항

| 파일 | 이유 |
|---|---|
| `scripts/collect-toss-candles.py` | 기존 broker surface로 일봉 페이징 수집, 출처·해시·원본 보존, adjusted/unadjusted 비교로 조정 방식 감사 |
| `analysis-service/app/research/{data,strategies,backtest,metrics,validation,report,__main__,__init__}.py` | 오프라인 연구 엔진 (1,810줄, stdlib 전용) |
| `analysis-service/tests/test_research_*.py`, `tests/research_fixtures.py` | 77개 테스트. 합성 데이터는 테스트 전용 |
| `.gitignore` | `research-data/` 추가 — 브로커 시세 데이터 커밋 방지 |

수정하지 않음: `trading-backend`, `web-dashboard`, `pyproject.toml`, 주문·승인·위험 정책, 운영 데이터.

## 6. 검증 결과

- analysis-service 전체 `133 passed` (기존 56 + 신규 77).
- 핵심 테스트: 54개 설정 전부 look-ahead 불변성, 손계산 체결·비용, 2자산 드리프트, 지표 손계산, PSR 단조성·DSR<PSR(0), 무결성 실패, fold 시간순·비중첩, holdout 비사용, CLI 바이트 동일 재현, import 격리.
- 수집 스크립트: 로컬 mock 백엔드로 E2E 검증(450봉 3페이지, sha 일치, 뉴욕 날짜 변환, 배당 조정 2건 탐지, 토큰 미기록, 불가 종목 격리·exit 1, 토큰 누락 exit 2).
- 수집기 페이징 중단 사유(`stop_reason`: `NO_CURSOR`/`EMPTY_PAGE`/`NO_PROGRESS`/`MAX_PAGES`)를 시계열별 기록, 의심 중단은 manifest `warnings`에 남김 — "Toss 과거가 짧음"과 "페이징이 깨짐"을 구분하기 위함. `adjustment_audit`는 O/H/L/C 조정 비율 일치 여부(`ohlc_ratio_mismatch_count`)도 검사 — 엔진이 시가로 체결하므로 시가 조정이 틀리면 수익률이 틀림.
- 수집 결과 → 엔진 E2E: mock 수집 데이터(450봉)에서 세 전략 모두 `INSUFFICIENT_DATA`로 정상 판정, 재실행 JSON 바이트 동일.
- CI: `scripts/ci-change-filter.py` 분류상 `analysis-service/` 변경은 analysis lane(pytest), `scripts/` 변경은 stack lane을 트리거.
- **실데이터 성과·위험 지표: 없음.** 수집을 실행해야 산출된다(아래 9).

### 엔진 설계상 명세 편차 (implementer 보고, 검토 후 수용)
- 수익률을 곱셈식 `nav_open·(1−c·T)·(1+intraday)−1`로 계산: 단일 자산 보유가 `close_T/open_0`와 정확히 일치하고, 100% 목표에서 비용으로 현금이 음수가 되지 않음. 가산식과는 2차 항만 다름.
- 회전율은 시가 시점 드리프트 비중 기준(가짜 거래 방지).
- Donchian은 고가가 아닌 종가 기준 채널.

## 7. 비용

| 항목 | 초기 | 월간 |
|---|---|---|
| 신규 라이브러리·서비스 | 0 | 0 |
| 클라우드·유료 API | 0 | 0 |
| Toss API 호출 | 기존 계정, 1회 수집 ≈ 8종목 × 2시계열 × ≤40페이지 | 재수집 시에만 |
| CI 시간 | 연구 테스트 ≈ 수 초 추가 | — |

## 8. 남은 위험

1. **Toss 과거 깊이 미확인** — 1920봉(≈7.6년) 미만이면 전 전략 `INSUFFICIENT_DATA`. 페이징 수집 후 확인.
2. **`adjusted=true` 의미 미확인** — 배당 반영 여부 불명. manifest의 `adjustment_audit`(조정/비조정 종가 비율 변화일)를 보고 사람이 `adjustment_basis`를 판정해야 함. 배당 미반영이면 SPY 벤치마크와 전략 모두 총수익 과소평가(채권·고배당 ETF 비중이 큰 모멘텀에 불리).
3. **생존 편향** — 오늘 고른 장수 ETF 유니버스. `LIMITED_NOT_CONTROLLED`로 표기. 개별 종목 횡단면 모멘텀은 point-in-time 구성종목 없이 하지 않음.
4. **수수료 가정** 10bp 미검증, 체결 실패·부분체결·시가 갭 유동성 미모델링(ETF 대형주 기준 영향 작다고 보지만 검증 안 됨).
5. **DSR 독립시행 가정** — 설정 간 상관이 높아 보수적/부정확할 수 있음.
6. 휴장일 정렬로 날짜가 빠지면 월말 결정 위치가 한 칸 밀릴 수 있음(보고서 `dropped_dates`로 노출).
7. rf=0 — 현금 보유 전략의 실제 성과를 과소평가.
8. **전역 날짜 정렬** — 모든 전략이 8개 ETF+SPY 날짜 교집합에서 평가된다. 한 ETF의 Toss 이력이 짧으면 SPY 단일 전략(추세·평균회귀)도 그만큼 잘린다. 결과의 `aligned_first`와 manifest 종목별 `first_date`를 비교해 확인.
9. **미모델링 비용·실패** — KRW 계좌의 환전 스프레드, 매도 시 SEC fee/FINRA TAF, 수집 중 429 rate limit(재시도 없음, 해당 종목 오류 처리).

## 9. 다음 단계 (기대효과순)

1. **실데이터 수집·실행** (사용자 실행, 운영 Toss 연결 읽기 전용):
   - 토큰: 대시보드 로그인 후 브라우저 개발자 도구에서 백엔드 API 요청의 `Authorization: Bearer …` 값(또는 `POST /api/v1/auth/refresh` 응답의 access token). 기본 TTL **20분**(`ACCESS_TOKEN_TTL`). 만료 시 수집기는 HTTP 401 힌트와 함께 해당 종목을 오류로 남긴다 — 새 토큰으로 재실행.
   - compose는 백엔드를 `127.0.0.1`에 바인딩하므로 원격 호스트면 SSH 터널 필요.
   - **스모크 먼저:** `--symbols SPY --max-pages 2` → manifest에서 `pages`=2, `rows`≈400, `stop_reason`=`MAX_PAGES`, `first_date`가 약 400거래일 전인지, `adjustment_audit` 확인. 여기서 `NO_PROGRESS`면 페이징 커서 형식 문제이므로 전체 실행 금지.
   ```sh
   export TRADE_BACKEND_URL=http://127.0.0.1:8080 TRADE_BROKER_CONNECTION_ID=... TRADE_ACCESS_TOKEN=...
   python3 scripts/collect-toss-candles.py --out research-data/smoke --symbols SPY --max-pages 2
   python3 scripts/collect-toss-candles.py --out research-data/toss-etf-$(date +%Y%m%d)
   cd analysis-service && python -m app.research --data ../research-data/toss-etf-YYYYMMDD \
       --out ../research-data/report.json --markdown ../research-data/report.md
   ```
   → 과거 깊이, `adjustment_audit`, 세 전략 판정 확인. 결과가 나빠도 그리드를 바꿔 재실행하지 않는다(바꾸면 N 증가로 기록).
2. ~~**adjustment_basis 확정** — audit 결과로 manifest 수정 후 재실행~~ → **2차 보고로 대체**: 수동 수정 금지, 사전 규칙 `adj-rule-v1`(수집기 `--classify-only`)만으로 기록.
3. **검증 통과 전략이 있을 때만:** 현재 신호를 기존 prediction ingestion에 D5/D20 예측으로 등록해 모의운용 채점(새 추적기 만들지 않음). 일정 기간 채점 후 기존 `preflight_investment_risk` 입력으로 연결 검토. 주문 경로 변경은 별도 승인.
4. Toss 수수료 실제값 확인 → 비용 기본값 갱신.
5. 통과 전략 없으면: 실패 기준별 원인 분석(비용? 체제? 데이터 길이?)만 기록하고 복잡도 추가 보류.

---

# 2차 보고 — 실데이터 수집·백테스트 (2026-11-23)

## A. 무엇을 바꿨고 왜

| 커밋 | 변경 | 이유 |
|---|---|---|
| `a452431` | 수집기: GET+캔들 경로만 허용·리다이렉트/프록시 거부, 429/타임아웃 백오프, 401 즉시 중단+`--resume`, 조정 판정 규칙 `adj-rule-v1`, pause 1초 | 대시보드 토큰은 주문까지 가능한 전권 토큰 → 스크립트가 자체 차단. Toss 429 는 HTTP 200 봉투 `PROVIDER_RATE_LIMITED` 로 옴(백엔드 소스 확인). 토큰 TTL 20분 대비 재개 필요 |
| `1094674` | 엔진: `adjustment_basis != SPLIT_AND_DIVIDEND` 면 `HELD_UNVERIFIED_ADJUSTMENT`, 보고서에 CAGR·MDD·회전율·비용드래그·SPY 비교·폴드 표 | 요구 "불확실하면 판정 보류"를 데이터 보기 전에 코드로 고정. 54개 그리드 불변, config_hash 만 변경 |
| `250f7f7` | 수집기 docstring 정정 | 엔진 어휘(SPLIT_AND_DIVIDEND/UNVERIFIED)만 기록 |
| `62a8914` | 로더: 수집 시각(뉴욕) 16:30 이전 당일 봉·이후 날짜 봉 제외 | 스모크에서 장중 미완성 봉이 마지막 행으로 들어오는 것 발견 |

테스트: 수집기 20/20, analysis-service 157/157. 백엔드·대시보드·주문·승인·운영 데이터 무변경(캔들 경로는 DB 쓰기 없음 확인).

## B. 수집 결과 (Toss `/api/v1/candles`, 1d, count=200, nextBefore)

| 종목 | 봉 | 시작 | 중단 사유 | 재시도 |
|---|---|---|---|---|
| SPY | 8000 | 1995-02-09 | MAX_PAGES (40페이지 한도) | 0 |
| QQQ | 6971 | 1999-03-10 | NO_CURSOR | 1 (RATE_LIMITED, 복구) |
| IWM | 6663 | 2000-05-26 | NO_CURSOR | 0 |
| EFA | 6354 | 2001-08-17 | NO_CURSOR | 0 |
| TLT / IEF | 6122 | 2002-07-26 | NO_CURSOR | 0 |
| EEM | 5943 | 2003-04-11 | NO_CURSOR | 0 |
| GLD | 5538 | 2004-11-18 | NO_CURSOR | 0 |

- 총 10분 43초, 오류 0, 중복 0, 정렬 정상, 5일 초과 공백은 2001-09-11 휴장뿐.
- 페이지 경계 누락 검사: 16개 시리즈 전부 `sum(pages.count) == rows`(겹침 0). 200봉 경계 497곳의 달력 간격이 모두 1일/주말 3일/휴장일(2024-07-04, 2003-01-20 MLK, 1998-04-10 Good Friday, 1995-11-23 Thanksgiving) → 경계에서 빠진 봉 없음.
- SPY 는 페이지 한도로 1993~1995 누락. 공통 정렬 시작이 GLD(2004-11-18)라 결과 영향 없음. 필요 시 `--resume --max-pages 60`.

## C. 분할·배당 조정 판정 — UNVERIFIED (판정 보류 사유)

- `adjusted=true` 와 `false` 가 30년간 사실상 동일(비율 변화: 당일 장중 봉, 2002-03-21 이상치뿐).
- QQQ 2000-03 2:1, EFA/EEM 2005-06 3:1 분할 시점에 두 시리즈 모두 가격 연속 → **둘 다 분할 반영**.
- 수백 회 배당 지급에도 비율 불변 → **둘 다 배당 미반영**(가격수익률).
- `adjusted=false` 에 2002-03-21 하루 분할 전 가격(EFA 121.03, IWM 100.53) 데이터 오류. 엔진은 adjusted 만 사용.
- 사전 규칙(adj-rule-v1): SPY 분기 배당 step 0/95 필요, GLD 대조군·OHLC 검사는 당일 장중 봉 때문에 실패 → `UNVERIFIED` → 세 전략 모두 `HELD`.
- 사실상 기준은 "SPLIT_ONLY(가격수익률)". 규칙을 결과 본 뒤 바꾸지 않음(스누핑 방지).

## D. 백테스트 결과 (참고용, 판정 보류)

정렬 5537일(2004-11-18~2026-11-20), walk-forward 13폴드 OOS 2008-12-22~2021-12-27, holdout 2022-06-24~2026-11-20, N=54, 비용 15bp(수수료 10+슬리피지 5). dataset_hash `ffda791c…`, config_hash `9bccfc38…`, 재실행 바이트 동일.

| 전략 | OOS CAGR | SPY | OOS Sharpe | SPY | OOS MDD | SPY MDD | DSR | Active PSR | 2x비용 Sharpe | 회전율/년 | 비용드래그/년 | Holdout CAGR | SPY |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| momentum | 4.40% | 13.87% | 0.35 | 0.81 | -36.1% | -34.1% | 0.41 | 0.014 | 0.29 | 688% | 1.03% | 8.50% | 14.66% |
| trend | 4.62% | 13.87% | 0.45 | 0.81 | -20.2% | -34.1% | 0.55 | 0.008 | 0.36 | 669% | 1.00% | 6.58% | 14.66% |
| mean_reversion | -1.14% | 13.87% | -0.14 | 0.81 | -18.2% | -34.1% | 0.02 | 0.0004 | -0.47 | 1508% | 2.26% | 1.27% | 14.66% |

보류가 없었어도 세 전략 모두 DSR·SPY 대비 Sharpe·Active PSR 기준 탈락(평균회귀는 2x 비용도 탈락). trend 는 MDD 를 SPY 대비 14%p 줄였으나 수익 희생이 더 큼.

배당·현금이자 누락 보정 상한(근거 있는 추정, 결론 불변):
- momentum: 최대 이득 = 유니버스 최고 분배율 ETF 를 100% 보유한 경우 ≈ 연 4~5%p, SPY 도 ≈ 1.8%p 얻음 → 격차 9.5%p 중 최대 ≈ 3%p 축소.
- trend(평균 노출 0.73, SPY/현금)·mean_reversion(노출 0.09): 배당 이득이 SPY 보다 작아 격차 오히려 확대. 현금 이자(rf=0 가정) 보정 시 holdout(2022~26) mean_reversion 은 ≈ 연 4%p 추가 → ≈ 5% vs SPY 14.66%, 여전히 열위.

기타 공개 사항: 마지막 폴드 끝(2021-12-27)과 holdout 시작(2022-06-24) 사이 약 6개월은 어느 쪽에도 쓰이지 않음(폴드 1년 단위 잔여).

## C-2. 기존 시스템 관련 부수 발견 (코드 변경 없음, 확인 필요)

`docs/ops/tactical-overlay-v1-calculations.md` 는 Toss 일봉을 UNADJUSTED 로 가정하지만, 실측상 `adjusted=false` 도 **분할 반영**(QQQ 2000 2:1, EFA/EEM 2005 3:1 시점 가격 연속)이고 2002-03-21 하루만 분할 전 가격 오류가 있다. 실시간 tactical overlay 가 "비조정" 가정에 의존한다면 재확인 필요.

## E. 결론과 다음 단계

- **모의운용 제안 대상: 없음.** 통과 전략 0. 실거래 연결 없음.
- 그리드 재튜닝 안 함. 다음 단계 후보:
  1. 배당 포함 데이터 확보 — Toss 는 미제공으로 보임. 대안: 배당 이력을 별도 무료 소스(예: SEC/ETF 발행사 분배 공시)에서 받아 총수익 재구성 → 비용/승인 필요 여부 사전 확인.
  2. 읽기 전용 커넥터 API 키용 캔들 경로(백엔드 변경+배포, 별도 승인) → 토큰 수동 복사 제거, 키 단계 주문 차단.
  3. 새 가설은 N 누적(54→)으로 DSR 반영 후 추가.
