package com.loopers.application.promotion;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * application.yml coupon-issue.limiter.*. 쿠폰 발급 TX 동시 실행 제한 설정 (DR-63).
 *
 * @param permits  프로모션 하나에서 동시에 발급 TX 를 실행할 수 있는 수 (앱 전체 합)
 * @param lease    허가 하나의 보유 상한. 지나면 반납하지 않아도 회수된다
 * @param waitTime 허가가 없을 때 기다리는 상한. 그 안에 반납되면 바로 얻는다
 * @param executor 허가를 얻은 발급 TX 를 실행하는 앱 서버 하나의 스레드 풀
 */
@ConfigurationProperties("coupon-issue.limiter")
public record CouponIssueLimiterProperties(int permits, Duration lease, Duration waitTime, ExecutorProperties executor) {

    /**
     * @param threads       발급 TX 를 실행하는 스레드 수 (고정)
     * @param queueCapacity 스레드가 모두 바쁠 때 허가를 쥔 채 기다리는 작업 수 상한. 넘치면 허가를 반납하고 COUPON_ISSUE_BUSY
     */
    public record ExecutorProperties(int threads, int queueCapacity) {
    }
}
