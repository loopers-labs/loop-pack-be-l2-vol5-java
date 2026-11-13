package com.loopers.application.coupon.facade;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 테스트는 끄고 회차를 직접 부른다 */
@ConditionalOnProperty(name = "coupon-issue.worker.enabled", havingValue = "true")
@EnableScheduling
@RequiredArgsConstructor
@Component
public class CouponIssueWorkerScheduler {

    private final CouponIssueWorker worker;

    /** fixedDelay 는 앞 회차가 끝난 뒤 다음을 잡아 같은 인스턴스의 회차끼리 겹치지 않는다 */
    @Scheduled(fixedDelayString = "${coupon-issue.worker.interval-ms}")
    public void run() {
        worker.runOnce();
    }
}
