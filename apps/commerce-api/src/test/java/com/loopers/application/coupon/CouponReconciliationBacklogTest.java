package com.loopers.application.coupon;

import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.CouponIssuePublisher;
import com.loopers.domain.coupon.CouponIssuePublisher.CouponIssue;
import com.loopers.domain.coupon.CouponReservation;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class CouponReconciliationBacklogTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final IssueQuota QUOTA = IssueQuota.of(100);

    private final CouponFacade couponFacade;
    private final CouponReservation couponReservation;
    private final CouponTransactions couponTransactions;
    private final CouponReconciliation couponReconciliation;
    private final CouponIssuePublisher publisher;
    private final CampaignCache campaignCache;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponReconciliationBacklogTest(CouponFacade couponFacade, CouponReservation couponReservation,
                                    CouponTransactions couponTransactions, CouponReconciliation couponReconciliation,
                                    CouponIssuePublisher publisher, CampaignCache campaignCache,
                                    DatabaseCleanUp databaseCleanUp, RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.couponReservation = couponReservation;
        this.couponTransactions = couponTransactions;
        this.couponReconciliation = couponReconciliation;
        this.publisher = publisher;
        this.campaignCache = campaignCache;
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

    @DisplayName("RCN-001: 워커가 밀려 있으면 DB 최대 번호보다 큰 번호는 큐에 있는 것으로 보고 다시 보내지 않는다")
    @Test
    void leavesNumbersAboveDbMaxWhileTheWorkerIsBehind() {
        Long campaignId = openCampaign();
        publisher.publish(new CouponIssue(campaignId, 99L, 99, NOW));
        couponReservation.reserve(campaignId, 1L, QUOTA, 0);
        couponReservation.reserve(campaignId, 2L, QUOTA, 0);
        couponReservation.reserve(campaignId, 3L, QUOTA, 0);
        couponReservation.reserve(campaignId, 4L, QUOTA, 0);
        couponTransactions.issue(campaignId, 1L, 1, NOW);
        couponTransactions.issue(campaignId, 3L, 3, NOW);
        assertThat(couponReconciliation.reconcile(campaignId)).isZero();

        List<ConsumerRecord<String, byte[]>> records = TopicRecords.during(KafkaTestContainersConfig.COUPON_ISSUES,
            () -> assertThat(couponReconciliation.reconcile(campaignId)).isEqualTo(1));

        assertThat(records).singleElement().satisfies(record ->
            assertThat(new String(record.value())).contains("\"userId\":2"));
    }
}
