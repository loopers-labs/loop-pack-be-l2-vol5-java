package com.loopers.payment.application.port.out;

/**
 * 보냈을 수 있으나 결과를 모른다. 다시 보내지 않고 조회로만 확인한다 (ADR-W5-01).
 * {@link Reason}은 경계 로그 ②의 cause로 남는다 — 응답 유실(READ_TIMEOUT)과 다른 원인을 가른다 (W10 D-5).
 */
public class PgUnknownResultException extends RuntimeException {

    /**
     * 결과를 모르게 된 이유.
     */
    public enum Reason {
        /** 응답을 기다리다 읽기 timeout이 끝났다 */
        READ_TIMEOUT,
        /** 응답을 읽다가 연결이 끊겼다 (timeout 아님) */
        READ_FAILED,
        /** PG가 5xx로 답했다 */
        SERVER_ERROR,
        /** 응답을 받았지만 해석하지 못했다 */
        UNREADABLE
    }

    private final Reason reason;

    public PgUnknownResultException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
