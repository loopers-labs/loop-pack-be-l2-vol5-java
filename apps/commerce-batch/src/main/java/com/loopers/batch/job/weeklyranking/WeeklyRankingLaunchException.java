package com.loopers.batch.job.weeklyranking;

import lombok.Getter;

@Getter
public class WeeklyRankingLaunchException extends RuntimeException {

    public enum Reason {
        INVALID_OPTION,
        TARGET_NOT_FOUND,
        NOT_LATEST_EXECUTION,
        NOT_RESTARTABLE,
        IN_PROGRESS_OR_UNKNOWN,
        CORRUPT_HISTORY,
        LAUNCH_REJECTED
    }

    private final Reason reason;

    public WeeklyRankingLaunchException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
