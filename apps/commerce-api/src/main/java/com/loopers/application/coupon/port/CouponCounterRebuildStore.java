package com.loopers.application.coupon.port;

import java.util.List;

/** 캠페인 DB 행 잠금을 보유한 호출자만 완성된 snapshot을 교체한다. */
public interface CouponCounterRebuildStore {
    void replace(long campaignId, List<Long> committedCouponIds);
}
