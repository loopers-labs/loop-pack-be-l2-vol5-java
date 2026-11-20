package com.loopers.batch.job.ranking.domain;

public class SnapshotConflictException extends RuntimeException {
    public SnapshotConflictException(String message) {
        super(message);
    }
}
