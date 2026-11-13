package com.loopers.application.coupon;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {"commerce.coupon.issue-mode=REDIS_SYNC", "commerce.coupon.issue-limit.mode=SEMAPHORE",
    "commerce.coupon.issue-limit.permits=2", "commerce.coupon.issue-limit.wait-timeout=5s"})
class CouponIssueSemaphoreIntegrationTest extends CouponIssueLimitTestSupport {

    /** 자리 2개면 두 실행이 TX 안에 있는 동안 세 번째는 기다리고, 하나가 끝나면 들어간다. */
    @Test
    void thirdExecutionWaitsWhileTwoHoldPermits() throws Exception {
        long campaign = preparedCampaign(5);
        CountDownLatch holding = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        holdInsideTransaction(holding, finish);

        List<Future<CouponIssueResult>> held = List.of(issueAsync(user(), campaign), issueAsync(user(), campaign));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        Future<CouponIssueResult> third = issueAsync(user(), campaign);
        Thread.sleep(500);

        verify(transaction, times(2)).issue(any(), any(), any());
        assertThat(third.isDone()).isFalse();

        finish.countDown();
        for (Future<CouponIssueResult> future : held) {
            assertThat(future.get(10, TimeUnit.SECONDS).created()).isTrue();
        }
        assertThat(third.get(10, TimeUnit.SECONDS).created()).isTrue();
        verify(transaction, atLeastOnce()).issue(any(), any(), any());
        assertThat(couponRows(campaign)).isEqualTo(3);
    }
}
