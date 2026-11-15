package com.loopers.interfaces.scheduling;

import com.loopers.application.coupon.CouponIssueWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 작업 큐 모드에서 Worker를 돌린다. 스케줄 실행이 겹치지 않아 이 인스턴스의 Worker는 하나다.
 * 앱을 여러 대 띄우면 Worker도 여러 개가 되므로, 그때는 소비자 이름·남의 미완료 작업 넘겨받기를 따로 정해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression("'${commerce.coupon.issue-mode:REDIS_SYNC}' == 'QUEUE' and ${commerce.coupon.issue-queue.worker.enabled:true}")
public class CouponIssueWorkerScheduler {
    private final CouponIssueWorker worker;

    // 처리할 작업이 있으면 이어서 처리하고, 없거나 처리 완료가 0건(다시 처리할 작업만 남음)이면 다음 주기를 기다린다.
    @Scheduled(fixedDelayString = "${commerce.coupon.issue-queue.worker.fixed-delay-ms:50}")
    public void work() {
        try {
            while (worker.processOnce() > 0) {
                // 다음 묶음을 이어서 처리한다.
            }
        } catch (RuntimeException failure) {
            log.warn("Coupon issue worker failed; retrying on next run", failure);
        }
    }
}
