package com.loopers.domain.ranking.model;

public class SnapshotConflictException extends RuntimeException {
    public SnapshotConflictException(String message) {
        super(message);
    }
}
