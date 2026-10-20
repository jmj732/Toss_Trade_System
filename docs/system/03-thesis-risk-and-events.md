# 투자 논리, 리스크 게이트와 가격 이벤트

이 장은 저장된 투자 논리(thesis)가 제안·검증·승인되는 경로, 그 논리가 포트폴리오 위험 계산과 매수 사전점검에 쓰이는 조건, 그리고 승인된 가격 트리거를 감시해 이력과 알림으로 남기는 방식을 설명한다. 이 경로는
판단과 검토를 기록한다. 가격 도달만으로 투자 논리를 무효화하거나 주문을 만들지는 않는다.

주요 계약은 [투자 컨텍스트 REST 계약](../ops/investment-context-contract.md), [투자 컨텍스트 MCP
계약](../ops/investment-context-mcp-contract.md), [결정 계층 검증 계약](../ops/investment-os-decision-layer-verification.md)에서 확인할 수
있다. 아래 링크는 현재 구현과 데이터 제약을 가리킨다.

## 1. 제안과 승인된 투자 논리는 서로 다른 상태다

현재값은 사용자와 티커별 `investment_thesis_states` 한 행에 저장된다. 갱신 때마다 완전한 thesis snapshot, 이전·새 무효화 상태와 트리거 가격, 작성자 유형, 작성 시각, 근거 시각 및
사유를 `investment_thesis_revisions`에 새 revision으로 추가한다. 서비스는 thesis 변경 때 revision row를 새로 추가해 이력을 보존한다. DB migration은
actor·상태·revision 관계를 제약하며, AI 정책과 검증 이벤트는 DB trigger로 수정·삭제를 막는다. revision 작성은 사용자별 thesis writer 잠금과 트랜잭션 안에서 이뤄진다. 기존
proposal 수정은 `expectedUpdatedAt`으로 낙관적 동시성 충돌을 감지한다. 자세한 쓰기 경로는
[`InvestmentContextService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java),
작성자 구분은 [`ThesisActor`](../../trading-backend/src/main/java/com/jmj/trade/investment/ThesisActor.java), DB 제약은 [V57 revision
migration](../../trading-backend/src/main/resources/db/migration/V57__create_investment_thesis_revisions.sql)과 [V61 baseline
migration](../../trading-backend/src/main/resources/db/migration/V61__initialize_legacy_thesis_revision_baselines.sql)에 있다.

무효화 상태의 허용 enum은 `NOT_REVIEWED`, `SUSPECTED`, `CONFIRMED`, `CLEARED`, `AI_PROPOSED`, `UNVERIFIED`,
`INVALIDATION_UNDEFINED`다. `AI_PROPOSED`, `UNVERIFIED`, `INVALIDATION_UNDEFINED`는 제안·미확인 상태다. 계산 가능한 무효화 하방 위험 및 가격 감시 대상으로
인정되는 것은 양수 가격 트리거가 있는 `CONFIRMED` thesis뿐이다. 나머지 상태의 트리거 가격은 계산이나 감시에 사용하지 않는다. V57 이전 legacy thesis의 첫 revision은
`LEGACY_BASELINE`으로 초기화하며, 당시 작성자·검토 시각·근거 시각·proposal run ID를 알 수 없다고 명시한다. enum과 제약은
[V56](../../trading-backend/src/main/resources/db/migration/V56__investment_prompt_invalidation_statuses.sql) 및
[V61](../../trading-backend/src/main/resources/db/migration/V61__initialize_legacy_thesis_revision_baselines.sql), 현재 위험 계산은
[`InvestmentContextService.riskContributions`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java)에
정의돼 있다.

`put_investment_thesis` 커넥터/MCP 도구는 `AI_PROPOSED`, `UNVERIFIED`, `INVALIDATION_UNDEFINED`만 받아 저장한다. 기존 thesis를 수정할 때 현재
`expectedUpdatedAt`을 요구하며, 이미 `CONFIRMED`인 행은 이 제안 경로가 덮어쓰지 않는다. 이 도구는 `CONFIRMED`를 설정할 수 없고 서버에서 모델을 실행하거나 근거를 수집하지 않는다. 별도
사용자 세션 경로는 승인 조건을 만족할 때 직접 `CONFIRMED`를 기록할 수 있다. 처음으로 확정하거나, 확정 상태가 아니던 것을 확정하거나, 기존 확정 트리거 가격을 바꿀 때는 `sourceAsOf`와 현재 상태의
`expectedUpdatedAt`이 필수다. 현재 값과 일치하지 않으면 충돌로 거부된다. 커넥터 설명과 proposal 입력 제한은
[`ConnectorMcpProtocol`](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java)에, 확정 쓰기 조건은
[`InvestmentContextService.putThesis`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java)에
있다.

### 외부 AI 검증 정책

검증 도구는 호출자가 작성한 증거와 판정을 저장하고 Spring 서버가 설정된 정책을 적용한다. 서버는 AI 모델을 실행하거나 출처 URL 내용을 가져와 사실 여부를 확인하지 않는다. 배포 기본값은
비활성화(`investment.thesis.ai-policy.enabled=false`), 버전 `v1`, 증거 최대 나이 24시간, 최소 고유 출처 수 2개, 트리거의 기준 가격 대비 최대 하방 거리 25%다. 배포
설정만으로 켜는 경우에는 Investment OS Sheet가 켜져 있고 요청 사용자가 설정된 Sheet owner여야 한다. 사용자 세션에서 만든 정책 revision은 해당 사용자의 배포 기본 활성값을 대체하며,
임계값은 배포 설정에서 온다. API와 기본값은
[`InvestmentThesisVerificationService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentThesisVerificationService.java),
저장 제약은 [V60](../../trading-backend/src/main/resources/db/migration/V60__investment_thesis_ai_verification_policy.sql)에 있다.

검증 요청의 `verdict` enum은 `PASS`, `REJECT`; 저장 결과 `outcome` enum은 `AUTO_APPROVED`, `BLOCKED`, `REJECTED`다. `REJECT`는 정책 계산과 별개로
`REJECTED`를 남긴다. `PASS`는 다음 검사를 모두 통과할 때만 `AUTO_APPROVED`가 된다. 정책 검사가 하나라도 실패하면 `BLOCKED`와 이유 코드가 남는다.

- 제출한 proposal revision이 현재 revision과 같고 현재 상태가 `AI_PROPOSED`, `UNVERIFIED`, `INVALIDATION_UNDEFINED`여야 한다.
- 사용자 정책이 켜져야 하며 proposal revision에 기록된 `proposalRunId`가 있어야 한다. 검증 `verificationRunId`는 proposal run ID와 달라야 한다.
- `sourceAsOf`는 미래 시각이 아니고 정책의 증거 유효 기간 안이어야 한다. URL은 HTTPS, 호스트 필수, user-info·fragment 금지 조건을 통과해야 하며 중복 URL은 입력 오류다. 서버는 내용의
  진위나 출처 독립성을 조회하지 않는다. 설정된 수 이상의 고유 URL이어야 통과한다.
- 선택 트리거 가격이 양수이고 있어야 한다. 저장된 가격 사실에서 뽑은 신뢰 가능한 risk mark보다 낮아야 하며, 그 mark로 나눈 하방 거리가 정책 한도 이하여야 한다.

주요 차단 코드에는 `STALE_PROPOSAL_REVISION`, `THESIS_NOT_PROPOSABLE`, `AI_POLICY_DISABLED`, `PROPOSAL_RUN_ID_MISSING`,
`PROPOSAL_AND_VERIFICATION_RUN_MATCH`, `VERIFICATION_EVIDENCE_STALE`, `VERIFICATION_SOURCES_INSUFFICIENT`,
`MISSING_TRIGGER_PRICE`, `TRUSTED_PRICE_<status>`, `TRIGGER_NOT_BELOW_TRUSTED_PRICE`, `TRIGGER_DISTANCE_EXCEEDS_POLICY`가 있다.
명시적 거절은 `EXTERNAL_VERIFICATION_REJECTED`, 정책 통과는 `AUTO_APPROVAL_POLICY_PASSED`로 기록된다.

정책 통과 시 같은 트랜잭션에서 그 revision을 `CONFIRMED`로 새로 기록한다. 결과 receipt는 검증 이벤트 ID, proposal revision ID, outcome, 이유, policy
version, 승인된 revision ID와 번호를 포함한다. 현재 thesis가 `AI_POLICY` 작성자이면 매수 사전점검과 onboarding은 receipt가 존재하는지만 보지 않고,
`AUTO_APPROVED`인지와 이벤트 ID·승인 revision ID·revision 번호·policy version이 현재 행과 모두 일치하는지 확인한다. 검증 이벤트 ID를 재사용한 동일한 정규화 요청은 원래 결과를
돌려주고, 다른 사용자 또는 다른 본문으로 재사용하면 충돌이다. 정책과 검증 receipt는 수정·삭제할 수 없도록 저장된다. 구현과 [V60
migration](../../trading-backend/src/main/resources/db/migration/V60__investment_thesis_ai_verification_policy.sql)을 함께
참조한다.

### Telegram 2단계 승인

사용자 세션이 Telegram 승인 요청을 만들면 트리거는 요청 본문에서 받지 않는다. 서버가 기존 proposal 또는 검증된 계산 후보(`EXISTING_PROPOSAL`, `COMPUTED_ATR`,
`COMPUTED_SUPPORT`)를 선택하고 해당 후보의 입력, 근거 시각, thesis의 예상 갱신 시각을 저장한다. 요청 상태는 `PENDING` → `AWAITING_CONFIRM` → `APPROVED`이며 보류,
만료, 새 요청으로 대체, 충돌, 발송 실패는 각각 `HELD`, `EXPIRED`, `SUPERSEDED`, `CONFLICT`, `FAILED`로 기록된다. 첫 승인 버튼은 한 번 쓰는 확인 토큰을 발급할 뿐
thesis를 바꾸지 않는다. 두 번째 최종 승인 때 저장 당시 thesis 시각을 다시 검사하고, 변경되지 않았으면 `TELEGRAM` 작성자로 `CONFIRMED` revision을 쓴다. 두 번째 단계의 실패는
승인·thesis 쓰기를 롤백한 뒤 실패 상태로 남긴다. 이 경로도 주문을 만들지 않는다. 처리된 Telegram update ID는 중복 처리 방지 테이블로 보호하며, DB에는 토큰 원문 대신 SHA-256 해시가
저장된다. 구현은
[`TelegramApprovalService`](../../trading-backend/src/main/java/com/jmj/trade/investment/TelegramApprovalService.java), 상태와
제약은 [V59](../../trading-backend/src/main/resources/db/migration/V59__telegram_thesis_approval.sql)에 있다.

## 2. Risk mark와 시장 캘린더

리스크 계산은 최신 저장 snapshot의 `latestPrice`와 별도로 판정된 정규장 종가를 입력으로 받는다. quote와 close를 서로 복사하거나 하나의 필드처럼 취급하지 않는다. 기본 신선도 설정은 일반
quote 15분, 정규장 종가 7일이다. 세션이 있는 quote는 `LIVE_REGULAR`이면 15분, `REGULAR_CLOSE`이면 7일 기준으로 freshness를 재평가한다. 세션 라벨 없는 quote는
provider가 기록한 거래 interval을 기준으로 한다. 그 시각이 열린 interval 밖이고 캘린더 증거가 없으면 `UNVERIFIED`다. 다음 provider 거래 interval 시작 시각이 있으면 그때까지
저장된 quote를 유지하며 시작 이후에는 `STALE`로 만든다. 기간 설정과 freshness 재평가는
[`InvestmentContextService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java)에
있다.

[`InvestmentRiskMarkSelector`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentRiskMarkSelector.java)는
다음 규칙으로 위험 계산용 mark를 선택한다.

1. quote 상태가 `SOURCE_CONFLICT`이면 대체 mark를 고르지 않고 같은 충돌 상태로 중단한다. quote가 `OK`이면 양수 값, 시각, 출처가 있고 미래 시각이 아니어야 한다. 세션이
   `REGULAR_CLOSE`면 별도의 검증된 정규장 종가 조건도 만족해야 한다. 그 외 `OK` quote는 `LATEST_QUOTE` basis로 사용한다.
2. quote가 `PARTIAL` 또는 `STALE`일 때는 TOSS quote와 캘린더 증거가 있는 폐장 구간인지 판정한다. bounded interval 증거를 쓰는 경우 캘린더 출처도 TOSS여야 하고 캘린더 관측 시각이
   미래가 아니어야 한다. `now`는 시작 이후, 종료 전이어야 한다. 옛 snapshot은 `TOSS_QUOTE_OUTSIDE_DECLARED_INTERVALS`와 다음 interval 시작 시각 조건을 함께 만족해야
   한다.
3. 이 fallback에서도 실제 mark는 검증된 TOSS 정규장 종가다. 값이 양수이고 상태 `OK`, 관측 시각이 미래가 아니어야 한다. 종가 날짜는 provider의 마지막 완료 세션 날짜와 같고 뉴욕 시간 기준 종가
   날짜와도 같아야 한다.

선택 결과는 값뿐 아니라 `asOf`, 출처, basis, as-of basis, status, reason을 함께 노출한다. 이 판정은 저장된 facts만 재평가하며 provider를 새로 호출하지 않는다. 매수
사전점검은 `OK` mark만 통과시킨다. 가격 출처 충돌, 불명확한 세션, 오래된 값 또는 증거 누락은 금액을 추정하지 않고 차단 이유로 남긴다. 반면 가격 이벤트 감시는 별도 용도로 실시간 regular-session
quote 또는 마지막 완료 정규장의 검증된 TOSS 종가를 사용할 수 있다. 종가 기반 이벤트는 알림·검토용이며 이를 sizing mark로 승격하지 않는다. 이벤트용 판단은
[`MonitoringThesisTriggerDetector`](../../trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringThesisTriggerDetector.java)에
있다.

## 3. 위험 기여도와 매수 사전점검

확정된 트리거와 신뢰 가능한 현재 mark가 있으면 무효화 하방 비율은 `invalidationDownside = (mark - trigger) / mark`, 개별 계획 손실 기여도는
`plannedLossContribution = portfolioWeight × invalidationDownside`로 계산한다. 두 계산 결과는 소수점 이하 8자리로 반올림한다. mark가 양수가 아니거나, 트리거가
없거나 음수이거나 현재 mark보다 높으면 하방 값은 `null`이다. 특히 트리거가 현재가보다 높으면 이미 가격을 넘은 것으로 보고 `INVALIDATION_PRICE_BREACHED`를 이유로 남긴다. 미확정 트리거,
신뢰할 수 없는 mark, 누락된 포트폴리오 비중에서 수치를 지어내지 않는다. 계산식은
[`InvestmentDataCalculator`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentDataCalculator.java), 사용
조건과 budget 판정은
[`InvestmentContextService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java)에
있다.

사용자별 soft risk budget은 `RiskPolicyService`에 저장하며 범위는 0 초과 1 이하의 비율이다. `thesisFailureStress`는 보유 중인 모든 종목의
`plannedLossContribution` 합이다. 합계는 보유 종목이 하나 이상이고 모든 종목의 계획 손실 값이 존재할 때만 계산한다. 하나라도 값이 빠지면 알려진 손실만 더하거나 결측을 0으로 취급하지 않고 전체
합계를 `null`로 둔다. 합계 값은 있어도 aggregate status가 `STALE`이면 budget 비교는 평가하지 않는다. 정책이 있으면 `softBudgetStatus=DATA_MISSING`, 없으면
`NOT_CONFIGURED`다. 완전하고 status가 `OK`인 합계만 budget과 비교해 이내면 `WITHIN_SOFT_BUDGET`, 초과면 `OVER_SOFT_BUDGET`이 된다. 따라서 개별 종목 risk
status가 `OK`여도 전체 보유 위험의 누락·stale, 또는 budget 미설정으로 인해 종목별 `sizingEligible`이 false일 수 있다. 종목별 위험 기여도는 시장 mark, thesis 승인, 보유
비중, 포트폴리오 freshness를 포함한 값이다. 두 종목 상관 기반 top-two stress도 표시될 수 있지만 현재 preflight의 직접 blockers는 아니다.

DTO의 boolean `risk.sizingEligible`과 문자열 `securities[].sizingEligibility`는 구분한다. boolean은 현재 thesis가 `CONFIRMED`이고
포트폴리오·mark·비중·하방·손실 요건 및 `WITHIN_SOFT_BUDGET`을 만족하는지 나타낸다. security 문자열은 `NO`(thesis 없음 또는 미확정), `YES`(확정 thesis와 true
boolean), `CONDITIONAL`(확정 thesis지만 boolean false)로 변환한 표시용 값이다. `CONDITIONAL`은 계산이 허용됐다는 뜻이 아니며, boolean eligibility와 혼용하면
안 된다. DTO 변환은
[`SecurityView.getSizingEligibility`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java),
계산은
[`InvestmentContextService.riskContributions`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java)에
있다.

`preflight_investment_risk(ticker, quantity)`는 읽기 전용 repeatable-read 사전점검이다. 동일한 snapshot을 기준으로 현재 위험과 제안 수량을 반영한 pro-forma
위험을 계산한다. pro-forma에서는 mark와 수량으로 구매액을 만들고, 기존 보유분에 수량·시장가치를 더하거나 새 ticker 포지션을 넣어 비중·위험을 재계산한다. 이 결과는 가정 계산이며 포트폴리오나 주문을
수정하지 않는다. MCP `tools/list`에서는 이 도구가 `canTrade` 조건 밖에 등록되고 일반 read-tool helper를 사용하므로 `connector:read` scope를 요구한다. trade
권한이나 주문 실행 권한을 요구하는 도구가 아니다.
[`ConnectorMcpProtocol`](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java)의 도구 목록·기본
scope와
[`InvestmentRiskPreflightService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentRiskPreflightService.java),
같은 snapshot을 만드는
[`InvestmentContextService.riskPreflightData`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java)를
참조한다.

`buyEligible`은 blockers가 비었을 때만 true다. blockers는 다음을 포함한다.

- **포트폴리오:** 위험 수치의 사용 가능성, 전체 현재 보유 위험의 완전성, 수기 포트폴리오 freshness. 주요 코드: `PORTFOLIO_RISK_UNAVAILABLE`,
  `HELD_POSITION_RISK_INCOMPLETE`, `MANUAL_PORTFOLIO_STALE`.
- **Thesis 승인:** 현재 thesis 및 revision 존재, `CONFIRMED`, 승인 origin 검증. `AI_POLICY` 승인에는 현재 revision과 일치하는 AUTO_APPROVED receipt가
  필요하다. 코드: `THESIS_MISSING`, `THESIS_NOT_CONFIRMED`, `THESIS_REVISION_MISSING`, `APPROVAL_ORIGIN_UNVERIFIED`,
  `AI_APPROVAL_RECEIPT_MISSING_OR_MISMATCHED`.
- **위험값과 정책:** mark 사용 가능성, 보유·pro-forma 위험 완전성, 현재와 pro-forma soft budget, 수량 한도, 통화별 주문 금액 한도, 최대 집중도. 코드에는
  `RISK_MARK_UNAVAILABLE`, `CURRENT_SOFT_BUDGET_EXCEEDED`, `PROFORMA_SOFT_BUDGET_EXCEEDED`, `MAX_QUANTITY_EXCEEDED`,
  `MAX_ORDER_AMOUNT_EXCEEDED`, `MAX_CONCENTRATION_EXCEEDED` 등이 있다. 정책값 누락이나 계산 불가도 허용으로 간주하지 않는다.
- **현금과 통화:** 통화 분류, 구매액 산정, 보고된 통화별 현금 잔액. 코드: `CURRENCY_MISSING`, `ORDER_AMOUNT_UNEVALUATED`, `CASH_UNAVAILABLE`,
  `INSUFFICIENT_CASH`.

`PortfolioGate`, `PolicyGate`, `CashGate`는 각각 `OK`/`BLOCKED`, `WITHIN_POLICY`/`BLOCKED`/`POLICY_UNAVAILABLE`,
`SUFFICIENT`/`INSUFFICIENT`/`UNAVAILABLE` 상태와 계산 근거를 반환한다. `buyEligible`은 주문 승인, 브로커 가용성, 실시간 체결 검증을 뜻하지 않으며 주문을 생성하거나 제출하지
않는다. `soft`라는 이름에도 불구하고 이 사전점검 경로는 budget 초과 시 신규 매수 eligibility를 차단한다.

### 투자 후보 onboarding

`readiness`는 기존 저장 데이터와 scheduled pipeline 상태, thesis·receipt, risk mark, 계산 후보의 준비 상태만 읽는다. provider 호출이나 상태 쓰기를 하지 않는다.
`READY`는 status, pipeline 성공 시각, thesis `CONFIRMED` 여부, AI receipt의 revision 일치, sizing eligibility, risk mark, 후보 계산 결과를 함께
확인한 값이다. 여러 blocker가 함께 반환될 수 있다. 정확한 blocker 합성은
[`InvestmentOnboardingService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentOnboardingService.java)에
있다. `register`는 ticker를 기존 monitoring watchlist에 idempotent하게 추가해 다음 scheduled collection 대상에 넣는다. 이 작업은 즉시 데이터 수집·리서치·주문을
실행하지 않는다. MCP 도구 설명은
[`ConnectorMcpProtocol`](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java)을 참조한다.

## 4. 확정 트리거 가격 이벤트, 저장과 이력

Monitoring 실행은 보유 수량이 양수인 ticker 중 `asOf`가 있는 포트폴리오에 대해서만 확인한다. thesis 저장소에서 현재 `CONFIRMED`이고 양수 트리거 가격이 있는 행만 감시한다.
`LIVE_REGULAR` 가격이 트리거 이하이면 `INTRADAY_BREACH`, 마지막 완료 세션의 검증된 정규장 종가가 트리거 이하이면 `PRICE_RISK_BREACH`다. 트리거보다 높으면서 트리거의 103%
이하이면 `NEAR`; 103% 초과는 `NORMAL`이다. 미확정 thesis, 미보유 ticker, stale/missing portfolio snapshot, 검증되지 않은 가격은 이벤트를 만들지 않는다. 구체적인
session 검사는
[`MonitoringThesisTriggerDetector`](../../trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringThesisTriggerDetector.java),
scheduler 흐름은
[`MonitoringCoordinator`](../../trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringCoordinator.java)에 있다.

`THESIS_REVIEW` scope의 current row는 현재 상태와 가장 최근 observation을 제공하고, `monitoring_state_history`는 실제 상태 전이마다 event UUID, 이전·새
상태, evidence, 관측 시각을 append-only로 저장한다. 활성 상태는 `NEAR`, `INTRADAY_BREACH`, `PRICE_RISK_BREACH`다. 활성 상태에서 정상 범위로 돌아오면
`RECOVERED` 전이를 남긴다. 이력이 회복 후 자동 삭제되지는 않는다. 상태 전이의 severity는 `NEAR`=`WARNING`, 두 breach=`CRITICAL`, `RECOVERED`=`INFO`다.

이력의 `sourceAsOf`는 가격 데이터 시각이고 `observedAt`은 감시 평가가 기록한 시각이며 `persistedAt`은 DB 기록 시각이다. 같은 상태의 반복 관측은 current evidence 안의
`latestObservation`과 관측 시각만 갱신하고 새 이력 이벤트·Telegram 알림을 만들지 않는다. 더 오래된 observation은 무시한다. 같은 사용자·ticker current row를 `FOR
UPDATE`로 잠근 뒤 상태를 비교하므로 동시 monitoring 실행은 순서대로 반영된다. 활성 incident에서는 incident ID를 재사용하고 정상 상태 뒤 새 incident가 시작되면 새 ID를 만든다.
전이 event ID와 `user|ticker|incident|transition|observedAt` dedup key는 history와 outbox를 연결한다. history에는 `(user, scope,
sourceType, sourceEventId)` unique key가 있고 migration trigger가 수정·삭제를 거부한다. 구현은
[`MonitoringStateStore.observeThesisRisk`](../../trading-backend/src/main/java/com/jmj/trade/monitoring/MonitoringStateStore.java),
제약은 [V47](../../trading-backend/src/main/resources/db/migration/V47__create_continuous_monitoring_state.sql)과
[V62](../../trading-backend/src/main/resources/db/migration/V62__investment_risk_event_links.sql)에 있다.

상태 전이와 `MONITORING_ALERT` outbox row는 같은 DB transaction에서 commit된다. outbox는 event UUID를 source ID로 사용하며 `(event_type,
source_id)` 충돌 시 재삽입하지 않는다. Telegram 전송은 별도 worker가 재시도한다. 따라서 DB에서 전이·알림 의도가 함께 보장되지만 외부 Telegram 전달이 같은 transaction에서
일어난다는 뜻은 아니다. outbox writer는
[`NotificationOutboxWriter`](../../trading-backend/src/main/java/com/jmj/trade/notification/NotificationOutboxWriter.java),
Telegram 전달 상태 테이블은
[V48](../../trading-backend/src/main/resources/db/migration/V48__create_telegram_notification_deliveries.sql)에 있다. 알림
payload는 자동 thesis 변경 없이 review 알림이고 `orderAction=NONE`을 명시한다. 가격 이벤트가 thesis를 확정·취소하거나 주문을 제출·취소하지 않는다는 점은 detector와
[`InvestmentRiskEventService`](../../trading-backend/src/main/java/com/jmj/trade/monitoring/InvestmentRiskEventService.java)에
정의돼 있다.

### 이벤트 읽기와 결정 연결

`get_investment_risk_events`는 인증 사용자 자신의 이벤트만 읽는다. 필터는 ticker, 시작 시각, unresolved 포함 여부, page size(기본 50, 최대 100), cursor다.
반환에는 페이지 이벤트, ticker별 최신 이벤트, unresolved 이벤트, `nextCursor`, `hasMore`, `asOf`, `OK` 또는 필수 evidence 누락이 있는 `PARTIAL`,
`missingFields`가 포함된다. unresolved 조회는 current 상태가 활성인 ticker를 대상으로 하며 그 상태의 `latestObservation`도 포함한다. MCP schema와
user-scoped query는
[`ConnectorMcpProtocol`](../../trading-backend/src/main/java/com/jmj/trade/connector/ConnectorMcpProtocol.java),
[`InvestmentRiskEventService`](../../trading-backend/src/main/java/com/jmj/trade/monitoring/InvestmentRiskEventService.java)에
있다.

`append_investment_decision` 요청은 선택적으로 risk event ID를 전달한다. 서버는 event가 같은 사용자 소유이고 scope가 `THESIS_REVIEW`이며 decision과
ticker가 같고 event evidence에서 thesis revision ID를 읽을 수 있는지 검증한 뒤 append-only 연결을 쓴다. 연결은 (사용자, event, decision) 조합별 한 번이며 기존
decision ID를 재사용할 때는 본문과 연결 ID가 모두 같아야 idempotent replay다. 읽기 응답은 연결된 decision의 ID, `asOf`, ticker/action/session/horizon,
rationale, invalidation, next-review trigger, confidence, thesis revision 및 연결 시각을 붙인다. 한 event당 최신 5개까지만 보여 주며 더 있으면
`relatedDecisionsTruncated=true`; 입력은 최대 20개 event ID를 허용한다. 연결이 없으면 `decisionLinkStatus=PRIOR_DECISION_UNAVAILABLE`다. 기존 과거
결정은 자동 연결하지 않는다. 저장은 [`InvestmentContextService.recordDecision` /
`linkRiskEvents`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java), append-only
FK와 unique key는 [V62 migration](../../trading-backend/src/main/resources/db/migration/V62__investment_risk_event_links.sql)에
있다.

## 5. 위험 결정에서 방어 매도 준비까지

위험 이벤트는 여전히 행동을 고르지 않는다. 매도 의도가 있으면 caller가 `append_investment_decision`에 `REDUCE` 또는 `EXIT`, event 하나의 `riskEventIds`, `targetRemainingQuantity`, `targetBrokerConnectionId`를 함께 기록한다. 두 target 필드는 함께 생략하거나 함께 제공한다. target을 제공할 때 event도 정확히 하나여야 하고, 해당 계좌는 같은 사용자의 active Toss connection이어야 한다. `EXIT` target은 0, `REDUCE` target은 양수다. 이 값은 caller가 작성한 정책 결과이며 서버가 CRITICAL severity만으로 행동·목표 보유량을 선택하지 않는다. V63은 이 optional target과 connection을 ledger에 추가하고 기존 행은 `NULL`로 유지한다. 규칙은 [`DecisionInput` 정규화](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentContextService.java), [V63 migration](../../trading-backend/src/main/resources/db/migration/V63__add_investment_decision_target_quantity.sql)에 있다.

`get_investment_sell_readiness`는 `decisionId`와 `eventId` UUID만 받는 읽기 도구다. 사용자 소유 decision/event 링크, `REDUCE`/`EXIT`, decision과 event의 신선도(기본 15분), 현재 미해결 이벤트와 동일한 thesis revision, decision이 더 새로운 account-scoped decision에 의해 대체되지 않았는지를 검사한다. 선택한 연결의 계좌 snapshot만 이용하므로 오래된 통합 계좌가 계좌별 매도를 대신 승인하지 않는다. 해당 계좌 snapshot이 stale/partial이거나 보유·매도 가능 수량·관측 시각이 빠지거나 오래되면 sizing을 막는다. 산식은 `quantity = current position quantity - targetRemainingQuantity`다. 결과가 0 이하이거나 매도 가능량보다 크면 차단한다. 소수점 매도량은 주문 단위로 반올림하지 않고 `FRACTIONAL_LIMIT_SELL_UNSUPPORTED`로 차단한다. 따라서 소수점 보유량 전량 매도도 브로커가 지원하지 않는 한 준비할 수 없다.

열린 주문 조회 실패, 같은 종목의 OPEN 주문, 기존 준비/진행 중 제안도 별도 blocker다. 가격 준비는 Toss order book의 bid/ask, quote timestamp와 provider observation timestamp, 미국 시장 캘린더를 함께 확인한다. 종목·통화가 맞고 최대 quote age(기본 30초) 안이며, order book과 현재 시각이 모두 검증된 `LIVE_REGULAR`여야 한다. 준비 limit은 bid이며 `ask - bid` 및 spread bps를 계산한다. spread 최대값은 설정돼야 하고 초과하면 차단한다. 종목별 price increment도 HTTPS 출처와 `verified=true` 설정이 있어야 하며 bid가 increment에 맞아야 한다. 미설정/미검증 increment 또는 spread 정책은 추정하지 않고 blocker로 돌려준다. 실제 Toss order book 계약 참조는 [Toss OpenAPI specification](https://openapi.tossinvest.com/openapi-docs/latest/openapi.json)이다. 이 경로가 별도 실시간 시세 공급자를 사용한다고 문서화하지 않는다.

응답은 `actionStatus`, `sizingStatus`, `pricingStatus`, `prepareStatus`, 계산 `quantity`, `targetRemainingQuantity`, `bid`, `ask`, `limitPrice`, spread, session/source, quote as-of와 provider observed-at, `blockers`, `asOf`를 제공한다. 차단 단계에 따라 숫자 필드는 null일 수 있다. `append_investment_decision`은 계속 caller-authored 기록이고 `get_investment_sell_readiness`는 provider를 읽지만 저장하지 않는다.

`prepare_order`는 기존 입력을 유지한다. `decisionId` 또는 `eventId`를 risk-linked defensive 경로로 보낼 때는 두 ID가 모두 필요하고, `SELL` + `LIMIT`만 허용된다. 이 경로는 transaction과 owned-connection row lock 안에서 readiness를 다시 평가하고 입력 ticker·수량·가격이 최신 계산과 일치하는지 검사한다. 통과하면 V64 order intent에 decision/event UUID를 연결해 proposal을 저장하고 `proposalId`를 반환한다. `submit_order`에서도 연결된 proposal의 action, 수량, 가격, event와 revision을 다시 검증한 뒤 기존 명시적 승인/실행 경로를 탄다. event-linked 경로를 선택할 때는 두 ID를 함께 제공해야 한다. 둘 다 생략하면 기존 generic `prepare_order` 계약을 사용하며, 그 제안에는 이 decision/event 연결을 증명하는 audit link가 없다. 제안은 실제 제출이 아니다. 구현은 [`InvestmentSellReadinessService`](../../trading-backend/src/main/java/com/jmj/trade/investment/InvestmentSellReadinessService.java), [`McpOrderExecutionService`](../../trading-backend/src/main/java/com/jmj/trade/order/McpOrderExecutionService.java), [V64 audit-link migration](../../trading-backend/src/main/resources/db/migration/V64__link_defensive_order_proposals.sql)에 있다. Toss adapter가 만드는 order는 `DAY` time-in-force다.

가격 이벤트 시각도 구분해 읽는다. `sourceAsOf`는 가격 공급자의 시각 또는 거래일 label일 수 있고, `observedAt`은 서버가 상태를 평가/전이한 시각, `persistedAt`은 저장 시각이다. 정규장 종가는 `regularCloseSessionDate`, 마지막 완료 거래일, close status/reason으로 별도 검사한다. 이 구조에서 같은 거래일이라는 사실만으로 source observation이 정규장 마감 뒤였거나 provider가 종가를 최종 확정했다고 증명되지는 않는다. `sourceAsOf`가 bell 이전인 기록은 값이 틀렸다고 단정하지 말고 timestamp 의미·timezone·완료 session 및 provider의 finality 근거를 조사한다. 현재 저장 contract에는 provider의 독립 `finalized` flag가 없다. 장중 `INTRADAY_BREACH`와 완료 close에 근거한 `PRICE_RISK_BREACH`는 계속 다른 상태다.

체결 조회도 독립 fill endpoint와 혼동하지 않는다. `get_recent_fills`는 OPEN/CLOSED order의 cumulative `filledQuantity`에서 fill view를 만들고, 같은 `brokerOrderId`의 repeated snapshot을 축약한다. OPEN/CLOSED 양쪽에 같은 주문이 있으면 identity와 단조 증가 조건을 검증하고 CLOSED snapshot만 쓴다; 양을 합산하지 않는다. 체결량은 주문별 누적량이지 부분 체결별 실행 원장이 아니다. `since` 필터에서 `filledAt`이 없는 항목은 제외된다. Toss 예외는 MCP `TOSS_API_ERROR`와 provider retry flag로 반환되고, snapshot identity/cumulative conflict는 `CONNECTOR_ERROR`로 실패한다. 어떤 조회 실패도 성공한 빈 배열로 간주하지 말고 `get_orders` OPEN 결과 0건만으로 체결 0건을 추정하지 않는다. exact fill history API 및 안정적인 fill IDs는 별도 미구현 경계다.

### OPENING 예약 실행 provenance (P07)

저장소에는 ChatGPT Scheduled Task를 읽거나 수정하는 API, 실제 task ID/revision, prompt 내용, 실행 tool 권한을 증명하는 런타임 registry가 없다. 따라서 보고서에 적힌 `OPENING v1.5`와 편집 prompt의 `v1.5.1`만으로 실제 실행 prompt 또는 schedule revision을 확정할 수 없다. 실제 Task 설정과 실행 artifact로 확인되지 않은 값은 `UNVERIFIED`로 기록한다. 저장소 prompt 파일이나 connector tool schema가 Task에 자동 반영됐다고 추정하지 않는다.

외부 실행 감사에는 적어도 실제 task ID와 `scheduleRevision`, 정확한 `promptVersion`(가능하면 prompt hash), unique `runId`, scheduled/started/finished timestamp(UTC; ET는 `America/New_York`로 변환), 선택 connection, 호출한 MCP tool/인수 식별자, 결과·typed error·retryable 상태를 같은 run record에 캡처하는 것을 권장한다. 이들은 현재 Task API 또는 backend audit table이 보장하는 필드가 아니라 run 운영용 권고다. repo는 task 설정을 조회·수정하거나 위 run metadata를 자동 영속화하지 않는다.

## 6. 읽을 때 기억할 경계

- AI 검증 결과는 외부 호출자가 제출한 판정·출처 주장에 대한 서버 정책 결과다. 서버는 URL의 형식과 수, 시각, revision·run 연결, 저장 mark 대비 trigger 조건을 검사하지만 출처 문서나 투자
  논리의 사실을 독립 조사하지 않는다.
- Context, verification, onboarding, risk preflight, risk-event read는 저장된 snapshot을 사용한다. 읽기 시점에 provider를 새로 조회하지 않으므로
  quote·portfolio·pipeline freshness가 부족하면 차단되거나 `PARTIAL` 결과가 될 수 있다.
- soft budget과 preflight는 위험·수량·가격·cash 조건을 보여 주는 eligibility gate다. 주문 생성·제출이나 브로커의 최종 주문 거절 이유를 대신하지 않는다. 실제 체결 경로에는 별도 사전
  제출 및 브로커 검증이 있다.
- 확정 트리거의 breach는 검토 이벤트다. 사람이 후속으로 thesis를 수정하거나 주문을 별도로 결정해야 한다. 이벤트 전이는 thesis 상태, trigger 가격, 보유 수량 또는 주문 ledger를 자동으로 바꾸지
  않는다.
- risk event history는 상태 전이 기록이다. 같은 상태 안에서 바뀐 observation은 current row의 `latestObservation`에 갱신되므로 모든 quote 관측값의 별도 이벤트 이력을
  의미하지 않는다.
