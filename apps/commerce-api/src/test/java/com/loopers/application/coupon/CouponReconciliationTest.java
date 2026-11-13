package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
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

@SpringBootTest(properties = {
    "kafka.topics.coupon-issues=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER,
    "kafka.topics.coupon-issues-dlt=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER_DLT,
    "coupon.issue.worker.auto-startup=true"
})
class CouponReconciliationTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final Duration WITHIN = Duration.ofSeconds(10);
    private static final IssueQuota QUOTA = IssueQuota.of(100);

    private final CouponFacade couponFacade;
    private final CouponReservation couponReservation;
    private final CouponTransactions couponTransactions;
    private final CouponReconciliation couponReconciliation;
    private final CampaignCache campaignCache;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponReconciliationTest(CouponFacade couponFacade, CouponReservation couponReservation,
                             CouponTransactions couponTransactions, CouponReconciliation couponReconciliation,
                             CampaignCache campaignCache, JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry,
                             DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.couponReservation = couponReservation;
        this.couponTransactions = couponTransactions;
        this.couponReconciliation = couponReconciliation;
        this.campaignCache = campaignCache;
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
        couponReconciliation.clear();
    }

    private Long openCampaign() {
        Long campaignId = couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            QUOTA, true).getId();
        campaignCache.get(campaignId);
        return campaignId;
    }

    private void reserveOnly(Long campaignId, Long userId) {
        couponReservation.reserve(campaignId, userId, QUOTA, 0);
    }

    private int couponCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupon where campaign_id = ?", Integer.class, campaignId);
    }

    private Integer issueNumberOf(Long campaignId, Long userId) {
        return jdbcTemplate.queryForObject("select issue_number from coupon where campaign_id = ? and user_id = ?",
            Integer.class, campaignId, userId);
    }

    private double republished(Long campaignId) {
        return meterRegistry.counter("coupon.issue.republished", "campaignId", String.valueOf(campaignId)).count();
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

    @DisplayName("RCN-001: 워커가 비어 있으면 지난 실행 때 있었는데 DB 에 없는 번호를 전부 다시 보내 워커가 쿠폰으로 만든다. 마지막 번호도 빠지지 않는다")
    @Test
    void republishesEveryMissingNumberWhenTheWorkerIsDrained() throws Exception {
        Long campaignId = openCampaign();
        reserveOnly(campaignId, 1L);
        reserveOnly(campaignId, 2L);
        reserveOnly(campaignId, 3L);
        reserveOnly(campaignId, 4L);
        couponTransactions.issue(campaignId, 1L, 1, NOW);
        couponTransactions.issue(campaignId, 3L, 3, NOW);

        assertThat(couponReconciliation.reconcile(campaignId)).isZero();
        assertThat(couponReconciliation.reconcile(campaignId)).isEqualTo(2);
        eventually(() -> couponCount(campaignId) == 4);

        assertThat(issueNumberOf(campaignId, 2L)).isEqualTo(2);
        assertThat(issueNumberOf(campaignId, 4L)).isEqualTo(4);
        assertThat(republished(campaignId)).isEqualTo(2.0);
        assertThat(couponReconciliation.reconcile(campaignId)).isZero();
        assertThat(couponCount(campaignId)).isEqualTo(4);
    }
}
