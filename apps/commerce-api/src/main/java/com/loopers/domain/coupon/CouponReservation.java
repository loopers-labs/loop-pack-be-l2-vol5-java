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

    boolean isReserved(Long campaignId, Long userId);

    boolean isClosed(Long campaignId);

    void close(Long campaignId);

    void open(Long campaignId);

    void clear(Long campaignId);

    void restore(Long campaignId, List<Reservation> reservations);

    int largestNumber(Long campaignId);

    long count(Long campaignId);

    List<Reservation> reservedBetween(Long campaignId, int afterNumber, int toNumber, int limit);
}
