# 잔여 작업 마스터 플랜 — Release Candidate 상태

최종 갱신: 2026-08-03

기존 문서는 2026-07-31의 오래된 브랜치·커밋·미구현 목록을 기준으로 작성되어 현재 코드와 불일치했다. 이 문서는 현재 RC의 완료 범위, 잔여 위험, 운영 전제만 기록한다.

## 완료 범위

- 주문 원장·전이·outbox/inbox 멱등성 및 모듈 경계 검증.
- 계좌/시장데이터 재검증, sellable quantity, broker 주문 계약.
- provider 수집, stock/portfolio analysis, Forecast, Gemini 설명 정책 및 degrade 경로.
- market event 수집·영향 분석·재평가 흐름.
- 승인 step-up·표시값 대조, kill switch 원장/API, UNKNOWN 조정·수동해제 절차.
- real-order canary의 preflight와 mock submit → observe → cancel → reconcile 안전 경계.
- production readiness/provider probe, Prometheus 예측 관측성, backup/restore, staging deploy/rollback 드릴.
- RC 증적: [`release-candidate-acceptance-2026-08-03.md`](../docs/ops/release-candidate-acceptance-2026-08-03.md).

## 잔여 위험

- 실 provider/Gemini/Toss 자격증명이 없어 외부 실호출과 실계좌 canary는 미실행.
- 실제 staging registry/OIDC/secret-file/운영 endpoint는 mock/local drill 범위 밖.
- 백업 암호화 at rest, offsite 보관, retention, PITR/WAL 자동화는 미구현 운영 항목.

## 운영 전제

- `REAL_ORDER_ENABLED=false`, `REAL_ORDER_CANARY_ENABLED=false`, `BROKER_CREDENTIALS_ENABLED=false`를 기본값으로 유지.
- 실주문 승인 전 credential vault·allowlist·한도·quote freshness·step-up·kill switch·readiness를 운영자가 확인.
- Gemini/provider 키와 staging secret은 저장소에 넣지 않고 secret-file/운영 secret manager로 주입.
- 이번 closeout에서는 새 기능, 리팩터링, 한도 상향을 하지 않으며 위 잔여 위험을 출시 승인 조건으로 관리.
