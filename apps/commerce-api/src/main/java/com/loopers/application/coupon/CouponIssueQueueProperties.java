package com.loopers.application.coupon;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 쿠폰 발급 대기열 설정. 미완료 작업(대기, 처리 중, 결과 불명) 한도를 정한다.
 */
@ConfigurationProperties("coupon.issue.queue")
public record CouponIssueQueueProperties(Integer capacity) {

    public CouponIssueQueueProperties {
        if (capacity != null && capacity < 1) {
            throw new IllegalArgumentException("쿠폰 발급 대기열 한도는 1 이상이어야 합니다.");
        }
    }

    public int requireCapacity() {
        if (capacity == null) {
            throw new IllegalStateException("쿠폰 발급 대기열 한도가 설정되지 않았습니다.");
        }
        return capacity;
    }
}
