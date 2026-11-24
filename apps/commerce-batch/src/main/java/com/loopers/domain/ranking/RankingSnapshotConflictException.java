package com.loopers.domain.ranking;

public class RankingSnapshotConflictException extends RuntimeException {
    public RankingSnapshotConflictException(String message) {
        super(message);
    }

    public RankingSnapshotConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
