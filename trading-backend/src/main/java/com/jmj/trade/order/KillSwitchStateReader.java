package com.jmj.trade.order;

import java.util.UUID;

/**
 * kill switch 최신 상태 읽기 포트 (플랜 원장 E4). 제출 직전 재검증 체크가 이 포트만 의존하므로
 * fail-closed 판정을 원장 구현과 분리해 검증할 수 있다.
 */
public interface KillSwitchStateReader {

    /**
     * 제출 대상(사용자·계좌)에 적용되는 GLOBAL/USER/ACCOUNT 범위 중 하나라도 최신 상태가 engaged
     * 이면 {@code true}. 상태를 읽을 수 없으면 예외를 던진다 — 통과로 삼키지 않는다.
     */
    boolean anyEngaged(UUID userId, UUID accountId);
}
