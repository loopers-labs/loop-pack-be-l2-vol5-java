package com.loopers.application.promotion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yml coupon-issue.limiter.*. 쿠폰 발급 TX 동시 실행 제한 설정 (DR-62).
 *
 * @param permits  프로모션 하나에서 동시에 발급 TX 를 실행할 수 있는 수 (앱 전체 합)
 * @param lease    허가 하나의 보유 상한. 지나면 반납하지 않아도 회수된다
 * @param waitTime 허가가 없을 때 기다리는 상한. 그 안에 반납되면 바로 얻는다
 */
@ConfigurationProperties("coupon-issue.limiter")
public record CouponIssueLimiterProperties(int permits, Duration lease, Duration waitTime) {
}
