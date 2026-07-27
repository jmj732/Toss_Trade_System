# Portfolio Snapshot Sync Delta

- Status: Accepted by explicit autonomous-execution instruction
- Scope: account/position/capacity snapshots required before portfolio aggregation
- Unchanged architecture and prior contracts are not repeated.

## Decisions

1. **동기화 성공 단위**
   - 한 `brokerConnectionId`가 노출한 MVP 단일 계좌의 한 sync run이 단위다.
   - account summary, positions, KRW buying power, USD buying power가 모두 성공해야 `SUCCEEDED`다.
   - 계좌가 정확히 하나가 아니면 안전하게 실패한다.

2. **부분 실패 처리**
   - 외부 호출 중 하나라도 실패하면 run만 `FAILED`로 종료한다.
   - account/position/capacity snapshot은 한 건도 저장하지 않는다.
   - 이전 성공 snapshot은 수정하거나 삭제하지 않는다.

3. **동시 실행 차단**
   - 연결당 `RUNNING` run은 DB partial unique index로 하나만 허용한다.
   - `portfolio.sync.stale-after`(기본 `PT15M`) 초과 run은 새 run 시작 트랜잭션에서
     `FAILED`/`FAILED_STALE`로 원자 회수한다.
   - 충돌 요청은 외부 호출 없이 `SYNC_ALREADY_RUNNING`으로 실패한다.
   - 별도 Redis lock과 신규 의존성은 추가하지 않는다.

4. **credential revision 변경 경쟁**
   - run 시작 시 revision을 고정해 기록한다.
   - 외부 호출 후 snapshot commit 직전에 같은 사용자·연결·revision·미삭제 상태를 다시 확인한다.
   - 변경/삭제됐으면 run을 `FAILED`로 끝내고 snapshot을 저장하지 않는다.

5. **append-only 스냅샷 구조**
   - mutable `account_sync_runs`가 상태와 안전한 오류 코드만 보유한다.
   - 성공 run에 `account_snapshots` 1개, `position_snapshots` N개,
     `account_capacity_snapshots` 2개를 연결한다.
   - snapshot 테이블의 UPDATE/DELETE는 DB trigger로 거부한다.

6. **최신 성공 스냅샷 판정**
   - 같은 사용자·연결에서 `SUCCEEDED` run을 `completed_at DESC, id DESC`로 정렬한 첫 run이다.
   - 현재 credential revision과 다르면 과거 성공 데이터로 남되 최신 사용 가능 데이터로 보지 않는다.

7. **KRW/USD buying power 조회 범위**
   - 매 sync마다 KRW와 USD를 각각 정확히 한 번 조회한다.
   - 둘 중 하나라도 실패하면 전체 run이 실패한다.
   - buying power는 현금·총자산에 합산하지 않는다.

## Deferred

- FX 환산, portfolio risk, sellable quantity, scheduler, retry, REST API.
