package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
    "coupon.issue.publish.ack-timeout=300ms",
    "spring.kafka.producer.properties.linger.ms=3000"
})
class CouponIssuePublishUnconfirmedTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");

    private final CouponFacade couponFacade;
    private final CampaignCache campaignCache;
    private final RedisTemplate<String, String> redis;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponIssuePublishUnconfirmedTest(CouponFacade couponFacade, CampaignCache campaignCache,
                                      @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                                      KafkaTemplate<String, byte[]> kafkaTemplate,
                                      DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.campaignCache = campaignCache;
        this.redis = redis;
        this.kafkaTemplate = kafkaTemplate;
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

    @DisplayName("PUB-002: 확인이 상한 안에 오지 않으면 예약은 남고 503 이며, 재요청은 같은 번호로 PENDING 이고 그 메시지는 뒤늦게 broker 에 닿는다")
    @Test
    void keepsReservationWhenUnconfirmed() {
        Long campaignId = openCampaign();

        List<ConsumerRecord<String, byte[]>> records = TopicRecords.during(KafkaTestContainersConfig.COUPON_ISSUES, () -> {
            assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);

            assertThat(redis.opsForZSet().size("coupon:campaign:" + campaignId + ":issued")).isEqualTo(1);
            assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new CouponFacade.IssueResult.Pending(1));

            kafkaTemplate.flush();
        });

        assertThat(records).singleElement().satisfies(record ->
            assertThat(new String(record.value(), StandardCharsets.UTF_8)).contains("\"campaignId\":" + campaignId));
    }
}
