package com.jmj.trade.order;

import com.jmj.trade.security.AuthenticationClaims;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

/**
 * kill switch 조작 API (플랜 원장 E4; SPEC:962/1159).
 *
 * <p>조작은 비대칭이다. engage(정지)는 비상 정지이므로 step-up 없이 즉시 가능하고, disengage(해제)는
 * step-up 재인증(먼저 발급받은 {@code X-Step-Up-Token})을 요구한다. 해제 토큰은 조작하려는
 * (scope, target) 에 바인딩돼 발급된다.
 */
@RestController
@RequestMapping("/api/v1/trading/kill-switch")
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class KillSwitchController {

    private final KillSwitchLedger ledger;

    KillSwitchController(KillSwitchLedger ledger) {
        this.ledger = ledger;
    }

    /**
     * kill switch 상태 조회 (BC-7). 조회 대상은 인증된 사용자 본인 범위로 제한된다 — 대상 해석이
     * 조작 경로와 같은 규칙을 쓰므로 남의 USER/ACCOUNT 스위치는 403 이다.
     *
     * <p>{@code scope} 는 필수다. 기본값을 정해 주면 넓은 범위(GLOBAL)의 정지를 못 본 채
     * "정지 아님"으로 오해할 수 있어, 무엇을 묻는지 호출측이 명시하게 한다.
     *
     * <p>원장에 행이 없으면 {@code engaged/version/reason/changedAt} 이 모두 {@code null} 인
     * "미설정" 응답이다. 미설정과 "해제됨(engaged=false)"은 서로 다른 사실이므로 뭉개지 않는다.
     */
    @GetMapping
    StateView state(
            Principal principal,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "targetId", required = false) UUID targetId
    ) {
        var userId = userId(principal);
        var resolvedScope = scope(scope);
        // USER 범위에서 targetId 생략은 "내 스위치"로 읽는다. 다른 사용자를 지정하면 원장이 거부한다.
        var resolvedTarget = resolvedScope == KillSwitchLedger.Scope.USER && targetId == null
                ? userId
                : targetId;
        return ledger.currentState(userId, resolvedScope, resolvedTarget)
                .map(StateView::of)
                .orElseGet(() -> StateView.unset(
                        resolvedScope,
                        resolvedScope == KillSwitchLedger.Scope.GLOBAL
                                ? KillSwitchLedger.GLOBAL_TARGET
                                : resolvedTarget));
    }

    /**
     * 해제용 step-up 토큰 발급. 최근 OIDC 재인증(auth_time)만이 근거이며 값이 없거나 오래되면 401.
     * 원문 토큰은 이 응답에서 한 번만 노출되고 서버는 해시만 저장한다.
     */
    @PostMapping("/step-up")
    StepUpView stepUp(
            Principal principal,
            Authentication authentication,
            @RequestBody StepUpRequest request
    ) {
        if (request == null) {
            throw KillSwitchException.invalidInput();
        }
        var issued = ledger.issueDisengageStepUp(
                userId(principal), scope(request.scope()), request.targetId(),
                AuthenticationClaims.authenticatedAt(authentication));
        return new StepUpView(issued.stepUpToken(), issued.expiresAt());
    }

    /**
     * kill switch 상태 변경. {@code engaged=true} 는 step-up 없이, {@code engaged=false} 는 step-up
     * 토큰이 있어야 성공한다. 원장에 새 버전을 추가하고(덮어쓰기 없음) 그 행을 감사 레코드로 반환한다.
     */
    @PostMapping
    ChangeView change(
            Principal principal,
            @RequestHeader(value = "X-Step-Up-Token", required = false) String stepUpToken,
            @RequestBody ChangeRequest request
    ) {
        if (request == null || request.engaged() == null) {
            throw KillSwitchException.invalidInput();
        }
        var userId = userId(principal);
        var scope = scope(request.scope());
        var actor = userId.toString();
        var record = request.engaged()
                ? ledger.engage(userId, scope, request.targetId(), request.reason(), actor)
                : ledger.disengage(userId, scope, request.targetId(), request.reason(), actor, stepUpToken);
        return ChangeView.of(record);
    }

    private static KillSwitchLedger.Scope scope(String scope) {
        if (scope == null) {
            throw KillSwitchException.invalidInput();
        }
        try {
            return KillSwitchLedger.Scope.valueOf(scope);
        } catch (IllegalArgumentException exception) {
            throw KillSwitchException.invalidInput();
        }
    }

    private static UUID userId(Principal principal) {
        try {
            return UUID.fromString(principal.getName());
        } catch (RuntimeException exception) {
            throw PaperOrderWorkflowException.authenticatedUserInvalid();
        }
    }

    @ExceptionHandler(KillSwitchException.class)
    ResponseEntity<PublicError> killSwitch(KillSwitchException exception) {
        return switch (exception.code()) {
            case INVALID_INPUT -> error(HttpStatus.UNPROCESSABLE_ENTITY, "KILL_SWITCH_INPUT_INVALID");
            case FORBIDDEN_TARGET -> error(HttpStatus.FORBIDDEN, "KILL_SWITCH_TARGET_FORBIDDEN");
        };
    }

    private static ResponseEntity<PublicError> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new PublicError(code));
    }

    record StepUpRequest(String scope, UUID targetId) {
    }

    record ChangeRequest(String scope, UUID targetId, Boolean engaged, String reason) {
    }

    record StepUpView(String stepUpToken, Instant expiresAt) {
    }

    /**
     * kill switch 상태 (BC-7). {@code engaged} 가 {@code null} 이면 원장에 아무 기록도 없는 "미설정"
     * 이다 — 해제됨({@code false})과 구분해야 하며, 프론트는 이를 "정지 아님"으로 읽어서는 안 된다.
     */
    record StateView(
            String scope,
            UUID targetId,
            Boolean engaged,
            Long version,
            String reason,
            Instant changedAt
    ) {
        static StateView of(KillSwitchLedger.StateRecord record) {
            return new StateView(
                    record.scope().name(),
                    record.targetId(),
                    record.engaged(),
                    record.version(),
                    record.reason(),
                    record.changedAt());
        }

        static StateView unset(KillSwitchLedger.Scope scope, UUID targetId) {
            return new StateView(scope.name(), targetId, null, null, null, null);
        }
    }

    record ChangeView(String scope, UUID targetId, long version, boolean engaged, String reason, Instant changedAt) {
        static ChangeView of(KillSwitchLedger.ChangeRecord record) {
            return new ChangeView(
                    record.scope().name(),
                    record.targetId(),
                    record.version(),
                    record.engaged(),
                    record.reason(),
                    record.changedAt());
        }
    }

    record PublicError(String code) {
    }
}
