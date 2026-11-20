package com.loopers.application.ranking;

public class WeeklyRankingQueryException extends RuntimeException {
    public enum Reason { NOT_PUBLISHED, UNAVAILABLE, CORRUPT }

    private final Reason reason;

    public WeeklyRankingQueryException(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public WeeklyRankingQueryException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
