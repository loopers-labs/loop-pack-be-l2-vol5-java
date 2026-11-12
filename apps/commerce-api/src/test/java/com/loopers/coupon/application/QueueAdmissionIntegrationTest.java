package com.loopers.coupon.application;

import com.loopers.config.redis.RedisConfig;
import com.loopers.coupon.adapter.out.persistence.CouponCampaignJpaRepository;
import com.loopers.coupon.adapter.out.persistence.CouponJpaRepository;
import com.loopers.coupon.application.port.out.CouponGatePort;
import com.loopers.coupon.application.port.out.CouponGatePort.IssueDecision;
import com.loopers.coupon.application.port.out.CouponGatePort.QueueStateType;
import com.loopers.coupon.application.port.out.CouponIssueOutboxPort;
import com.loopers.coupon.domain.CampaignSnapshot;
import com.loopers.coupon.domain.CouponCampaign;
import com.loopers.coupon.support.CouponTestFixtures;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 입장 (D-4-3)과 Redis 데이터 유실 뒤 자동 복구 (D-4-7). 구간 1시간·구간당 3명.
 */
@SpringBootTest(properties = {"coupon.queue.admission.per-tick=3", "coupon.queue.admission.tick=1h"})
class QueueAdmissionIntegrationTest {

    @Autowired
    private QueueAdmissionService admissionService;

    @Autowired
    private CouponGatePort gate;

    @Autowired
    private CouponIssueOutboxPort outboxPort;

    @Autowired
    private CouponCampaignJpaRepository campaignJpaRepository;

    @Autowired
    private CouponJpaRepository couponJpaRepository;

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> redis;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @Autowired
    private com.loopers.coupon.application.OpenCampaigns openCampaigns;

    @Autowired
    private com.loopers.coupon.application.SoldOutFlags soldOutFlags;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        // 테이블을 비우면 id가 다시 1부터라, 서버 메모리의 캠페인·품절 표시가 다음 테스트의 같은 id에 남는다
        openCampaigns.refresh();
        soldOutFlags.clear();
    }

    private CampaignSnapshot open(int total) {
        CouponCampaign campaign = CouponTestFixtures.openCampaign(campaignJpaRepository, couponJpaRepository, total, null);
        openCampaigns.refresh();
        admissionService.recover(campaign.getId());
        return CampaignSnapshot.of(campaign);
    }

    /** 바로 입장을 피하려고 줄에 직접 넣는다 (score = 도착 순서) */
    private void enqueue(Long campaignId, int users) {
        for (int i = 1; i <= users; i++) {
            redis.opsForZSet().add("coupon:{" + campaignId + "}:queue", String.valueOf(i), i);
        }
    }

    @DisplayName("필수 실험 ⑦ 입장 스케줄러 4개(서버 4대)가 같은 구간에 동시에 돌아도 구간당 3명만, 앞에서부터 들어간다.")
    @Test
    void concurrentSchedulersAdmitPerTickLimitInOrder() throws Exception {
        CampaignSnapshot campaign = open(100);
        enqueue(campaign.id(), 10);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(executor.submit(() -> {
                start.await(10, TimeUnit.SECONDS);
                return admissionService.admit(campaign).admitted();
            }));
        }
        start.countDown();
        int admitted = 0;
        for (Future<Integer> future : futures) {
            admitted += future.get(30, TimeUnit.SECONDS);
        }
        executor.shutdownNow();

        assertThat(admitted).isEqualTo(3);
        assertThat(gate.status(campaign.id(), 1L, 100).type()).isEqualTo(QueueStateType.ADMITTED);
        assertThat(gate.status(campaign.id(), 3L, 100).type()).isEqualTo(QueueStateType.ADMITTED);
        assertThat(gate.status(campaign.id(), 4L, 100).type()).isEqualTo(QueueStateType.WAITING);
        assertThat(gate.status(campaign.id(), 4L, 100).ahead()).isZero();
    }

    @DisplayName("품절이면 입장을 멈추고 줄을 지운다 — 기다리던 사람은 SOLD_OUT을 받는다.")
    @Test
    void soldOutClearsQueue() {
        CampaignSnapshot campaign = open(1);
        gate.register(campaign.id(), 500L, 1, 10);                    // 바로 입장
        assertThat(gate.issue(campaign.id(), 500L, 1)).isEqualTo(IssueDecision.ACCEPTED);
        enqueue(campaign.id(), 3);

        assertThat(admissionService.admit(campaign).status()).isEqualTo(CouponGatePort.AdmitStatus.SOLD_OUT);
        assertThat(redis.hasKey("coupon:{" + campaign.id() + "}:queue")).isFalse();
        assertThat(gate.status(campaign.id(), 2L, 1).type()).isEqualTo(QueueStateType.SOLD_OUT);
    }

    @DisplayName("필수 실험 ⑥ Redis 데이터가 사라지면 판정은 UNKNOWN(503)이고, 입장 스케줄러가 outbox로 당첨자를 되살린다. 당첨자는 유지되고 남은 수량만 새로 받는다.")
    @Test
    void autoRecoveryKeepsWinners() {
        CampaignSnapshot campaign = open(2);
        gate.register(campaign.id(), 500L, 2, 10);
        assertThat(gate.issue(campaign.id(), 500L, 2)).isEqualTo(IssueDecision.ACCEPTED);
        outboxPort.appendIfAbsent(campaign.id(), 500L, ZonedDateTime.now());

        redisCleanUp.truncateAll(); // FLUSHALL
        assertThat(gate.issue(campaign.id(), 500L, 2)).isEqualTo(IssueDecision.UNKNOWN);

        admissionService.admit(campaign); // UNKNOWN → 복구

        assertThat(gate.issue(campaign.id(), 500L, 2)).isEqualTo(IssueDecision.ALREADY);
        assertThat(gate.winners(campaign.id())).containsExactly(500L);
        assertThat(gate.register(campaign.id(), 600L, 2, 10).type()).isIn(QueueStateType.ADMITTED, QueueStateType.WAITING);
    }
}
