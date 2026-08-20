package com.jmj.trade.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 주문 kill switch 원장 (플랜 원장 E4; SPEC:886/962/1136/1159).
 *
 * <p>비상 정지를 DB 원장으로 관리한다. 상태를 덮어쓰지 않고 (scope, target) 별 버전을 올려 추가만
 * 하며, 각 행이 곧 감사 레코드다. 조작은 비대칭이다 — engage 는 마찰이 없어야 하므로(비상 정지)
 * step-up 을 요구하지 않고, disengage 는 신중해야 하므로 E2 의 step-up 재인증을 요구한다.
 *
 * <p>범위 3종(GLOBAL/USER/ACCOUNT)은 서로 독립이다. "하나라도 켜져 있으면 차단" 이며, 좁은 범위의
 * 해제가 넓은 범위의 정지를 무효화하지 못한다. 상태 읽기는 캐시 없이 매번 최신 버전을 조회한다 —
 * 캐시가 없으므로 정지 지연은 다음 제출 판정 시점까지로, 사실상 즉시다.
 */
public final class KillSwitchLedger implements KillSwitchStateReader {

    /** GLOBAL 범위는 대상이 없어 고정 sentinel 로 표현한다(마이그레이션의 CHECK 와 일치). */
    public static final UUID GLOBAL_TARGET = new UUID(0L, 0L);

    private static final String DISENGAGE_STEP_UP_SUBJECT = "KILL_SWITCH_DISENGAGE";
    private static final int MAX_REASON_LENGTH = 500;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OrderApprovalStepUpService stepUp;
    private final Clock clock;

    public KillSwitchLedger(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            OrderApprovalStepUpService stepUp,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.stepUp = Objects.requireNonNull(stepUp, "stepUp");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 제출 대상(사용자·계좌)에 적용되는 GLOBAL/USER/ACCOUNT 범위 중 하나라도 최신 상태가 engaged
     * 이면 {@code true}. 각 (scope, target) 의 "최신 버전" 행만 보고 OR 로 합친다. 조회가 실패하면
     * 예외를 던진다 — 통과로 삼키지 않는다. fail-closed 판정은 호출측 재검증 체크가 담당한다.
     */
    @Override
    public boolean anyEngaged(UUID userId, UUID accountId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(accountId, "accountId");
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE(bool_or(k.engaged), false)
                  FROM kill_switch_ledger k
                 WHERE k.version = (
                           SELECT MAX(k2.version)
                             FROM kill_switch_ledger k2
                            WHERE k2.scope = k.scope
                              AND k2.target_id = k.target_id)
                   AND (
                           (k.scope = 'GLOBAL'  AND k.target_id = ?)
                        OR (k.scope = 'USER'    AND k.target_id = ?)
                        OR (k.scope = 'ACCOUNT' AND k.target_id = ?))
                """, Boolean.class, GLOBAL_TARGET, userId, accountId));
    }

    /**
     * (scope, target) 의 최신 상태를 읽는다 (BC-7). 원장에 행이 하나도 없으면 {@link Optional#empty()}
     * — "미설정"과 "해제됨"은 다른 사실이므로 engaged=false 로 뭉개지 않는다. 대상 해석은 조작
     * 경로와 같은 {@link #resolveTarget} 을 쓰므로, 남의 USER/ACCOUNT 스위치는 읽을 수 없다.
     */
    public Optional<StateRecord> currentState(UUID userId, Scope scope, UUID rawTarget) {
        var target = resolveTarget(userId, scope, rawTarget);
        return jdbc.query("""
                SELECT version, engaged, reason, changed_at
                  FROM kill_switch_ledger
                 WHERE scope = ? AND target_id = ?
                 ORDER BY version DESC
                 LIMIT 1
                """, (resultSet, rowNumber) -> new StateRecord(
                scope,
                target,
                resultSet.getLong("version"),
                resultSet.getBoolean("engaged"),
                resultSet.getString("reason"),
                resultSet.getObject("changed_at", OffsetDateTime.class).toInstant()),
                scope.name(), target)
                .stream().findFirst();
    }

    /** 정지(engage). 비상 정지이므로 step-up 이 없다(SPEC 조작 비대칭). */
    public ChangeRecord engage(UUID userId, Scope scope, UUID rawTarget, String reason, String actor) {
        return append(userId, scope, rawTarget, true, reason, actor);
    }

    /**
     * 해제(disengage). E2 의 step-up 재인증을 요구한다(SPEC:1159). 토큰 소비와 원장 추가를 한
     * 트랜잭션으로 묶어, 추가가 실패하면 소비도 롤백돼 토큰을 다시 쓸 수 있다.
     */
    public ChangeRecord disengage(
            UUID userId,
            Scope scope,
            UUID rawTarget,
            String reason,
            String actor,
            String stepUpToken
    ) {
        requireActor(actor);
        var target = resolveTarget(userId, scope, rawTarget);
        var cleanReason = requireReason(reason);
        return Objects.requireNonNull(transactions.execute(status -> {
            // step-up 은 상태 변경 직전에 소비한다. 실패 시 401 로 매핑돼 원장에 아무 행도 남지 않는다.
            stepUp.consumeForSubject(userId, DISENGAGE_STEP_UP_SUBJECT, target, stepUpToken);
            return appendResolved(scope, target, false, cleanReason, actor);
        }));
    }

    /**
     * 해제용 step-up 토큰을 발급한다. 최근 OIDC 재인증({@code authTime})만이 근거이며, 대상은
     * 발급 요청자가 조작 권한을 가진 (scope, target) 여야 한다.
     */
    public OrderApprovalStepUpService.IssuedStepUp issueDisengageStepUp(
            UUID userId,
            Scope scope,
            UUID rawTarget,
            Instant authTime
    ) {
        var target = resolveTarget(userId, scope, rawTarget);
        return stepUp.issueForSubject(userId, DISENGAGE_STEP_UP_SUBJECT, target, authTime);
    }

    private ChangeRecord append(
            UUID userId,
            Scope scope,
            UUID rawTarget,
            boolean engaged,
            String reason,
            String actor
    ) {
        requireActor(actor);
        var target = resolveTarget(userId, scope, rawTarget);
        var cleanReason = requireReason(reason);
        return Objects.requireNonNull(
                transactions.execute(status -> appendResolved(scope, target, engaged, cleanReason, actor)));
    }

    private ChangeRecord appendResolved(
            Scope scope,
            UUID target,
            boolean engaged,
            String reason,
            String actor
    ) {
        var id = UUID.randomUUID();
        var changedAt = clock.instant();
        var version = Objects.requireNonNull(jdbc.queryForObject("""
                SELECT COALESCE(MAX(version), 0) + 1
                  FROM kill_switch_ledger
                 WHERE scope = ? AND target_id = ?
                """, Long.class, scope.name(), target));
        jdbc.update("""
                INSERT INTO kill_switch_ledger (
                    id, scope, target_id, version, engaged, reason, actor, changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, scope.name(), target, version, engaged, reason, actor, at(changedAt));
        return new ChangeRecord(id, scope, target, version, engaged, reason, actor, changedAt);
    }

    private UUID resolveTarget(UUID userId, Scope scope, UUID rawTarget) {
        Objects.requireNonNull(userId, "userId");
        if (scope == null) {
            throw KillSwitchException.invalidInput();
        }
        return switch (scope) {
            case GLOBAL -> {
                if (rawTarget != null && !rawTarget.equals(GLOBAL_TARGET)) {
                    throw KillSwitchException.invalidInput();
                }
                yield GLOBAL_TARGET;
            }
            case USER -> {
                // 자기 자신의 USER 스위치만 조작할 수 있다. 타인 대상 지정은 거부한다.
                if (rawTarget == null || !rawTarget.equals(userId)) {
                    throw KillSwitchException.forbiddenTarget();
                }
                yield userId;
            }
            case ACCOUNT -> {
                if (rawTarget == null) {
                    throw KillSwitchException.invalidInput();
                }
                if (!ownsConnection(userId, rawTarget)) {
                    throw KillSwitchException.forbiddenTarget();
                }
                yield rawTarget;
            }
        };
    }

    private boolean ownsConnection(UUID userId, UUID connectionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM broker_connections
                     WHERE id = ? AND user_id = ?
                )
                """, Boolean.class, connectionId, userId));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw KillSwitchException.invalidInput();
        }
        return reason;
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw KillSwitchException.invalidInput();
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public enum Scope {
        GLOBAL,
        USER,
        ACCOUNT
    }

    /** 원장에 실제로 존재하는 최신 상태. 이 값이 없다는 것(Optional.empty)이 곧 "미설정"이다. */
    public record StateRecord(
            Scope scope,
            UUID targetId,
            long version,
            boolean engaged,
            String reason,
            Instant changedAt
    ) {
    }

    public record ChangeRecord(
            UUID id,
            Scope scope,
            UUID targetId,
            long version,
            boolean engaged,
            String reason,
            String actor,
            Instant changedAt
    ) {
    }
}
