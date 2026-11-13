package com.loopers.domain.coupon;

import java.util.List;

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

        record Closed() implements Result {
        }
    }

    record Reservation(Long userId, int issueNumber) {
    }

    Result reserve(Long campaignId, Long userId, IssueQuota quota, int dbIssuedCount);

    void cancel(Long campaignId, Long userId);

    void close(Long campaignId);

    void open(Long campaignId);

    int largestNumber(Long campaignId);

    long count(Long campaignId);

    List<Reservation> reservedBetween(Long campaignId, int afterNumber, int toNumber, int limit);
}
