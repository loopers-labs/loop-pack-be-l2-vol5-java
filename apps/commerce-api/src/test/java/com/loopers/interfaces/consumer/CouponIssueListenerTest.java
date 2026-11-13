package com.loopers.interfaces.consumer;

import com.loopers.application.coupon.CampaignCache;
import com.loopers.application.coupon.CouponFacade;
import com.loopers.domain.coupon.CampaignName;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "kafka.topics.coupon-issues=" + CouponIssueListenerTest.TOPIC,
    "coupon.issue.worker.auto-startup=true"
})
class CouponIssueListenerTest {

    static final String TOPIC = "coupon-issues-worker";

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00.123456Z");
    private static final Duration WITHIN = Duration.ofSeconds(10);

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 3, Map.of());
    }

    private final CouponFacade couponFacade;
    private final CampaignCache campaignCache;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponIssueListenerTest(CouponFacade couponFacade, CampaignCache campaignCache, JdbcTemplate jdbcTemplate,
                            DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
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

    private int issuedCount(Long campaignId) {
        return jdbcTemplate.queryForObject("select issued_count from coupon_campaign where id = ?", Integer.class, campaignId);
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

    @DisplayName("WRK-003: 접수된 메시지 하나는 같은 번호와 예약 시각을 가진 쿠폰 행 하나가 되고 발급 수가 1 오르며, 재요청은 ISSUED 다")
    @Test
    void recordsOneCouponPerMessage() throws Exception {
        Long campaignId = openCampaign();

        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new CouponFacade.IssueResult.Accepted(1));
        eventually(() -> couponCount(campaignId) == 1);

        Map<String, Object> coupon = jdbcTemplate.queryForMap(
            "select issue_number, issued_at from coupon where campaign_id = ? and user_id = ?", campaignId, 42L);
        assertThat(coupon.get("issue_number")).isEqualTo(1);
        assertThat(((LocalDateTime) coupon.get("issued_at")).toInstant(ZoneOffset.UTC)).isEqualTo(NOW);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isInstanceOf(CouponFacade.IssueResult.Issued.class);
    }
}
