package com.loopers.application.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponAdmission;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponAdmissionIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final String couponAdmission_KEY = "coupon:issue:admission:v1";
    private static final long DEADLINE_MILLIS = 2_000;

    private final CouponFacade couponFacade;
    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, String> redis;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final CouponAdmission couponAdmission;
    private final SoldOutCampaigns soldOutCampaigns;

    @Autowired
    CouponAdmissionIntegrationTest(CouponAdmission couponAdmission, CouponFacade couponFacade, DataSource dataSource, JdbcTemplate jdbcTemplate,
                                   @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                                   DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp,
                                   SoldOutCampaigns soldOutCampaigns) {
        this.couponFacade = couponFacade;
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.databaseCleanUp = databaseCleanUp;
        this.couponAdmission = couponAdmission;
        this.redisCleanUp = redisCleanUp;
        this.soldOutCampaigns = soldOutCampaigns;
    }

    @BeforeEach
    void setUp() {
        couponAdmission.open();
    }

    @AfterEach
    void tearDown() {
        couponAdmission.close();
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        soldOutCampaigns.clear();
    }

    private long activeTokens() {
        Long size = redis.opsForSet().size(couponAdmission_KEY);
        return size == null ? 0 : size;
    }

    private void awaitActiveTokens(long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (activeTokens() != expected) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("활성 token 이 " + expected + " 개가 되지 않았다: " + activeTokens());
            }
            Thread.sleep(20);
        }
    }

    private int couponRows(Long campaignId, Long userId) {
        Integer rows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM coupon WHERE campaign_id = ? AND user_id = ?", Integer.class, campaignId, userId);
        return rows == null ? -1 : rows;
    }

    @DisplayName("EXP-017: 자리 3 개가 모두 찬 동안 네 번째는 429 이고, 자리가 비면 다시 받는다")
    @Test
    void fourthIsRejectedWhileThreeHold() throws Exception {
        Long campaignId = couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(10), true).getId();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM coupon_campaign WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, campaignId);
                lock.executeQuery();
            }
            List<Future<CouponFacade.Issued>> holding = new ArrayList<>();
            for (long userId = 1; userId <= 3; userId++) {
                long user = userId;
                holding.add(executor.submit(() -> couponFacade.issue(campaignId, user, NOW)));
            }
            try {
                awaitActiveTokens(3);

                assertThatThrownBy(() -> couponFacade.issue(campaignId, 4L, NOW))
                    .isInstanceOf(CoreException.class)
                    .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_BUSY);
                assertThat(couponRows(campaignId, 4L)).isZero();
            } finally {
                holder.commit();
            }
            for (Future<CouponFacade.Issued> issued : holding) {
                assertThat(issued.get(DEADLINE_MILLIS, TimeUnit.MILLISECONDS).newlyIssued()).isTrue();
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(DEADLINE_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
        }

        assertThat(activeTokens()).isZero();
        assertThat(couponFacade.issue(campaignId, 4L, NOW).newlyIssued()).isTrue();
        Integer issuedCount = jdbcTemplate.queryForObject(
            "SELECT issued_count FROM coupon_campaign WHERE id = ?", Integer.class, campaignId);
        assertThat(issuedCount).isEqualTo(4);
        assertThat(activeTokens()).isZero();
    }

    @DisplayName("ADM-011: 품절을 한 번 본 뒤에는 자리가 모두 차 있어도 품절로 거절한다")
    @Test
    void soldOutIsRejectedBeforeAdmission() {
        Long campaignId = couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(1), true).getId();
        Coupon first = couponFacade.issue(campaignId, 1L, NOW).coupon();
        assertThatThrownBy(() -> couponFacade.issue(campaignId, 2L, NOW))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.COUPON_SOLD_OUT);
        redis.opsForSet().add(couponAdmission_KEY, "held-1", "held-2", "held-3");

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 3L, NOW))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.COUPON_SOLD_OUT);
        CouponFacade.Issued again = couponFacade.issue(campaignId, 1L, NOW);
        assertThat(again.newlyIssued()).isFalse();
        assertThat(again.coupon().id()).isEqualTo(first.id());
    }
}
