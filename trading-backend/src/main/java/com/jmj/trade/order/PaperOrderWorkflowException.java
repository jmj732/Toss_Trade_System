package com.jmj.trade.order;

public final class PaperOrderWorkflowException extends RuntimeException {

    private final Code code;

    private PaperOrderWorkflowException(Code code) {
        super(code.name());
        this.code = code;
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

    public Code code() {
        return code;
    }

    public enum Code {
        NOT_FOUND,
        CONFLICT,
        VALIDATION_FAILED,
        AUTHENTICATED_USER_INVALID
    }
}
