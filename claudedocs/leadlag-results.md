# LL-1 리드-래그 결과 보고 (2026-11-23)

사전 등록: `claudedocs/leadlag-preregistration.md` (84fe065, 부록 A 개정 ff48ad2). 과거 구간 탐색 결과이며, 가설적 결과로 주문은 없다.

## 데이터

### 가격 (Toss)

- 수집: 43종목 = 개별주 35 + 섹터 ETF 7 + SPY. 1d, 분할조정·미조정 두 시리즈를 모두 받았다.
- 오류 0. 병렬 샤드 4개로 수집한 뒤 `scripts/merge-toss-collections.py`로 해시를 검증하며 병합했다.
- 1995년 이전 이력은 MAX_PAGES로 잘렸다. 부록 A에 따라 분석 시작일(1998-12-22) 이전 구간이므로 허용했다.

### 배당 (Yahoo `events=div`, 무료)

- 사전 등록 검증을 통과한 40개만 사용했다.
- 제외된 3개는 규칙대로 제외했으며, 데이터를 대체하거나 보정하지 않았다.

| 종목 | 사유 | 해석 |
|---|---|---|
| C | 분할-배당 불일치 (2002-03-21/22) | Toss 미조정 시계열 이상. 같은 날 EFA·IWM에서도 관측됨 |
| GE | 분할-배당 불일치 (2024-04-02) | GE Vernova 분사로 비율이 점프. 분할이 아님 |
| KO | 배당락일이 비거래일 (2001-09-12) | 9·11 휴장일 |

### 최종 데이터셋

- 조정 기준: `SPLIT_AND_DIVIDEND` (파생)
- 구성: 개별주 32 + 섹터 ETF 7 + SPY

## 결과 (walk-forward OOS 2001-12-28 ~ 2026-01-12, 6048일, fold 24)

| 시도 | Sharpe | 총수익 | 2배 비용 Sharpe | DSR(N=58) |
|---|---|---|---|---|
| M0 k3 | -0.25 | -89.6% | -1.27 | 0.0006 |
| M0 k5 | -0.29 | -83.1% | -1.27 | 0.0003 |
| M1 k3 | -0.39 | -95.8% | -1.63 | 0.0000 |
| M1 k5 | -0.39 | -90.7% | -1.54 | 0.0000 |
| SPY 보유 | 0.58 | +825.8% | - | - |

## 판정

### G1 실패: M1은 M0보다 낫지 않다

| 검정 | 평균 | t | 단측 p |
|---|---|---|---|
| T1 Clark-West (주 목표 open→open) | ≈0 | -0.33 | 0.63 |
| T2 M1−M0 순수익 차이 (k=5) | -0.01%/일 | -1.46 | 0.93 |

부호도 반대 방향이다. 선도주 정보를 더하면 오히려 나빠진다.

### 진단 Clark-West (판정에 쓰지 않음)

| 목표 구간 | t | p |
|---|---|---|
| 종가→종가 | -0.29 | 0.62 |
| 밤사이 갭 | +0.09 | 0.46 |
| 장중 | -0.87 | 0.81 |

체결 시점과 무관하게 신호 자체가 없다. "신호 있음·체결 불가"나 "비용 미달"에 해당하지 않는다.

### G2 실패

M1 k5는 DSR, SPY 대비 Sharpe, active PSR, 2배 비용 기준을 모두 미달했다.

### 비용과 모델

- M0도 큰 손실이다. 2배 비용에서 크게 악화되므로, 매일 교체하는 매매 비용이 손실의 큰 몫을 차지한다.
- 다만 판정 결론(M1 > M0 아님)은 비용과 무관한 T1으로 확정된다.

## 결론과 다음 단계 (사전 등록 §6에 따름)

- 연구를 중단한다. M2(경제적 관계)와 M3(공시·뉴스)는 구현하지 않는다.
- 모의운용 후보는 없다.
- 전진 확증은 G2 통과 후보가 없으므로 시작하지 않는다.

### 해석상 한계

- 생존 편향은 통제하지 못했다. 다만 이 편향은 결과를 위로 미는 방향이므로 무효 결론을 강화한다.
- 대형주끼리, 일봉 기준의 결과다. 소형주 또는 일중 데이터에서의 리드-래그는 검정하지 않았다.
- 누적 시도 수는 N = 58이다. 다음 가설은 LL-2로 별도 등록하고 이 수에 누적해야 한다.

## 재현

```
python3 scripts/fetch-yahoo-dividends.py --symbols <43종목> --out research-data/leadlag-dividends
cd analysis-service
python3.10 -m app.research.total_return --toss ../research-data/leadlag --dividends ../research-data/leadlag-dividends --out ../research-data/leadlag-tr
python3.10 -m app.research.leadlag --data ../research-data/leadlag-tr --prior-report ../research-data/full-report.json --out ../research-data/leadlag-report.json --markdown ../research-data/leadlag-report.md
```

해시:

- 데이터 `cc0f5ab3…`
- 설정 `3b465eed…`
