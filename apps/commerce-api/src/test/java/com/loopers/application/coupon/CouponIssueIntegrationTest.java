package com.loopers.application.coupon;

import com.loopers.application.coupon.CouponFacade.IssueResult;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CampaignName;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponPublishSlots;
import com.loopers.domain.coupon.DiscountAmount;
import com.loopers.domain.coupon.IssueQuota;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.kafka.TopicRecords;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponIssueIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-08T00:00:00Z");
    private static final long TIMEOUT_SECONDS = 10;

    private final CouponFacade couponFacade;
    private final CouponTransactions couponTransactions;
    private final CampaignCache campaignCache;
    private final CouponPublishSlots couponPublishSlots;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;
    private final RedisTemplate<String, String> redis;

    @Autowired
    CouponIssueIntegrationTest(@Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) RedisTemplate<String, String> redis,
                               CouponFacade couponFacade, CouponTransactions couponTransactions,
                               CampaignCache campaignCache, CouponPublishSlots couponPublishSlots,
                               JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp,
                               RedisCleanUp redisCleanUp) {
        this.couponFacade = couponFacade;
        this.couponTransactions = couponTransactions;
        this.campaignCache = campaignCache;
        this.couponPublishSlots = couponPublishSlots;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
        this.redis = redis;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
        campaignCache.clear();
    }

    private Long openCampaign(int quota, boolean active) {
        return couponFacade.openCampaign(CampaignName.of("블프 쿠폰"), DiscountAmount.of(5_000),
            IssueQuota.of(quota), active).getId();
    }

    private int couponRows(Long campaignId) {
        Integer rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupon WHERE campaign_id = ?", Integer.class,
            campaignId);
        return rows == null ? -1 : rows;
    }

    private int issuedCount(Long campaignId) {
        Integer count = jdbcTemplate.queryForObject("SELECT issued_count FROM coupon_campaign WHERE id = ?",
            Integer.class, campaignId);
        return count == null ? -1 : count;
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String issueInDb(Long campaignId, Long userId) {
        try {
            couponTransactions.issue(campaignId, userId, userId.intValue(), NOW);
            return "ISSUED";
        } catch (DomainException e) {
            return e.error().name();
        }
    }

    @Nested
    @DisplayName("CPN-004: 접수 응답")
    class Accept {

        @DisplayName("품절 뒤에도 접수된 사용자는 같은 번호다")
        @Test
        void repeatAfterSoldOutKeepsNumber() {
            Long campaignId = openCampaign(1, true);
            couponFacade.issue(campaignId, 42L, NOW);

            assertThatThrownBy(() -> couponFacade.issue(campaignId, 7L, NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.COUPON_SOLD_OUT);
            assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new IssueResult.Pending(1));
        }

        @DisplayName("RSV-001: 같은 사용자가 동시에 두 번 접수하면 번호 하나다")
        @Test
        void sameUserRaceGetsOneNumber() throws Exception {
            Long campaignId = openCampaign(5, true);

            List<IssueResult> results = runTogether(List.of(
                () -> couponFacade.issue(campaignId, 42L, NOW),
                () -> couponFacade.issue(campaignId, 42L, NOW)));

            assertThat(results).containsExactlyInAnyOrder(new IssueResult.Accepted(1), new IssueResult.Pending(1));
        }
    }

    @DisplayName("PUB-001: 202 를 줬으면 그 예약의 메시지가 broker 에 있다")
    @Test
    void acceptedMeansPublished() {
        Long campaignId = openCampaign(5, true);

        List<ConsumerRecord<String, byte[]>> records = TopicRecords.during(KafkaTestContainersConfig.COUPON_ISSUES,
            () -> couponFacade.issue(campaignId, 42L, NOW));

        assertThat(records).singleElement().satisfies(record ->
            assertThat(record.key()).isEqualTo(String.valueOf(campaignId)));
    }

    @DisplayName("PUB-004: 확인을 기다리는 요청이 상한(테스트 2)만큼 차 있으면 예약 없이 503 이고, 자리가 나면 받는다")
    @Test
    void rejectsWithoutReservingWhenInFlightIsFull() {
        Long campaignId = openCampaign(5, true);
        assertThat(couponPublishSlots.tryAcquire()).isTrue();
        assertThat(couponPublishSlots.tryAcquire()).isTrue();
        try {
            assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW))
                .isInstanceOf(CoreException.class)
                .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ISSUE_BUSY);
            assertThat(redis.opsForZSet().size("coupon:campaign:" + campaignId + ":issued")).isZero();
        } finally {
            couponPublishSlots.release();
            couponPublishSlots.release();
        }

        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new IssueResult.Accepted(1));
    }

    private void pauseRedis(Duration duration) {
        redis.execute((RedisCallback<Object>) connection -> connection.execute("CLIENT",
            "PAUSE".getBytes(StandardCharsets.UTF_8), String.valueOf(duration.toMillis()).getBytes(StandardCharsets.UTF_8)));
    }

    @DisplayName("FLT-001: Redis 가 상한 안에 답하지 않으면 접수하지 않고 503 이다. 명령이 늦게 적용돼 예약이 남았으면 재요청은 같은 번호다")
    @Test
    void refusesWhenRedisTimesOut() throws Exception {
        Long campaignId = openCampaign(5, true);
        pauseRedis(Duration.ofMillis(600));

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);

        Thread.sleep(600);
        assertThat(couponFacade.issue(campaignId, 42L, NOW)).isEqualTo(new IssueResult.Pending(1));
        assertThat(redis.opsForZSet().size("coupon:campaign:" + campaignId + ":issued")).isEqualTo(1);
    }

    @DisplayName("FLT-008: 예약 집합이 DB 발급 수보다 작으면 503 이고 접수하지 않는다")
    @Test
    void lostReservationsAreUnavailable() {
        Long campaignId = openCampaign(5, true);
        couponTransactions.issue(campaignId, 1L, 1, NOW);

        assertThatThrownBy(() -> couponFacade.issue(campaignId, 42L, NOW))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }

    @DisplayName("EXP-019: 한도 5 에 8 명이 동시에 DB 발급에 들어가면 5 명이 받고 3 명은 품절이다")
    @Test
    void quotaHoldsUnderRace() throws Exception {
        Long campaignId = openCampaign(5, true);
        List<Callable<String>> tasks = new ArrayList<>();
        for (long user = 1; user <= 8; user++) {
            long userId = user;
            tasks.add(() -> issueInDb(campaignId, userId));
        }

        List<String> outcomes = runTogether(tasks);

        assertThat(outcomes).filteredOn("ISSUED"::equals).hasSize(5);
        assertThat(outcomes).filteredOn(DomainError.COUPON_SOLD_OUT.name()::equals).hasSize(3);
        assertThat(couponRows(campaignId)).isEqualTo(5);
        assertThat(issuedCount(campaignId)).isEqualTo(5);
    }

    @DisplayName("ISS-006: 같은 사용자가 동시에 두 번 DB 발급에 들어가면 쿠폰은 하나다")
    @Test
    void sameUserRaceIssuesOnce() throws Exception {
        Long campaignId = openCampaign(5, true);

        List<String> outcomes = runTogether(List.of(
            () -> issueInDb(campaignId, 42L),
            () -> issueInDb(campaignId, 42L)));

        assertThat(outcomes).containsExactlyInAnyOrder("ISSUED", DomainError.COUPON_ALREADY_ISSUED.name());
        assertThat(couponRows(campaignId)).isEqualTo(1);
        assertThat(issuedCount(campaignId)).isEqualTo(1);
    }

    @DisplayName("CPN-007: 보유 목록은 본인 쿠폰만 최신순으로 보여 준다")
    @Test
    void listsOwnCouponsNewestFirst() {
        Long blackFriday = openCampaign(5, true);
        Long cyberMonday = openCampaign(5, true);
        Long yearEnd = openCampaign(5, true);
        Coupon older = couponTransactions.issue(blackFriday, 42L, 1, NOW);
        Coupon sameTimeFirst = couponTransactions.issue(cyberMonday, 42L, 1, NOW.plusSeconds(60));
        Coupon sameTimeSecond = couponTransactions.issue(yearEnd, 42L, 1, NOW.plusSeconds(60));
        couponTransactions.issue(blackFriday, 7L, 2, NOW.plusSeconds(120));

        assertThat(couponFacade.findMine(42L)).extracting(Coupon::id)
            .containsExactly(sameTimeSecond.id(), sameTimeFirst.id(), older.id());
    }
}
