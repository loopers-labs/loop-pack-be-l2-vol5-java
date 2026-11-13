package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.support.error.ErrorType;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
        couponReconciliation.clear();
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

    @DisplayName("FLT-008: Redis 가 비면 첫 접수가 캠페인을 닫고 닫힌 채 남는다. 관리자가 복구하면 202 를 받은 사람은 전원 쿠폰이 있고, 번호가 이어지며, 한도를 넘겨 받지 않는다")
    @Test
    void recoversReservationsFromDbAfterLoss() throws Exception {
        Long campaignId = couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(4), true).getId();
        for (long userId = 1; userId <= 3; userId++) {
            assertThat(couponFacade.issue(campaignId, userId, NOW))
                .isEqualTo(new CouponFacade.IssueResult.Accepted((int) userId));
        }
        eventually(() -> couponCount(campaignId) == 3);

        redisCleanUp.truncateAll();

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 4L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        assertThat(couponReservation.isClosed(campaignId)).isTrue();

        assertThat(couponFacade.recover(campaignId)).isEqualTo(3);

        assertThat(couponReservation.isClosed(campaignId)).isFalse();
        assertThat(couponFacade.issue(campaignId, 2L, NOW))
            .isInstanceOf(CouponFacade.IssueResult.Issued.class)
            .extracting("issueNumber").isEqualTo(2);
        assertThat(couponFacade.issue(campaignId, 4L, NOW)).isEqualTo(new CouponFacade.IssueResult.Accepted(4));
        assertThatThrownBy(() -> couponFacade.issue(campaignId, 5L, NOW))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.COUPON_SOLD_OUT);
    }

    @DisplayName("FLT-008: 복구는 같은 번호를 가진 쿠폰이 페이지 경계에 걸려 있어도 전원을 명단에 다시 올린다")
    @Test
    void rebuildsEveryCouponEvenWhenNumbersRepeatAcrossPages() throws Exception {
        Long campaignId = couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(2_000), true).getId();
        List<Object[]> rows = new ArrayList<>();
        for (long userId = 1; userId <= 1_000; userId++) {
            rows.add(new Object[] {campaignId, userId, (int) userId});
        }
        rows.add(new Object[] {campaignId, 1_001L, 1_000});
        jdbcTemplate.batchUpdate("insert into coupon (campaign_id, user_id, issue_number, discount_amount, issued_at, "
            + "created_at, updated_at) values (?, ?, ?, 5000, now(6), now(6), now(6))", rows);
        jdbcTemplate.update("update coupon_campaign set issued_count = ? where id = ?", rows.size(), campaignId);
        couponReservation.close(campaignId);

        assertThat(couponFacade.recover(campaignId)).isEqualTo(1_001);

        assertThat(couponReservation.isClosed(campaignId)).isFalse();
        assertThat(couponReservation.count(campaignId)).isEqualTo(1_001);
        assertThat(couponReservation.isReserved(campaignId, 1_001L)).isTrue();
        assertThat(couponFacade.issue(campaignId, 1_002L, NOW)).isEqualTo(new CouponFacade.IssueResult.Accepted(1_001));
    }
}
