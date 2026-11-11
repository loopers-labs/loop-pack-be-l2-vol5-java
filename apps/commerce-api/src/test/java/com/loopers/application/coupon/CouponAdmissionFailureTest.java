package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAdmission;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Instant;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponAdmissionFailureTest {

    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final String EPOCH_KEY = "coupon:issue:admission:epoch:v1";

    private final CouponFacade couponFacade;
    private final CouponAdmission couponAdmission;
    private final RedisTemplate<String, String> redis;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final SoldOutCampaigns soldOutCampaigns;

    @Autowired
    CouponAdmissionFailureTest(CouponFacade couponFacade, CouponAdmission couponAdmission,
                               @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                               DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp,
                               SoldOutCampaigns soldOutCampaigns) {
        this.couponFacade = couponFacade;
        this.couponAdmission = couponAdmission;
        this.redis = redis;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
        this.soldOutCampaigns = soldOutCampaigns;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        soldOutCampaigns.clear();
        couponAdmission.close();
    }

    private Long openCampaign() {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(10), true).getId();
    }

    private long scriptCalls() {
        Properties stats = redis.execute((RedisCallback<Properties>) connection ->
            connection.serverCommands().info("commandstats"));
        long calls = 0;
        for (String command : new String[] {"cmdstat_evalsha", "cmdstat_eval"}) {
            String line = stats == null ? null : stats.getProperty(command);
            if (line != null) {
                calls += Long.parseLong(line.replaceAll(".*calls=(\\d+).*", "$1"));
            }
        }
        return calls;
    }

    private void assertUnavailableWithoutRedis(Long campaignId, Long userId) {
        long before = scriptCalls();
        assertThatThrownBy(() -> couponFacade.issue(campaignId, userId, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        assertThat(scriptCalls()).isEqualTo(before);
    }

    @DisplayName("FLT-004: 수용은 닫힌 채 시작하고, 닫혀 있으면 Redis 에 묻지 않고 503 이다")
    @Test
    void startsClosed() {
        Long campaignId = openCampaign();

        assertThat(couponAdmission.isOpen()).isFalse();
        assertUnavailableWithoutRedis(campaignId, 1L);
    }

    @DisplayName("FLT-004: 열면 epoch 가 Redis 에 남고 발급이 된다")
    @Test
    void opensWithEpoch() {
        Long campaignId = openCampaign();

        CouponAdmission.Opened opened = couponAdmission.open();

        assertThat(couponAdmission.isOpen()).isTrue();
        assertThat(opened.epoch()).isNotBlank();
        assertThat(redis.opsForValue().get(EPOCH_KEY)).isEqualTo(opened.epoch());
        assertThat(couponFacade.issue(campaignId, 1L, NOW).newlyIssued()).isTrue();
    }

    @DisplayName("FLT-004: 열린 뒤 Redis 의 epoch 가 사라지면 상태 유실로 보고 닫히며, 기존 쿠폰 조회는 그대로다")
    @Test
    void closesWhenEpochIsLost() {
        Long campaignId = openCampaign();
        couponAdmission.open();
        Coupon issued = couponFacade.issue(campaignId, 1L, NOW).coupon();
        redis.delete(EPOCH_KEY);

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 2L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);

        assertThat(couponAdmission.isOpen()).isFalse();
        assertUnavailableWithoutRedis(campaignId, 3L);
        CouponFacade.Issued again = couponFacade.issue(campaignId, 1L, NOW);
        assertThat(again.newlyIssued()).isFalse();
        assertThat(again.coupon().id()).isEqualTo(issued.id());
    }
}
