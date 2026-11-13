package com.loopers.domain.coupon;

import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;

import java.time.Instant;
import java.util.Optional;

public record CouponIssueDeadLetter(
    Long id,
    String messageKey,
    Optional<CouponIssue> issue,
    String payload,
    String error,
    Status status,
    Instant receivedAt,
    Instant replayedAt
) {

    public enum Status {
        PENDING, REPLAYED
    }

    public static CouponIssueDeadLetter received(String messageKey, Optional<CouponIssue> issue, String payload,
                                                 String error, Instant receivedAt) {
        return new CouponIssueDeadLetter(null, messageKey, issue, payload, error, Status.PENDING, receivedAt, null);
    }

    public boolean isReplayable() {
        return status == Status.PENDING && issue.isPresent();
    }
}
