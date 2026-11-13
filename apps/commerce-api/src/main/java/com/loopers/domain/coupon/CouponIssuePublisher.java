package com.loopers.domain.coupon;

import java.time.Instant;

public interface CouponIssuePublisher {

    record CouponIssue(Long campaignId, Long userId, int issueNumber, Instant reservedAt) {
    }

    sealed interface Outcome {

        record Published() implements Outcome {
        }

        record Unconfirmed(String error) implements Outcome {
        }

        record Rejected(String error) implements Outcome {
        }
    }

    Outcome publish(CouponIssue issue);
}
