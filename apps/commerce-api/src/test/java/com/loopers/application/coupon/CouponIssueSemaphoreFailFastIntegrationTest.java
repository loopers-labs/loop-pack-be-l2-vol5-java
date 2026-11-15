package com.loopers.application.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 세마포어 3자리를 기다리지 않고 바로 거절하는 방식(wait-timeout 0).
 * 쿠폰이 남아 있어도 자리가 없으면 먼저 온 요청이 떨어지고, 자리가 난 뒤에 온 요청이 받는다.
 */
@SpringBootTest(properties = {"commerce.coupon.issue-mode=REDIS_SYNC", "commerce.coupon.issue-limit.mode=SEMAPHORE",
    "commerce.coupon.issue-limit.permits=3", "commerce.coupon.issue-limit.wait-timeout=0s"})
class CouponIssueSemaphoreFailFastIntegrationTest extends CouponIssueLimitTestSupport {

    /** 1~3번이 자리를 쥔 동안 4번이 오면 바로 거절된다. 1~3번이 끝난 뒤 온 5번은 쿠폰을 받는다. */
    @Test
    void fourthIsRejectedWhileCouponsRemainAndLaterFifthGetsCoupon() throws Exception {
        long campaign = preparedCampaign(10);
        CountDownLatch holding = new CountDownLatch(3);
        CountDownLatch finish = new CountDownLatch(1);
        holdInsideTransaction(holding, finish);

        List<Future<CouponIssueResult>> first3 = List.of(
            issueAsync(user(), campaign), issueAsync(user(), campaign), issueAsync(user(), campaign));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        long fourth = user();
        assertThatThrownBy(() -> facade.issue(fourth, campaign)).isInstanceOfSatisfying(CoreException.class,
            e -> assertThat(e.getErrorType()).isEqualTo(ErrorType.TOO_MANY_REQUESTS)).hasMessage(CouponRejections.LIMIT_NO_SEAT);
        // 쿠폰은 남아 있었다. 4번의 예약은 돌려받아 10장 중 3장만 예약된 상태다.
        assertThat(remaining(campaign)).isEqualTo("7");

        finish.countDown();
        for (Future<CouponIssueResult> future : first3) {
            assertThat(future.get(10, TimeUnit.SECONDS).created()).isTrue();
        }
        long fifth = user();
        assertThat(facade.issue(fifth, campaign).created()).isTrue();

        // 먼저 온 4번은 쿠폰이 없고, 나중에 온 5번은 있다. 쿠폰은 10장 중 4장만 나갔다.
        assertThat(hasCoupon(campaign, fourth)).isFalse();
        assertThat(hasCoupon(campaign, fifth)).isTrue();
        assertThat(couponRows(campaign)).isEqualTo(4);
    }

    private boolean hasCoupon(long campaignId, long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ? AND user_id = ?", Long.class,
            campaignId, userId) > 0;
    }
}
