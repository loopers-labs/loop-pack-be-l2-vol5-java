package com.loopers.infrastructure.coupon;

import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CouponIssuePublishConfig {

    @Bean
    MeterBinder couponPublishSlotMetrics(BulkheadCouponPublishSlots slots) {
        return TaggedBulkheadMetrics.ofBulkheadRegistry(slots.bulkheads());
    }
}
