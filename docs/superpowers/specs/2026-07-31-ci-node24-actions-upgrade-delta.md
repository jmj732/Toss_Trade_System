# CI Node 24 Actions Upgrade Delta

## 배경

Release Gates가 Node.js 20 기반 GitHub Action 사용 중단 경고를 출력한다.

## 결정

- GitHub-hosted `ubuntu-latest` runner와 기존 workflow 구조를 유지한다.
- Node 24 runtime을 사용하는 최소 호환 major로만 교체한다.
  - `actions/checkout`: `v4` → `v5`
  - `actions/setup-java`: `v4` → `v5`
  - `actions/setup-python`: `v5` → `v6`
  - `actions/setup-node`: `v4` → `v5`
  - `actions/upload-artifact`: `v4` → `v6`
- permissions, trigger, concurrency, cache inputs, artifact 이름·경로·보존 기간, 검증 명령은 변경하지 않는다.
- 애플리케이션 의존성과 workflow 구조는 변경하지 않는다.

## 검증

- workflow YAML 파싱 및 action 참조 정적 검증
- 기존 backend, analysis-service, dashboard 전체 테스트
- mock-stack 검증
- push 후 Release Gates 전체 성공과 Node 20 폐기 경고 부재 확인
