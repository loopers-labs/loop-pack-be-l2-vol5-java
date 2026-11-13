package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "kafka.topics.coupon-issues=" + CouponIssuePublishExpiredTest.TOPIC,
    "coupon.issue.publish.ack-timeout=6000ms",
    "spring.kafka.producer.properties.delivery.timeout.ms=1500",
    "spring.kafka.producer.properties.request.timeout.ms=1000",
    "spring.kafka.producer.properties.linger.ms=0"
})
class CouponIssuePublishExpiredTest {

    static final String TOPIC = "coupon-issues-under-replicated";
    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private final CouponFacade couponFacade;
    private final CampaignCache campaignCache;
    private final RedisTemplate<String, String> redis;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponIssuePublishExpiredTest(CouponFacade couponFacade, CampaignCache campaignCache,
                                  @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                                  DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.campaignCache = campaignCache;
        this.redis = redis;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @BeforeAll
    static void createTopicThatCannotSatisfyAcksAll() {
        KafkaTestContainersConfig.createTopic(TOPIC, 1, Map.of("min.insync.replicas", "2"));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
    }

    @DisplayName("PUB-002: broker 가 ISR 을 못 채워 producer 가 재시도하다 포기해도(delivery.timeout) 리더에 닿았을 수 있으므로 예약은 남고 503 이며, 재요청은 같은 번호로 PENDING 이다")
    @Test
    void keepsReservationWhenRetriesExpireAfterSending() {
        Long campaignId = couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(100), true).getId();

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);

        assertThat(redis.opsForZSet().score("coupon:campaign:" + campaignId + ":issued", "42")).isEqualTo(1.0);
        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new CouponFacade.IssueResult.Pending(1));
    }
}
