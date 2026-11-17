package com.loopers.domain.ranking;

import lombok.Getter;

@Getter
public class RankingPublicationException extends RuntimeException {

    public enum Reason {
        SNAPSHOT_CONTRACT_MISMATCH,
        SNAPSHOT_NOT_READY,
        INCOMPLETE_SCORES,
        INCONSISTENT_RESULT,
        INVALID_PUBLICATION,
        UNEXPECTED_WRITE_COUNT
    }

    private final Reason reason;

    public RankingPublicationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }
}
