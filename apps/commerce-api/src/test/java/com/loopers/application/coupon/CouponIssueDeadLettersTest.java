package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponIssuePublisher;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
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
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.coupon-issues=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER,
    "kafka.topics.coupon-issues-dlt=" + KafkaTestContainersConfig.COUPON_ISSUES_WORKER_DLT,
    "coupon.issue.worker.auto-startup=true"
})
class CouponIssueDeadLettersTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final Duration WITHIN = Duration.ofSeconds(10);

    private final CouponFacade couponFacade;
    private final CouponIssuePublisher publisher;
    private final CouponIssueDeadLetters couponIssueDeadLetters;
    private final CampaignCache campaignCache;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponIssueDeadLettersTest(CouponFacade couponFacade, CouponIssuePublisher publisher,
                               CouponIssueDeadLetters couponIssueDeadLetters, CampaignCache campaignCache,
                               JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.publisher = publisher;
        this.couponIssueDeadLetters = couponIssueDeadLetters;
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

    private Long openInactiveCampaign() {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(100), false).getId();
    }

    private int couponCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select count(*) from coupon where campaign_id = ?", Integer.class, campaignId);
    }

    private List<Long> pendingDeadLetterIds(Long campaignId) {
        return jdbcTemplate.queryForList(
            "select id from coupon_issue_dlt_log where campaign_id = ? and status = 'PENDING'", Long.class, campaignId);
    }

    private String statusOf(Long deadLetterId) {
        return jdbcTemplate.queryForObject("select status from coupon_issue_dlt_log where id = ?", String.class, deadLetterId);
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

    @DisplayName("WRK-010: DLT 의 메시지는 로그에 PENDING 으로 남고, 원인을 고친 뒤 골라 다시 보내면 쿠폰이 생기고 REPLAYED 다")
    @Test
    void replaysPendingDeadLetterAfterTheCauseIsFixed() throws Exception {
        Long campaignId = openInactiveCampaign();
        publisher.publish(new CouponIssue(campaignId, 42L, 1, NOW));
        eventually(() -> pendingDeadLetterIds(campaignId).size() == 1);
        assertThat(couponCount(campaignId)).isZero();

        jdbcTemplate.update("update coupon_campaign set active = true where id = ?", campaignId);
        List<Long> ids = pendingDeadLetterIds(campaignId);

        assertThat(couponIssueDeadLetters.replay(ids)).isEqualTo(1);
        eventually(() -> couponCount(campaignId) == 1);
        assertThat(statusOf(ids.get(0))).isEqualTo("REPLAYED");
    }
}
