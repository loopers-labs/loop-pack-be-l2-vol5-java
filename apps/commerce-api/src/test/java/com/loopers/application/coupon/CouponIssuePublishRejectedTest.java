package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "kafka.topics.coupon-issues=coupon-issues-missing")
class CouponIssuePublishRejectedTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private final CouponFacade couponFacade;
    private final CampaignCache campaignCache;
    private final RedisTemplate<String, String> redis;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponIssuePublishRejectedTest(CouponFacade couponFacade, CampaignCache campaignCache,
                                      @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                                      DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.campaignCache = campaignCache;
        this.redis = redis;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    private Long openCampaign() {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(100), true).getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
    }

    @DisplayName("PUB-002: producer 가 포기한 실패면 예약을 지우고 503 이며, 다시 요청하면 새로 접수를 시도한다")
    @Test
    void cancelsReservationWhenRejected() {
        Long campaignId = openCampaign();

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);

        assertThat(redis.opsForZSet().count("coupon:campaign:" + campaignId + ":issued", 1, Double.MAX_VALUE)).isZero();
        assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW)).isInstanceOf(CoreException.class);
    }
}
