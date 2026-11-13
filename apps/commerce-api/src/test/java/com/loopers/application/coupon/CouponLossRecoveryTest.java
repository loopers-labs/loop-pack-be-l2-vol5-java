package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "kafka.topics.coupon-issues=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER,
    "kafka.topics.coupon-issues-dlt=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER_DLT,
    "coupon.issue.worker.auto-startup=true"
})
class CouponLossRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final Duration WITHIN = Duration.ofSeconds(10);

    private final CouponFacade couponFacade;
    private final CouponReservation couponReservation;
    private final CouponReconciliation couponReconciliation;
    private final CampaignCache campaignCache;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponLossRecoveryTest(CouponFacade couponFacade, CouponReservation couponReservation,
                           CouponReconciliation couponReconciliation, CampaignCache campaignCache,
                           JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.couponReservation = couponReservation;
        this.couponReconciliation = couponReconciliation;
        this.campaignCache = campaignCache;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
    }

    private Long openCampaign() {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(100), true).getId();
    }

    private int couponCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupon where campaign_id = ?", Integer.class, campaignId);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WITHIN.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(WITHIN + " 안에 조건이 채워지지 않았다");
            }
            Thread.sleep(100);
        }
    }

    @DisplayName("FLT-008: 대사가 DB 발급 수를 캐시에 반영한 뒤 Redis 가 비면, 다음 접수는 유실로 보고 503 이다")
    @Test
    void detectsLossOnceReconcileRefreshedTheLowerBound() throws Exception {
        Long campaignId = openCampaign();
        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new CouponFacade.IssueResult.Accepted(1));
        eventually(() -> couponCount(campaignId) == 1);
        couponReconciliation.reconcile(campaignId);

        redisCleanUp.truncateAll();

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 7L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }

    @DisplayName("FLT-008: 닫힌 캠페인은 예약 없이 503 이고, 열면 다시 접수한다")
    @Test
    void closedCampaignRefusesAdmissionUntilOpened() {
        Long campaignId = openCampaign();
        couponReservation.close(campaignId);

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        assertThat(couponReservation.count(campaignId)).isZero();

        couponReservation.open(campaignId);
        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new CouponFacade.IssueResult.Accepted(1));
    }

    private boolean acceptedWithNumber(Long campaignId, Long userId, int issueNumber) {
        try {
            return couponFacade.issue(campaignId, userId, NOW).equals(new CouponFacade.IssueResult.Accepted(issueNumber));
        } catch (CoreException e) {
            return false;
        }
    }

    @DisplayName("FLT-008: Redis 가 비면 첫 접수가 캠페인을 닫고, 워커가 비운 뒤 DB 쿠폰으로 명단을 다시 만들어 열며, 번호가 이어진다")
    @Test
    void recoversReservationsFromDbAfterLoss() throws Exception {
        Long campaignId = openCampaign();
        for (long userId = 1; userId <= 3; userId++) {
            assertThat(couponFacade.issue(campaignId, userId, NOW))
                .isEqualTo(new CouponFacade.IssueResult.Accepted((int) userId));
        }
        eventually(() -> couponCount(campaignId) == 3);
        couponReconciliation.reconcile(campaignId);

        redisCleanUp.truncateAll();

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 4L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        eventually(() -> acceptedWithNumber(campaignId, 4L, 4));

        assertThat(couponReservation.count(campaignId)).isEqualTo(4);
        assertThat(couponFacade.issue(campaignId, 2L, NOW))
            .isInstanceOf(CouponFacade.IssueResult.Issued.class)
            .extracting("issueNumber").isEqualTo(2);
    }
}
