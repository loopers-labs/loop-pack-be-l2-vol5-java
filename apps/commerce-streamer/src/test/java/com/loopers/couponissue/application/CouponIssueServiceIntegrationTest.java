package com.loopers.couponissue.application;

import com.loopers.couponissue.application.port.in.CouponIssueUseCase;
import com.loopers.couponissue.application.port.in.CouponIssueUseCase.Result;
import com.loopers.couponissue.domain.CouponIssueMessage;
import com.loopers.support.StreamerIntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.loopers.couponissue.support.CouponIssueFixtures.campaignWithTwoCoupons;
import static com.loopers.couponissue.support.CouponIssueFixtures.issuedCount;
import static com.loopers.couponissue.support.CouponIssueFixtures.userCoupons;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CPN-08 쿠폰팩 발급 (D-5 조건부 UPDATE). 필수 실험 ①(수량·1인 1장)·③(같은 메시지 두 번).
 */
class CouponIssueServiceIntegrationTest extends StreamerIntegrationTestSupport {

    private static final long CAMPAIGN = 1L;

    @Autowired
    private CouponIssueUseCase couponIssueUseCase;

    @DisplayName("CPN-08 발급하면 쿠폰팩(2장)이 생기고 발급 수가 1 오른다. 만료 시각은 쿠폰마다 규칙대로다(CPN-07).")
    @Test
    void issuesCouponPack() {
        campaignWithTwoCoupons(jdbcTemplate, CAMPAIGN, 5);

        Result result = couponIssueUseCase.issue(new CouponIssueMessage(CAMPAIGN, 42L));

        assertThat(result).isEqualTo(Result.ISSUED);
        assertThat(userCoupons(jdbcTemplate, CAMPAIGN, 42L)).isEqualTo(2);
        assertThat(issuedCount(jdbcTemplate, CAMPAIGN)).isEqualTo(1);
        List<String> expires = jdbcTemplate.queryForList(
            "SELECT DATE_FORMAT(expires_at, '%Y-%m-%d') FROM user_coupon WHERE user_id = 42 ORDER BY coupon_id", String.class);
        assertThat(expires.get(0)).isEqualTo("2026-11-30");
        // 발급 후 7일: 만료 시각 - 발급 시각 = 정확히 7일 (실행 날짜와 무관하게)
        assertThat(jdbcTemplate.queryForObject(
            "SELECT TIMESTAMPDIFF(SECOND, issued_at, expires_at) FROM user_coupon WHERE user_id = 42 ORDER BY coupon_id LIMIT 1 OFFSET 1",
            Long.class)).isEqualTo(7L * 24 * 60 * 60);
    }

    @DisplayName("필수 실험 ③ 같은 메시지를 두 번 처리하면 두 번째는 ALREADY_ISSUED이고 쿠폰·발급 수가 한 번만 늘어난다.")
    @Test
    void sameMessageTwiceIssuesOnce() {
        campaignWithTwoCoupons(jdbcTemplate, CAMPAIGN, 5);

        Result first = couponIssueUseCase.issue(new CouponIssueMessage(CAMPAIGN, 42L));
        Result second = couponIssueUseCase.issue(new CouponIssueMessage(CAMPAIGN, 42L));

        assertThat(first).isEqualTo(Result.ISSUED);
        assertThat(second).isEqualTo(Result.ALREADY_ISSUED);
        assertThat(userCoupons(jdbcTemplate, CAMPAIGN, 42L)).isEqualTo(2);
        assertThat(issuedCount(jdbcTemplate, CAMPAIGN)).isEqualTo(1);
    }

    @DisplayName("같은 사용자의 메시지 두 개를 동시에 처리해도(rebalance) 쿠폰팩은 한 번, 발급 수도 1이다.")
    @Test
    void concurrentSameUserIssuesOnce() throws Exception {
        campaignWithTwoCoupons(jdbcTemplate, CAMPAIGN, 5);

        List<Result> results = runConcurrently(List.of(42L, 42L, 42L, 42L));

        assertThat(results).containsOnlyOnce(Result.ISSUED);
        assertThat(results).containsOnly(Result.ISSUED, Result.ALREADY_ISSUED);
        assertThat(userCoupons(jdbcTemplate, CAMPAIGN, 42L)).isEqualTo(2);
        assertThat(issuedCount(jdbcTemplate, CAMPAIGN)).isEqualTo(1);
    }

    @DisplayName("필수 실험 ① 당첨자 5명 캠페인에 8명이 동시에 오면 5명 발급, 3명 SOLD_OUT이다. 쿠폰은 5 × 2장, 발급 수는 5다.")
    @Test
    void eightUsersForFiveSlots() throws Exception {
        campaignWithTwoCoupons(jdbcTemplate, CAMPAIGN, 5);

        List<Result> results = runConcurrently(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L));

        assertThat(results.stream().filter(r -> r == Result.ISSUED)).hasSize(5);
        assertThat(results.stream().filter(r -> r == Result.SOLD_OUT)).hasSize(3);
        assertThat(userCoupons(jdbcTemplate, CAMPAIGN)).isEqualTo(10);
        assertThat(issuedCount(jdbcTemplate, CAMPAIGN)).isEqualTo(5);
    }

    private List<Result> runConcurrently(List<Long> userIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(userIds.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (Long userId : userIds) {
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return couponIssueUseCase.issue(new CouponIssueMessage(CAMPAIGN, userId));
                }));
            }
            start.countDown();
            List<Result> results = new ArrayList<>();
            for (Future<Result> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
