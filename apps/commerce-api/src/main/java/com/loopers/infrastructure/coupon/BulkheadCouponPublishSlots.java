package com.loopers.infrastructure.coupon;

import com.loopers.domain.coupon.CouponPublishSlots;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
class BulkheadCouponPublishSlots implements CouponPublishSlots {

    static final String NAME = "coupon-publish";

    private final BulkheadRegistry bulkheads;
    private final Bulkhead bulkhead;

    BulkheadCouponPublishSlots(CouponIssuePublishProperties properties) {
        this.bulkheads = BulkheadRegistry.of(BulkheadConfig.custom()
            .maxConcurrentCalls(properties.maxInFlight())
            .maxWaitDuration(Duration.ZERO)
            .build());
        this.bulkhead = bulkheads.bulkhead(NAME);
    }

    BulkheadRegistry bulkheads() {
        return bulkheads;
    }

    @Override
    public boolean tryAcquire() {
        return bulkhead.tryAcquirePermission();
    }

    @Override
    public void release() {
        bulkhead.onComplete();
    }
}
