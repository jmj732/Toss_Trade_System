package com.jmj.trade.order;

import com.jmj.trade.broker.Currency;

import java.math.BigDecimal;

public final class PaperOrderWorkflowException extends RuntimeException {

    private final Code code;
    private final transient DisplaySnapshot serverSnapshot;

    private PaperOrderWorkflowException(Code code) {
        this(code, null);
    }

    private PaperOrderWorkflowException(Code code, DisplaySnapshot serverSnapshot) {
        super(code.name());
        this.code = code;
        this.serverSnapshot = serverSnapshot;
    }

    static PaperOrderWorkflowException notFound() {
        return new PaperOrderWorkflowException(Code.NOT_FOUND);
    }

    static PaperOrderWorkflowException conflict() {
        return new PaperOrderWorkflowException(Code.CONFLICT);
    }

    static PaperOrderWorkflowException validationFailed() {
        return new PaperOrderWorkflowException(Code.VALIDATION_FAILED);
    }

    static PaperOrderWorkflowException authenticatedUserInvalid() {
        return new PaperOrderWorkflowException(Code.AUTHENTICATED_USER_INVALID);
    }

    /**
     * 화면 표시값이 서버 재계산값과 다름. 클라이언트가 새 확인 화면을 그릴 수 있도록 서버 계산값을
     * 함께 싣는다(SPEC:966).
     */
    static PaperOrderWorkflowException displayMismatch(DisplaySnapshot serverSnapshot) {
        return new PaperOrderWorkflowException(Code.DISPLAY_MISMATCH, serverSnapshot);
    }

    /** step-up 재인증 토큰이 없거나 만료·재사용·타 사용자·타 주문 등으로 유효하지 않음. */
    static PaperOrderWorkflowException stepUpRequired() {
        return new PaperOrderWorkflowException(Code.STEP_UP_REQUIRED);
    }

    /** 제안이 만료 시각을 지나 승인할 수 없음(D-42). 취소·철회는 여전히 허용된다. */
    static PaperOrderWorkflowException proposalExpired() {
        return new PaperOrderWorkflowException(Code.PROPOSAL_EXPIRED);
    }

    public Code code() {
        return code;
    }

    public DisplaySnapshot serverSnapshot() {
        return serverSnapshot;
    }

    public enum Code {
        NOT_FOUND,
        CONFLICT,
        VALIDATION_FAILED,
        AUTHENTICATED_USER_INVALID,
        DISPLAY_MISMATCH,
        STEP_UP_REQUIRED,
        PROPOSAL_EXPIRED
    }

    public record DisplaySnapshot(BigDecimal quantity, BigDecimal maxLoss, Currency currency) {
    }
}
