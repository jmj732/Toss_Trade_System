# 문헌 검증: 수익성(GP/A)·퀄리티 프리미엄은 대형주·롱온리·비용 차감 후에도 살아남는가

- 작성일: 2026-11-24 (env 기준)
- 방법: 원문 PDF를 내려받아 `pdftotext`로 본문·표를 직접 확인했다. 원문을 열 수 없는 항목은 저널·SSRN 초록 페이지를 쓰고 그 사실을 적었다.
- 인용은 출처당 15단어 이하로 제한하고 나머지는 요약했다.
- **판정 표기**
  - 확인: 최종 출판본으로 검증했다.
  - 확인(초안 기준): 워킹페이퍼나 초안으로만 검증했다. 최종본과 수치가 다를 수 있다.
  - 부분 확인 / 반박 / 확인 불가: 해당 항목 본문에서 근거를 밝힌다.
- **버전 주의**: 최종 출판본을 직접 확인한 것은 QMJ(RAST 2019)와 McLean & Pontiff(JF 2016) 두 편뿐이다. 나머지 수치는 모두 초안 또는 WP에서 가져왔다.

---

## 1. Novy-Marx (2013, JFE) "The Other Side of Value: The Gross Profitability Premium"

- **출처**
  - 원문 초안(2012년 6월): https://www.johnhcochrane.com/s/Novy_marx_OSoV.pdf
  - JFE 108(1):1–28 서지: https://ideas.repec.org/a/eee/jfinec/v108y2013i1p1-28.html
  - NBER WP 15940: https://www.nber.org/papers/w15940
- **판정: 확인(초안 기준)**

| 주장 | 원문 근거 | 판정 |
|---|---|---|
| GP/A가 수익률을 예측한다 | Table 1(Fama-MacBeth)과 Table 2. 가치가중, NYSE 브레이크포인트, 5분위, 1963.7–2010.12, 금융업 제외. 상위–하위 스프레드는 **월 0.31%, t=2.49**, FF3 알파는 **월 0.52%, t=4.49**(p.10) | 확인(초안) |
| 대형주·가치가중에서도 유지된다 | Table 4(규모×GP/A 독립 5×5, VW). 스프레드는 소형→대형 순으로 0.67 / 0.53 / 0.41 / 0.38 / **0.26(t=1.88)**. 최대형 분위의 FF3 알파는 **0.50(t=3.90)**, HML 베타는 −0.51 | 부분 확인 (아래 해석 참고) |
| 규모 분위별 증거 | 원문 표현: "spread is large and significant across size quintiles". 다만 스프레드는 규모가 커질수록 줄어든다. 최대형 분위의 원스프레드는 t=1.88로 5% 기준에 미달한다 | 확인(초안). 단, 대형주 원스프레드는 경계선 수준이다 |
| 국제 증거 | Table 5. 북미 외 선진국, 1990.7–2009.10. H–L **월 0.76%(t=2.25)**, 글로벌 FF3 알파 **0.99(t=2.97)**. 같은 기간 가치 스프레드는 0.51(t=2.12) | 확인(초안) |
| 회전율 | 각주 3: 연간 데이터 전략은 약 4년에 한 번 회전하며, 분기 데이터 전략의 1/4 수준이다. Table 7 전략은 각 사이드가 매년 1/3씩 교체된다 | 확인(초안) |

**Q-1에 가장 가까운 표: Table 7 Panel A**

- 구성: 금융업을 제외한 시가총액 상위 500종목을 GP/A로 3분위(150/200/150) 정렬했다. 본문은 종목당 1달러씩 매수한다고 기술하므로 사실상 동일가중이다. 기간은 1963.7–2010.12다.
- 하위 분위: 초과수익 월 0.38, FF3 α −0.15(t=−1.96)
- 중간 분위: 초과수익 월 0.57, FF3 α 0.00
- **상위 분위: 초과수익 월 0.65, FF3 α +0.25(t=3.84)**
- H–L: 월 0.27(t=2.33), α 0.40(t=3.75), HML 베타 −0.40
- 인용(p.13): "economically significant even among the largest, most liquid stocks."

---

## 2. Asness, Frazzini & Pedersen (2019, RAST) "Quality Minus Junk"

- **출처**: 출판본 PDF(CBS 리포지토리, CC BY), https://research-api.cbs.dk/ws/portalfiles/portal/60211462/lasse_heje_pedersen_et_al_quality_minus_junk_publishersversion.pdf (RAST 24(1):34–112, DOI 10.1007/s11142-018-9470-2)
- **판정: 확인 (최종본)**

**QMJ 알파 (Table 4, 미국 1957–2016)**

- 초과수익: 월 0.29(t=3.62)
- CAPM / 3F / 4F 알파: 0.39 / 0.51 / **0.60(t=9.95)**
- 글로벌 4F 알파: 0.61(t=8.07)

**대형주 증거**

- Table 15, NYSE 규모 10분위 중 미국 P10(최대형): 초과수익 **0.23(t=2.15)**, 4F α 0.66(t=7.23)
- Table 16 Panel B, 미국 대형주 QMJ: 초과수익 **0.16(t=1.75)**, 4F α 0.58(t=8.17)
- 해외 대형주는 국가별로 대부분 유의하지 않다. 예를 들어 영국 대형주 4F α는 −0.07이다

**롱/숏 레그 기여 (Table 14, 미국)**

| 레그 | 초과수익 | CAPM α | 4F α |
|---|---|---|---|
| 대형 고퀄리티(Big HQ) | 0.60 | 0.10(t=2.26) | **+0.28(t=8.22)** |
| 대형 정크(Big Junk) | 0.44 | −0.14 | **−0.30(t=−6.18)** |

- 대형주에서는 알파 기준으로 롱과 숏이 대략 절반씩 기여한다.
- 원수익 기준으로 대형 HQ − 대형 Junk는 월 0.16%에 불과하다.

**하위기간 (Table 15, 미국)**

- 2006–2016: 초과수익 0.32(**t=1.35**), 4F α 0.54(t=4.05)

**해석**

- 대형주에서는 알파가 강하지만 원스프레드는 약하다. 그 차이는 대부분 음의 시장 베타와 음의 HML 노출에서 생긴다.

---

## 3. Fama & French (2015, JFE) 5요인 RMW

- **출처**
  - 원문 초안(2013년 11월): https://www.johnhcochrane.com/s/FF_Five_Factor.pdf
  - JFE 116(1):1–22 서지: https://econpapers.repec.org/RePEc:eee:jfinec:v:116:y:2015:i:1:p:1-22
- **판정: 확인(초안 기준).** 최종본 표는 열지 못했다.

**RMW 프리미엄 크기 (Table 4, 1963.7–2012.12, 594개월)**

| 구성 방식 | 월평균 | t |
|---|---|---|
| 2×3 | 0.26% | 2.93 |
| 2×2 | 0.17% | 2.77 |
| 2×2×2×2 | 0.26% | 4.10 |

**소형주 RMW가 더 강한가**

- 2×3 기준: RMW_S 0.33(t=2.97), RMW_B 0.19(**t=1.94**), 차이 0.14(t=1.29)
- 저자들은 소형주 쪽이 크지만 그 증거는 약하다고 본다. 인용: "the evidence that the expected premium is larger is weak."
- 2×2×2×2 구성에서는 RMW_B 0.22(t=3.47)로 대형주에서도 유의하다.
- 결론적으로 "RMW가 소형주에서 더 강하다"는 주장은 **부분 확인**이다. 점추정치는 소형주가 크지만 차이는 통계적으로 유의하지 않다.

---

## 4. Novy-Marx & Velikov (2016, RFS) "A Taxonomy of Anomalies and Their Trading Costs"

- **출처**: NBER WP 20721(2014년 12월), https://www.nber.org/system/files/working_papers/w20721/w20721.pdf
- **판정: 확인(WP 기준).** RFS 최종본의 표 번호나 수치는 다를 수 있다.

**Gross Profitability 전략 (Table 3 Panel A)**

- 구성: VW, NYSE 브레이크포인트, 10분위 롱숏, 연 1회 재조정
- 총수익 월 0.40(t=2.94), 4F α 0.52(t=3.83)
- 회전율(TO) **1.96%/월**, 롱·숏 각 사이드의 평균(한쪽 기준)
- 거래비용 **0.03%/월**
- 순수익 **0.37(t=2.74)**, 순 4F α **0.51(t=3.77)**

**규모 구간별 결과 (Table 13 Panel A)**

| 구간 | 총수익 | 순수익 |
|---|---|---|
| Large (NYSE 중위수 이상) | 0.46(t=2.81) | **0.44(t=2.66)** |
| Small | 0.75 | 0.71 |
| Micro | 0.74 | 0.66 |

**한계**

- 비용은 유효 스프레드 추정치로, 소규모 유동성 수요자를 가정한다. 대형 기관의 가격충격은 무시한다(p.9 부근).
- 인용(초록): "anomalies… with one-sided monthly turnover lower than 50% continue to generate… significant net spreads."

---

## 5. Frazzini, Israel & Moskowitz "Trading Costs" (SSRN 2018)

- **출처**
  - SSRN 3229719: https://papers.ssrn.com/abstract=3229719 (WebFetch 시 403 오류)
  - AQR 페이지: https://www.aqr.com/Insights/Research/Working-Paper/Trading-Costs
  - 2012년 선행 초안 "Trading Costs of Asset Pricing Anomalies": https://conference.nber.org/conf_papers/f68262/f68262.pdf
- **판정: 부분 확인**

**2018판 (AQR 페이지 기준)**

- 데이터: 약 **1.7조 달러** 규모의 실거래 체결, 21개 선진시장, 19년
- 결론: 실제 비용은 기존 연구 추정치보다 한 자릿수 작다. 인용: "an order of magnitude smaller than previous studies suggest."
- bps 수치는 2차 자료에만 있으므로 **확인 불가(원문)**로 둔다.
  - 일중 체결 평균 시장충격 약 9bp: Alpha Architect 요약
  - 중앙값 약 6bp, S&P500 연간 비용 4.81bp: Swedroe(ETF.com) 요약

**2012 초안 (원문 확인)**

- 데이터: 약 1조 달러, 19개 시장, 1998–2011
- 대상 전략: size, value, momentum, 단기반전만 다룬다
- 미국 손익분기 펀드 규모: SMB 1,030억, HML 830억, UMD 520억 달러

**수익성·퀄리티 포함 여부**

- 2012 초안에는 포함되지 않았다(원문 확인).
- 2018판 본문에 포함됐는지는 **확인 불가**다.

---

## 6. McLean & Pontiff (2016, JF) "Does Academic Research Destroy Stock Return Predictability?"

- **출처**: 출판본 PDF, https://www.gwern.net/doc/economics/2016-mclean.pdf (JF 71(1), 2016년 2월, DOI 10.1111/jofi.12365)
- **판정: 확인 (최종본, 초록)**
- 표본: 예측변수 97개
- 표본외 수익 **26% 감소**, 출판 후 **58% 감소**
- 둘의 차이인 **32%**를 출판 정보에 기반한 거래 효과로 추정한다
- 인용: "Portfolio returns are 26% lower out-of-sample and 58% lower post-publication."
- 26%는 데이터마이닝 효과의 상한 추정치로 해석된다.

---

## 7. Hou, Xue & Zhang (2020, RFS) "Replicating Anomalies"

- **출처**
  - RFS 초록: https://academic.oup.com/rfs/article/33/5/2019/5236964
  - 표 수치: NBER WP 23394(2017년 5월), https://www.nber.org/system/files/working_papers/w23394/w23394.pdf
- **판정: 확인.** 초록은 최종본, Gpa 수치는 WP 기준이다.

**RFS 최종본 초록**

- 이상현상 452개 가운데, NYSE 브레이크포인트와 가치가중 기준에서 **65%가 |t|<1.96**이다. 거래마찰 범주는 96%가 실패한다.
- 다중검정 기준인 t=2.78을 적용하면 **82%가 실패**한다.
- WP 초록 기준으로는 447개 중 64%, t>3 기준 85%다.

**Gpa (Novy-Marx식, 당기 자산 분모)**

- 10분위 H–L **월 0.38%(t=2.62)**로, 1.96 기준은 통과한다.
- q-팩터 알파는 0.18(t=1.24)로 유의하지 않다. 투자요인이 설명한다는 해석이다.
- t>2.78 다중검정 기준으로는 탈락한다.

**Gla (전기 자산 분모)**

- **월 0.16%(t=1.04)**로 유의하지 않다(p.17 부근).
- 저자들은 Gpa 효과가 투자 효과와 섞여 있다고 본다. 인용: "the Gpa effect is confounded with the investment effect."
- FF RMW의 기반 변수인 Ope도 유의하지 않다고 보고한다.

**Q-1 함의**

- 분모를 당기 자산으로 하느냐 전기 자산으로 하느냐에 따라 결과가 달라진다.

---

## 8. Israel & Moskowitz (2013, JFE) "The role of shorting, firm size, and time on market anomalies"

- **출처**
  - AQR 요약 페이지(PDF에는 초록만 있음): https://www.aqr.com/Insights/Research/Journal-Article/The-Role-of-Shorting-Firm-Size-and-Time-on-Market-Anomalies
  - JFE 108(2):275–301 서지: https://ideas.repec.org/a/eee/jfinec/v108y2013i2p275-301.html
- **판정: 초록 수준의 주장은 확인, 표 수준 수치는 확인 불가.** 본문은 유료라 열지 못했다.

**초록 내용 (확인)**

- 롱 포지션이 size 수익의 거의 전부, value의 60%, momentum의 절반을 차지한다.
- 소형주로 갈수록 value에서는 숏의 중요성이 커지고, momentum에서는 작아진다.
- value 프리미엄은 규모가 커질수록 줄고, 최대형주에서는 약하다. 인용: "weak among the largest stocks."
- momentum은 규모와 신뢰할 만한 관계가 없다.

**대상 범위**

- size, value, momentum만 다룬다. **수익성은 다루지 않는다(확인).**

**수익성의 롱/숏 분해 대체 근거**

- QMJ Table 14: 대형 HQ 4F α +0.28, 대형 Junk −0.30. 대략 절반씩이다.
- Novy-Marx Table 7 Panel A: 상위 분위 α +0.25, 하위 분위 α −0.15. 롱 쪽이 약간 크다.

---

## 9. Martineau (2022, CFR) "Rest in Peace Post-Earnings Announcement Drift"

- **출처**
  - CFR 게재 초안 PDF: https://cfr.ivo-welch.info/published/papers/martineau2021rest.pdf (CFR 11(3–4):613–646)
  - 반론 정리: UCLA Anderson Review, https://anderson-review.ucla.edu/is-post-earnings-announcement-drift-a-thing-again
- **판정: 확인 (CFR 초안의 초록)**

**Martineau의 주장**

- 대형주 PEAD는 2006년 이후 존재하지 않는다. 인용: "For large stocks, PEAD have been non-existent since 2006"
- 마이크로캡에서는 최근에야 사라졌다.

**2025년 반론 (2차 자료 요약)**

- Hirshleifer, Peng & Wang (RFS 2025)과 Dickerson, Julliard & Mueller (JFE, in press)는 PEAD가 존속한다고 주장한다.
- 두 논문 모두 마이크로캡을 제외하지 않았다.
- Subrahmanyam(2025 WP)에 따르면 2001–2024년 표본에서 드리프트 t값은 전체 종목 2.18, 마이크로캡 제외 시 1.43이다.
- 즉 대형주에서 PEAD가 사라졌다는 결론은 반박되지 않았다.

---

## 10. 출판 이후(2014–2026) 수익성·퀄리티 팩터 성과

- **출처**
  - Ken French Data Library (2026.09 CRSP 기준 파일)
    - https://mba.tuck.dartmouth.edu/pages/faculty/ken.french/ftp/F-F_Research_Data_5_Factors_2x3_CSV.zip
    - https://mba.tuck.dartmouth.edu/pages/faculty/ken.french/ftp/6_Portfolios_ME_OP_2x3_CSV.zip
  - AQR QMJ Factors Monthly, 2026.08까지: https://www.aqr.com/-/media/AQR/Documents/Insights/Data-Sets/Quality-Minus-Junk-Factors-Monthly.xlsx
- **판정: 데이터로 확인.** 연도별 값은 원천 데이터의 수치이고, 기간 평균과 t값은 원천 데이터로 **필자가 직접 계산**했다.
- 이 기간을 요약한 신뢰할 만한 2차 논문은 찾지 못했다.

**French RMW 연간 수익률 (파일의 Annual Factors 행 원문 그대로, %)**

| 연도 | RMW |
|---|---|
| 2014 | 1.15 |
| 2015 | 0.98 |
| 2016 | 4.60 |
| 2017 | 4.53 |
| 2018 | −1.33 |
| 2019 | 3.81 |
| 2020 | −5.21 |
| 2021 | 26.72 |
| 2022 | 6.63 |
| 2023 | 6.19 |
| 2024 | 5.11 |
| 2025 | **−10.85** |

**French RMW 및 대형주 OP 포트폴리오 기간 평균 (필자 계산, 월 %)**

| 기간 | RMW 평균 | RMW t |
|---|---|---|
| 1963.7–2013.12 | 0.268 | 2.94 |
| 2014.1–2025.12 | 0.257 | 1.46 |
| 2014.1–2026.9 | 0.162 | 0.82 |

- 2026년 1–9월 RMW는 월수익률을 복리로 누적하면 약 −12.6%다(필자 계산).
- 대형 HiOP − 대형 LoOP(롱숏)
  - 1963–2013: 0.20(t=1.95)
  - 2014–2026.9: 0.28(t=1.21)
- 대형 HiOP − 대형 3개 포트폴리오 단순평균(롱온리 틸트의 근사치)
  - 전체 기간 1963.7–2026.9: 연 **1.38%**, 추적오차 **4.47%**, t=2.45
  - 2014–2026.9: 연 **1.92%**, 추적오차 4.86%, t=1.41
- 이 근사치의 한계: 정렬 변수가 OP/BE이고, 가치가중이며, 금융업이 포함된다. Q-1의 GP/A·동일가중 설계와 다르다.

**AQR QMJ (USA) (필자 계산)**

- 1957.7–2013.12: 월 0.340(t=4.14)
- 2014.1–2026.8: 월 0.253(t=1.01)
- 2017.1–2026.8: 월 0.107(t=0.35)
- 연도별 수익률: 2020년 −13.2%, 2021년 +18.7%, 2022년 +23.0%, 2023–2025년 −4.4%, −9.7%, −11.3%
- 주의: 파일의 전체 표본 평균(0.36)은 논문 Table 4 값(0.29)과 다르다. AQR 파일 자체에 데이터 출처와 방법론이 논문과 다를 수 있다고 명시돼 있다.

**요약**

- 출판 이후 수익성 팩터의 평균은 양(+)이지만 통계적으로 유의하지 않다.
- 2025–2026년에는 큰 손실이 났다.

---

## 11. Daniel & Moskowitz (2016, JFE) "Momentum Crashes"

- **출처**: NBER WP 20439(2014년 8월), https://www.nber.org/system/files/working_papers/w20439/w20439.pdf
- **판정: 확인(WP 기준)**

**2009년 손실 (Table 2, WML 최악의 월)**

| 월 | WML 수익률 | 최악 순위 |
|---|---|---|
| 2009년 4월 | **−45.52%** | 4위 |
| 2009년 3월 | −42.28% | 7위 |
| 2009년 8월 | −30.54% | 10위 |

- 2009년 3–5월에 과거 패자 10분위는 **+163%** 올랐고, 승자 10분위는 +8%, 시장은 +26%였다.
- 인용: "the past-loser decile rose by 163%"
- 손실은 숏 레그에서 주로 발생했다. 저자 표현으로는 패자가 위로 폭락("crashing up")한 것이다.

---

## 12. Chen & Zimmermann (2022, CFR) "Open Source Cross-Sectional Asset Pricing"

- **출처**
  - FEDS 2021-037 초록: https://www.federalreserve.gov/econres/feds/open-source-cross-sectional-asset-pricing.htm
  - 서지: CFR 11(2):207–264, https://ideas.repec.org/a/now/jnlcfr/104.00000112.html
- openassetpricing.com이 표기한 "27(2)"는 오기다.
- **판정: 확인 (FEDS판 초록)**

**복제율**

- 특성 319개를 대상으로 했다.
- 원 논문에서 명확히 유의했던 161개 중 **98%가 t>1.96**으로 재현됐다.
- 증거가 혼재했던 44개는 평균 t≈2다.
- 재현 t를 원 논문 t에 회귀하면 기울기 0.90, R² 83%다.
- 해석: 이 수치는 원 논문 방법론(주로 동일가중이나 전체 종목)을 그대로 재현한 결과다. HXZ의 대형주 친화적 설정(VW, NYSE 브레이크포인트)과는 질문이 다르다.

---

## Q-1(대형주·롱온리·분기 재조정 GP/A 상위 1/3 vs 동일가중 유니버스) 기대 효과 크기 근거

### A. 출처에서 확인된 사실

1. **대형주에서 강한 것은 알파이고, 원스프레드는 약하다.**
   - Novy-Marx Table 4, 최대형 분위: 원스프레드 0.26(t=1.88), FF3 α t=3.90
   - QMJ 미국 대형주: 초과수익 0.16(t=1.75), 4F α t=8.17
   - 둘의 차이는 음의 HML 노출과 음의 시장 베타에서 나온다.
   - 헤지하지 않은 롱온리 틸트가 실제로 얻는 것은 알파가 아니라 원스프레드에 가깝다.
2. **Q-1에 가장 가까운 표본 내 근거는 Novy-Marx Table 7 Panel A다.**
   - 구성: 상위 500종목, GP/A 3분위, 종목당 동일금액, 1963–2010
   - 상위 분위 0.65, 중간 0.57, 하위 0.38%/월
   - 상위 분위의 롱 쪽 FF3 α +0.25(t=3.84)
3. **거래비용은 제약이 아니다.**
   - NMV: GP 롱숏 10분위의 비용 0.03%/월, 회전율 1.96%/월. Large 구간 순수익 0.44(t=2.66)
   - Novy-Marx: 연간 데이터 전략은 약 4년에 한 번 회전한다.
4. **출판 후 감쇠가 있다.**
   - McLean–Pontiff: 평균 58% 감소
   - French RMW: 2014–2026.9 기간 t=0.82
   - AQR QMJ(USA): 2014–2026.8 기간 t=1.01
5. **신호 정의에 민감하다.** HXZ에서 Gpa는 t=2.62로 유의하지만, Gla는 t=1.04로 유의하지 않고 q-알파도 유의하지 않다.

### B. 필자의 추론 (출처의 직접 주장이 아님)

**1. 표본 내 기대 초과수익**

- Table 7 Panel A에서 유니버스의 동일가중 평균은 (0.38×150 + 0.57×200 + 0.65×150)/500 ≈ 0.537%/월이다.
- 상위 분위 − 유니버스 ≈ **+0.11%/월, 연 약 1.3–1.4%**(총수익 기준, 1963–2010)다.
- French의 대형 HiOP − 대형 평균도 전체 기간 연 1.4%로 비슷한 크기다.

**2. 출판 후 할인 적용**

- 58% 감쇠를 적용하면 연 **약 0.5–0.6%**다.
- 보수적 범위는 연 **0–1.5%**로 둔다.
- 2014년 이후 French 대형주 근사치는 연 1.9%였지만 t=1.41이라 신뢰구간이 넓다.

**3. 추적오차**

- 대형주 롱온리 틸트의 근사치는 연 **약 3.5–5%**다.
  - French 대형 HiOP − 대형 평균: 4.5–4.9%
  - BigHiOP − 시장: 3.5–3.8%
- 동일가중 유니버스를 벤치마크로 쓰고 3분위로 집중하면 이 범위의 상단에 가까울 가능성이 크다.

**4. 정보비율(IR)과 검증에 필요한 기간**

- 기대 IR은 **약 0.1–0.35**다.
- t=2를 얻는 데 필요한 기간은 (2/IR)²년이다.
  - IR 0.3이면 약 44년
  - IR 0.2이면 약 100년
- 따라서 실거래나 짧은 백테스트로 효과를 통계적으로 확인하는 것은 사실상 불가능하다.
- 이는 LL-1과 같은 "유의성 미달" 결론이 반복될 위험을 뜻한다.

**5. 분기 재조정**

- 연간 재무데이터를 쓰면 분기 재조정을 해도 회전율 증가가 미미하다.
- 분기 재무데이터를 쓰면 회전율이 약 4배로 늘어난다(Novy-Marx 각주 3).
- 비용은 어느 쪽이든 연 0.1–0.3%p 이하로 추정되며, 효과 크기 대비 부차적이다.

**6. 남는 위험**

- 성장주 틸트이므로 음의 HML 노출이 있어 가치주 랠리 시기에 부진하다.
- 2025–2026년 RMW와 QMJ가 동반 손실을 냈다.
- 위 근거는 미국 중심이다. QMJ Table 16에 따르면 해외 대형주는 국가별로 대부분 유의하지 않다.
- 분모 정의(Gpa와 Gla) 선택이 결과를 좌우할 수 있다.

**결론(추론)**

- Q-1은 비용을 빼도 생존할 가능성이 높은 저회전 틸트다.
- 그러나 기대 액티브 수익은 연 0.5–1.5%, 추적오차는 3.5–5%, IR은 0.1–0.35 수준이다.
- 이 정도 효과는 검출력이 매우 낮다. 사전에 효과 크기와 판정 기준을 등록하지 않으면 결과를 해석할 수 없을 가능성이 높다.
