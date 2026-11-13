package com.loopers.application.coupon.query;

import java.util.List;

public interface CouponCounterSnapshotDao {
    List<Long> findCommittedCouponIds(long campaignId);
}
