package com.loopers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 쿠폰 입장 대기실 시작 설정(commerce.coupon.waiting, 설계 §12.10·12.11). 변경은 재시작으로 적용한다.
 * campaignId 생략은 허용하며 대기실 비활성을 뜻한다. 명시한 ID 는 양수여야 한다.
 * 두 Duration 은 Redis·실행기에 밀리초로 넘기므로 1ms 이상이면서 밀리초 변환이 넘치지 않아야 한다. 어긋나면 기동 오류다.
 * 기본값(1초·30초)은 관찰용 초기값이며 운영 적정값의 실측이 아니다.
 */
@ConfigurationProperties("commerce.coupon.waiting")
public record CouponWaitingProperties(
    Long campaignId,
    @DefaultValue("1s") Duration schedulerDelay,
    @DefaultValue("30s") Duration admissionDuration
) {

    public CouponWaitingProperties {
        if (campaignId != null && campaignId < 1) {
            throw invalid("campaign-id", "생략하거나 1 이상의 정수", String.valueOf(campaignId));
        }
        requirePositiveMillis(schedulerDelay, "scheduler-delay");
        requirePositiveMillis(admissionDuration, "admission-duration");
    }

    private static void requirePositiveMillis(Duration value, String key) {
        if (value == null) {
            throw invalid(key, "1ms 이상의 기간", "null");
        }
        long millis;
        try {
            millis = value.toMillis();
        } catch (ArithmeticException e) {
            throw invalid(key, "밀리초로 표현 가능한 기간", value.toString());
        }
        if (millis < 1) {
            throw invalid(key, "1ms 이상의 기간", value.toString());
        }
    }

    private static IllegalArgumentException invalid(String key, String rule, String value) {
        return new IllegalArgumentException("commerce.coupon.waiting." + key + " 는 " + rule + "여야 합니다. [" + key + " = " + value + "]");
    }
}
