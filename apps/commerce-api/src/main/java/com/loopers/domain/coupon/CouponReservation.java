package com.loopers.domain.coupon;

public interface CouponReservation {

    sealed interface Result {

        record Reserved(int issueNumber) implements Result {
        }

        record Existing(int issueNumber) implements Result {
        }

        record SoldOut() implements Result {
        }

        record Lost() implements Result {
        }
    }

    Result reserve(Long campaignId, Long userId, IssueQuota quota, int dbIssuedCount);

    void cancel(Long campaignId, Long userId);
}
