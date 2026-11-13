package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 쿠폰 대기실 시작 설정. campaignId 를 생략하면 대기실이 비활성이다.
 * 기본값(1초·30초)은 관찰용 초기값이며 운영 적정값의 실측이 아니다.
 */
@ConfigurationProperties("commerce.coupon.waiting")
public record CouponWaitingProperties(
    Long campaignId,
    @DefaultValue("1s") Duration schedulerDelay,
    @DefaultValue("30s") Duration admissionDuration
) {

    private static final String PREFIX = "commerce.coupon.waiting.";

    public CouponWaitingProperties {
        if (campaignId != null && campaignId < 1) {
            throw invalid("campaign-id", "생략하거나 1 이상의 정수", String.valueOf(campaignId));
        }
        requirePositiveMillis(schedulerDelay, "scheduler-delay");
        requirePositiveMillis(admissionDuration, "admission-duration");
    }

    private static void requirePositiveMillis(Duration value, String key) {
        CouponPropertyRules.requirePositiveMillis(PREFIX, key, value);
    }

    private static IllegalArgumentException invalid(String key, String rule, String value) {
        return CouponPropertyRules.invalid(PREFIX, key, rule, value);
    }
}
