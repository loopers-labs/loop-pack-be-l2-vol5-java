package com.loopers.application.coupon;

import com.loopers.CommerceApiApplication;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponQueuePort;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.testcontainers.RedisTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.ZonedDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = CommerceApiApplication.class)
@Import(RedisTestContainersConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponPromoteSchedulerTest {

    @Autowired CouponPromoteScheduler scheduler;
    @Autowired CouponAdmissionGate admissionGate;
    @Autowired CouponQueuePort queuePort;
    @Autowired CouponCampaignJpaRepository campaignJpaRepository;
    @Autowired DatabaseCleanUp databaseCleanUp;
    @Autowired RedisCleanUp redisCleanUp;

    private Long campaignId;

    @BeforeEach
    void setUp() {
        CouponCampaignModel campaign = campaignJpaRepository.save(
            new CouponCampaignModel("스케줄러 테스트", 50, ZonedDateTime.now(), ZonedDateTime.now().plusDays(1))
        );
        campaignId = campaign.getId();
    }

    @AfterEach
    void tearDown() {
        admissionGate.close(campaignId);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("게이트가 열려 있으면 스케줄러가 사용자를 승격한다.")
    @Test
    void promote_gateOpen_promotesUsers() {
        admissionGate.open(campaignId);
        queuePort.enqueue(campaignId, 1L, 10);
        queuePort.enqueue(campaignId, 2L, 10);

        await().atMost(2, TimeUnit.SECONDS).untilAsserted(() -> {
            CouponQueuePort.QueueStatusResult s = queuePort.status(campaignId, 1L, 30L);
            assertThat(s.status()).isEqualTo(CouponQueuePort.QueueStatus.ADMITTED);
        });
    }

    @DisplayName("게이트가 닫혀 있으면 스케줄러가 사용자를 승격하지 않는다.")
    @Test
    void promote_gateClosed_doesNotPromote() throws InterruptedException {
        queuePort.enqueue(campaignId, 1L, 10);
        Thread.sleep(500);

        CouponQueuePort.QueueStatusResult s = queuePort.status(campaignId, 1L, 30L);
        assertThat(s.status()).isEqualTo(CouponQueuePort.QueueStatus.QUEUED);
    }

    @DisplayName("scheduler.promote()를 직접 호출해도 중복 실행되지 않는다.")
    @Test
    void promote_noOverlapViaAtomicBoolean() {
        admissionGate.open(campaignId);
        queuePort.enqueue(campaignId, 1L, 10);

        scheduler.promote();
        scheduler.promote();

        CouponQueuePort.QueueStatusResult s = queuePort.status(campaignId, 1L, 30L);
        assertThat(s.status()).isEqualTo(CouponQueuePort.QueueStatus.ADMITTED);
    }
}
