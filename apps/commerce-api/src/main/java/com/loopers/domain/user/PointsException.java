package com.loopers.domain.user;

public class PointsException extends RuntimeException {

    public enum Reason {
        INVALID_AMOUNT,
        INSUFFICIENT_POINTS,
        BALANCE_LIMIT_EXCEEDED
    }

    private final Reason reason;

    PointsException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
