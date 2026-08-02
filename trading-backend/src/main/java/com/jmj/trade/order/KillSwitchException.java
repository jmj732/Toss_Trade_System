package com.jmj.trade.order;

/**
 * kill switch API 의 도메인 오류 (플랜 원장 E4). step-up 재인증 실패는 별도로
 * {@link PaperOrderWorkflowException#stepUpRequired()}(401) 로 표현한다.
 */
public final class KillSwitchException extends RuntimeException {

    private final Code code;

    private KillSwitchException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public static KillSwitchException invalidInput() {
        return new KillSwitchException(Code.INVALID_INPUT);
    }

    public static KillSwitchException forbiddenTarget() {
        return new KillSwitchException(Code.FORBIDDEN_TARGET);
    }

    public enum Code {
        INVALID_INPUT,
        FORBIDDEN_TARGET
    }
}
