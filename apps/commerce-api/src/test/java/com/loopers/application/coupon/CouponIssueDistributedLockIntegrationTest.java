package com.loopers.application.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {"commerce.coupon.issue-mode=REDIS_SYNC", "commerce.coupon.issue-limit.mode=LOCK",
    "commerce.coupon.issue-limit.wait-timeout=2s"})
class CouponIssueDistributedLockIntegrationTest extends CouponIssueLimitTestSupport {

    /** 한 실행이 TX 안에 있는 동안 다음 실행은 TX에 들어가지 않고 기다렸다가, 앞 실행이 끝나면 들어간다. */
    @Test
    void secondExecutionWaitsOutsideTransactionUntilFirstFinishes() throws Exception {
        long campaign = preparedCampaign(5);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        holdInsideTransaction(holding, finish);

        Future<CouponIssueResult> first = issueAsync(user(), campaign);
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        Future<CouponIssueResult> second = issueAsync(user(), campaign);
        Thread.sleep(500);

        verify(transaction, times(1)).issue(any(), any(), any());
        assertThat(second.isDone()).isFalse();

        finish.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).created()).isTrue();
        assertThat(second.get(10, TimeUnit.SECONDS).created()).isTrue();
        verify(transaction, times(2)).issue(any(), any(), any());
        assertThat(couponRows(campaign)).isEqualTo(2);
    }

    /** 기다리다 시간이 지나면 DB에 들어가지 않고 예약을 돌려 429다. 남은 수가 원래대로 돌아온다. */
    @Test
    void waitTimeoutReturnsReservationAndFailsWithoutTransaction() throws Exception {
        long campaign = preparedCampaign(5);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        holdInsideTransaction(holding, finish);

        Future<CouponIssueResult> first = issueAsync(user(), campaign);
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        long late = user();

        assertThatThrownBy(() -> facade.issue(late, campaign)).isInstanceOfSatisfying(CoreException.class,
            e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.TOO_MANY_REQUESTS)).hasMessage(CouponRejections.LIMIT_NO_SEAT);
        verify(transaction, times(1)).issue(any(), any(), any());
        assertThat(remaining(campaign)).isEqualTo("4");

        finish.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).created()).isTrue();
        assertThat(couponRows(campaign)).isEqualTo(1);
    }
}
