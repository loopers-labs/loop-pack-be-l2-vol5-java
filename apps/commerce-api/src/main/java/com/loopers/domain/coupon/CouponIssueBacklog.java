package com.loopers.domain.coupon;

import java.util.Map;

public interface CouponIssueBacklog {

    record Position(Map<Integer, Long> endOffsets) {
    }

    boolean isDrained();

    Position position();

    boolean isCommittedUpTo(Position position);
}
