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
import org.springframework.test.annotation.DirtiesContext;
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
// 이 클래스만 쓰는 설정(속성·mock)이라 컨텍스트를 다른 테스트와 공유하지 않는다. 끝나면 닫아 커넥션 풀(최소 30개)을 돌려준다 —
// 닫지 않으면 캐시된 컨텍스트들의 커넥션이 MySQL max_connections(151)를 넘어 다른 테스트가 뜨지 못했다(11-12).
@DirtiesContext
@SpringBootTest(properties = {"coupon.queue.admission.per-tick=3", "coupon.queue.admission.tick=1h"})
class QueueAdmissionIntegrationTest {

    @Autowired
    private QueueAdmissionService admissionService;

    @Autowired
    private CouponGatePort gate;

    @Autowired
    private CouponIssueOutboxPort outboxPort;

    @Autowired
    private com.loopers.coupon.application.port.in.CouponIssueUseCase issueUseCase;

    @Autowired
    private com.loopers.coupon.application.port.in.CouponQueueUseCase queueUseCase;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
        try {
            for (Future<Integer> future : futures) {
                admitted += future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

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
        // 남은 수량(1장)만 새로 받는다
        assertThat(gate.register(campaign.id(), 600L, 2, 10).type()).isEqualTo(QueueStateType.ADMITTED);
        assertThat(gate.issue(campaign.id(), 600L, 2)).isEqualTo(IssueDecision.ACCEPTED);
        assertThat(gate.register(campaign.id(), 700L, 2, 10).type()).isEqualTo(QueueStateType.SOLD_OUT);
    }

    @DisplayName("Lua 판정이 당첨자 수에서 멈춘다: 쿠폰 2장에 입장한 3명이 동시에 발급하면 2명 당첨·1명 품절(409), 당첨자 Set과 기록은 2명이다.")
    @Test
    void luaStopsAtTotalUnderConcurrentIssue() throws Exception {
        CampaignSnapshot campaign = open(2);
        // 입장은 남은 쿠폰 수만큼만 하므로, Lua 판정의 마지막 방어를 보려고 입장 키를 직접 3개 만든다 (입장 키가 남은 채 Redis가 복구된 경우 등)
        for (long user = 1; user <= 3; user++) {
            redis.opsForValue().set("coupon:{" + campaign.id() + "}:entry:" + user, "1", java.time.Duration.ofSeconds(30));
        }

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (long user = 1; user <= 3; user++) {
                long userId = user;
                futures.add(executor.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return issueUseCase.issue(campaign.id(), userId).status().name();
                    } catch (com.loopers.support.error.CoreException e) {
                        return e.getErrorType().name();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }

            assertThat(results).containsExactlyInAnyOrder("ACCEPTED", "ACCEPTED", "COUPON_SOLD_OUT");
        } finally {
            executor.shutdownNow();
        }
        assertThat(gate.winners(campaign.id())).hasSize(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon_issue_outbox WHERE campaign_id = ?", Long.class, campaign.id()))
            .isEqualTo(2);
    }

    @DisplayName("남은 쿠폰 수만큼만 들인다: 쿠폰 2장이면 줄 5명 중 앞 2명만 입장. 당첨되면 자리가 줄고, 입장하고 떠난 사람(입장 기록 만료)의 자리는 다음 사람에게 간다.")
    @Test
    void admitsOnlyRemainingCoupons() {
        CampaignSnapshot campaign = open(2);
        enqueue(campaign.id(), 5);

        assertThat(admissionService.admit(campaign).admitted()).isEqualTo(2);
        assertThat(gate.status(campaign.id(), 1L, 2).type()).isEqualTo(QueueStateType.ADMITTED);
        assertThat(gate.status(campaign.id(), 2L, 2).type()).isEqualTo(QueueStateType.ADMITTED);
        assertThat(gate.status(campaign.id(), 3L, 2).type()).isEqualTo(QueueStateType.WAITING);

        assertThat(gate.issue(campaign.id(), 1L, 2)).isEqualTo(IssueDecision.ACCEPTED); // 1명 당첨, 1명은 아직 입장 중
        assertThat(admissionService.admit(campaign).admitted()).isZero();               // 남은 자리 = 2 − 1 − 1 = 0

        // 2번이 발급하지 않고 떠났다: 입장 기록이 만료됐다고 본다
        redis.opsForZSet().add("coupon:{" + campaign.id() + "}:admitted", "2", 1);
        redis.delete("coupon:{" + campaign.id() + "}:entry:2");
        assertThat(admissionService.admit(campaign).admitted()).isEqualTo(1);
        assertThat(gate.status(campaign.id(), 3L, 2).type()).isEqualTo(QueueStateType.ADMITTED); // 줄의 다음 사람
    }

    @DisplayName("캠페인이 막 열려 상태 키가 없어도, 요청이 그 자리에서 복구하고 답한다 (스케줄러를 기다리는 503이 없다).")
    @Test
    void requestRecoversMissingStateKey() {
        CouponCampaign campaign = CouponTestFixtures.openCampaign(campaignJpaRepository, couponJpaRepository, 5, null);
        openCampaigns.refresh(); // recover를 부르지 않는다 = 상태 키 없음

        assertThat(queueUseCase.register(campaign.getId(), 42L).status().name()).isEqualTo("ADMITTED");
        assertThat(issueUseCase.issue(campaign.getId(), 42L).status().name()).isEqualTo("ACCEPTED");
    }

    @DisplayName("다른 서버가 복구 중(복구 잠금이 잡혀 있음)이면 상태 키가 없는 동안 503이고, 당첨자 Set은 비어 있다.")
    @Test
    void unknownWhileOtherServerRecoversIs503() {
        CouponCampaign campaign = CouponTestFixtures.openCampaign(campaignJpaRepository, couponJpaRepository, 5, null);
        openCampaigns.refresh();
        redis.opsForValue().set("coupon:{" + campaign.getId() + "}:rebuild", "other-server");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> issueUseCase.issue(campaign.getId(), 42L))
            .isInstanceOfSatisfying(com.loopers.support.error.CoreException.class,
                e -> assertThat(e.getErrorType()).isEqualTo(com.loopers.support.error.ErrorType.SERVICE_UNAVAILABLE));
        assertThat(gate.winners(campaign.getId())).isEmpty();
    }
}
