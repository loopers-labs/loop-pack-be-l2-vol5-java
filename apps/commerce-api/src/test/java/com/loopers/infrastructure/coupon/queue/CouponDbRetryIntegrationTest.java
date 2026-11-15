package com.loopers.infrastructure.coupon.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.coupon.queue.CouponQueueWorker;
import com.loopers.application.coupon.queue.CouponQueueWorkerState;
import com.loopers.application.coupon.queue.CouponRequestQueue;
import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.infrastructure.coupon.CouponCampaignJpaEntity;
import com.loopers.infrastructure.coupon.CouponCampaignJpaRepository;
import com.loopers.infrastructure.coupon.CouponCampaignRepositoryImpl;
import com.loopers.infrastructure.coupon.CouponJpaRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "coupon.queue.worker.enabled=true",
        "coupon.queue.open-on-start=true", "coupon.queue.worker.poll-delay-ms=20"})
@Import(CouponDbRetryIntegrationTest.LockProbeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CouponDbRetryIntegrationTest {
    private static final String PREFIX = "coupon:{db-retry-" + UUID.randomUUID() + "}";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void isolateQueue(DynamicPropertyRegistry registry) {
        registry.add("coupon.queue.key", () -> PREFIX);
    }

    @Autowired private CouponCampaignJpaRepository campaigns;
    @Autowired private CouponJpaRepository couponRows;
    @Autowired private CouponRequestQueue queue;
    @Autowired private CouponQueueWorker worker;
    @Autowired private CouponQueueWorkerState state;
    @Autowired private CampaignLockProbe probe;
    @Autowired private DataSource dataSource;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;
    private final List<Long> createdCampaigns = new ArrayList<>();

    @Test
    void 실제_잠금_대기_시간초과를_세번_재시도한_뒤_실패시키고_다음_신청을_처리한다() throws Exception {
        // 활성 캠페인 5장, 발급 0장, 대기 0건. 별도 커넥션이 캠페인 행을 잠근다.
        long campaignId = campaign();
        long nextCampaignId = campaign();
        long firstUser = 900_001L;
        long secondUser = 900_002L;
        probe.timeOutFor(campaignId);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            lockCampaign(blocker, campaignId);
            try {
                var first = queue.submit(campaignId, firstUser);
                assertThat(first.outcome()).isEqualTo(CouponRequestQueue.SubmitOutcome.ACCEPTED);
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                        assertThat(status(first.requestId())).isEqualTo("PROCESSING"));
                var second = queue.submit(nextCampaignId, secondUser);
                assertThat(second.outcome()).isEqualTo(CouponRequestQueue.SubmitOutcome.ACCEPTED);

                // MySQL 1205가 반복되고 각 트랜잭션이 롤백되어야 한다.
                await().atMost(Duration.ofSeconds(35)).untilAsserted(() -> {
                    assertThat(probe.errorCodes()).hasSize(4);
                    assertThat(redis.opsForHash().hasKey(PREFIX + ":requests", first.requestId())).isFalse();
                });
                assertThat(probe.errorCodes()).containsExactly(1205, 1205, 1205, 1205);
                assertThat(couponRows.countByCampaignId(campaignId)).isZero();
                assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isZero();
                assertThat(state.acceptingNewRequests()).isTrue();

                // 1·2·4초 간격은 실제 대기 1초를 더한 시도 시작 시각 차이로 확인한다.
                var attempts = probe.attemptNanos();
                assertThat(attempts).hasSize(4);
                assertThat(Duration.ofNanos(attempts.get(1) - attempts.get(0))).isGreaterThan(Duration.ofMillis(1800));
                assertThat(Duration.ofNanos(attempts.get(2) - attempts.get(1))).isGreaterThan(Duration.ofMillis(2800));
                assertThat(Duration.ofNanos(attempts.get(3) - attempts.get(2))).isGreaterThan(Duration.ofMillis(4800));

                blocker.rollback();
                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    assertThat(couponRows.findByCampaignIdAndUserId(nextCampaignId, secondUser)).isPresent();
                    assertThat(redis.opsForHash().hasKey(PREFIX + ":requests", second.requestId())).isFalse();
                });
                assertThat(couponRows.countByCampaignId(campaignId)).isZero();
                assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isZero();
                assertThat(couponRows.findByCampaignIdAndUserId(campaignId, firstUser)).isEmpty();
                assertThat(couponRows.countByCampaignId(nextCampaignId)).isEqualTo(1);
                assertThat(campaigns.findById(nextCampaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
                assertThat(couponRows.findByCampaignIdAndUserId(nextCampaignId, secondUser)).isPresent();
            } finally {
                blocker.rollback();
            }
        }
    }

    @Test
    void 실제_교착상태로_롤백된_발급은_다시_시도해_한_장만_저장한다() throws Exception {
        // 외부 트랜잭션은 캠페인 A, 워커는 B를 먼저 잠가 순서를 교차시킨다.
        long campaignA = campaign();
        long campaignB = campaign();
        List<Long> weightedRows = List.of(campaign(), campaign(), campaign());
        long userId = 900_003L;
        var deadlock = probe.deadlockOnce(campaignA, campaignB);
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            lockCampaign(blocker, campaignA);
            for (long id : weightedRows) {
                try (var update = blocker.prepareStatement("update coupon_campaigns set name = concat(name, 'x') where id = ?")) {
                    update.setLong(1, id);
                    update.executeUpdate();
                }
            }
            try {
                var submitted = queue.submit(campaignA, userId);
                assertThat(submitted.outcome()).isEqualTo(CouponRequestQueue.SubmitOutcome.ACCEPTED);
                assertThat(deadlock.workerHasSecondLock.await(10, TimeUnit.SECONDS)).isTrue();

                // 외부 A→B, 워커 B→A. 외부 트랜잭션이 더 많은 행을 수정하여 워커가 희생자가 된다.
                var external = executor.submit(() -> {
                    deadlock.externalWaiting.countDown();
                    lockCampaign(blocker, campaignB);
                    return null;
                });
                external.get(10, TimeUnit.SECONDS);
                // MySQL releases B before the worker thread has necessarily observed its exception.
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(probe.errorCodes()).contains(1213));
                assertThat(couponRows.countByCampaignId(campaignA)).isZero();
                assertThat(campaigns.findById(campaignA).orElseThrow().getIssuedCount()).isZero();
                blocker.rollback();

                await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                    assertThat(couponRows.findByCampaignIdAndUserId(campaignA, userId)).isPresent();
                    assertThat(redis.opsForHash().hasKey(PREFIX + ":requests", submitted.requestId())).isFalse();
                });
                assertThat(probe.attemptNanos()).hasSize(2);
                assertThat(couponRows.countByCampaignId(campaignA)).isEqualTo(1);
                assertThat(campaigns.findById(campaignA).orElseThrow().getIssuedCount()).isEqualTo(1);
                assertThat(state.acceptingNewRequests()).isTrue();
            } finally {
                deadlock.externalWaiting.countDown();
                blocker.rollback();
            }
        }
    }

    private long campaign() {
        long id = campaigns.saveAndFlush(new CouponCampaignJpaEntity("경합 실습", true, 5, "할인권")).getId();
        createdCampaigns.add(id);
        return id;
    }

    private static void lockCampaign(Connection connection, long campaignId) throws SQLException {
        try (var statement = connection.prepareStatement("select id from coupon_campaigns where id = ? for update")) {
            statement.setLong(1, campaignId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalStateException("테스트 캠페인을 찾지 못했습니다.");
                }
            }
        }
    }

    private JsonNode request(String requestId) throws Exception {
        Object raw = redis.opsForHash().get(PREFIX + ":requests", requestId);
        assertThat(raw).isNotNull();
        return JSON.readTree(raw.toString());
    }

    private String status(String requestId) throws Exception {
        return request(requestId).path("status").asText();
    }

    @AfterEach
    void cleanTestData() {
        worker.stop();
        redis.delete(List.of(PREFIX + ":waiting", PREFIX + ":requests", PREFIX + ":users",
                PREFIX + ":processing", PREFIX + ":completed", PREFIX + ":campaigns"));
        for (long campaignId : createdCampaigns) {
            couponRows.deleteByCampaignId(campaignId);
            campaigns.deleteById(campaignId);
        }
    }

    @TestConfiguration
    static class LockProbeConfiguration {
        @Bean
        @Primary
        CampaignLockProbe campaignLockProbe(CouponCampaignRepositoryImpl delegate, JdbcTemplate jdbc) {
            return new CampaignLockProbe(delegate, jdbc);
        }
    }

    /** 낮은 lock wait timeout과 교차 잠금만 주입하며 오류는 실제 MySQL이 발생시킨다. */
    static class CampaignLockProbe implements CouponCampaignRepository {
        private final CouponCampaignRepository delegate;
        private final JdbcTemplate jdbc;
        private final AtomicLong target = new AtomicLong(-1);
        private final List<Integer> errorCodes = new CopyOnWriteArrayList<>();
        private final List<Long> attemptNanos = new CopyOnWriteArrayList<>();
        @PersistenceContext private EntityManager entityManager;
        private volatile Deadlock deadlock;

        CampaignLockProbe(CouponCampaignRepository delegate, JdbcTemplate jdbc) {
            this.delegate = delegate;
            this.jdbc = jdbc;
        }

        void timeOutFor(long campaignId) {
            target.set(campaignId);
        }

        Deadlock deadlockOnce(long campaignId, long secondCampaign) {
            target.set(campaignId);
            deadlock = new Deadlock(secondCampaign);
            return deadlock;
        }

        List<Integer> errorCodes() {
            return List.copyOf(errorCodes);
        }

        List<Long> attemptNanos() {
            return List.copyOf(attemptNanos);
        }

        @Override
        public Optional<CouponCampaign> lockById(long campaignId) {
            if (target.get() != campaignId) {
                return delegate.lockById(campaignId);
            }
            // MySQL session settings affect the JPA lock only when JDBC and JPA share a connection.
            Long jdbcConnectionId = jdbc.queryForObject("select connection_id()", Long.class);
            Long jpaConnectionId = entityManager.unwrap(Session.class).doReturningWork(connection -> {
                try (var statement = connection.createStatement();
                     var rows = statement.executeQuery("select connection_id()")) {
                    rows.next();
                    return rows.getLong(1);
                }
            });
            assertThat(jdbcConnectionId).isEqualTo(jpaConnectionId);
            Integer original = jdbc.queryForObject("select @@session.innodb_lock_wait_timeout", Integer.class);
            Deadlock current = deadlock;
            // A deadlock must be detected before any lock wait timeout even on a slow test host.
            jdbc.execute("set session innodb_lock_wait_timeout = " + (current == null ? 1 : 10));
            try {
                if (current != null && current.firstAttempt.compareAndSet(true, false)) {
                    jdbc.queryForObject("select id from coupon_campaigns where id = ? for update", Long.class,
                            current.secondCampaign);
                    current.workerHasSecondLock.countDown();
                    if (!current.externalWaiting.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("외부 트랜잭션을 시작하지 못했습니다.");
                    }
                }
                attemptNanos.add(System.nanoTime());
                return delegate.lockById(campaignId);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } catch (RuntimeException failure) {
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SQLException sql) {
                        errorCodes.add(sql.getErrorCode());
                        break;
                    }
                }
                throw failure;
            } finally {
                jdbc.execute("set session innodb_lock_wait_timeout = " + original);
            }
        }

        @Override
        public void increaseIssuedCount(long campaignId) {
            delegate.increaseIssuedCount(campaignId);
        }
    }

    static class Deadlock {
        final long secondCampaign;
        final AtomicBoolean firstAttempt = new AtomicBoolean(true);
        final CountDownLatch workerHasSecondLock = new CountDownLatch(1);
        final CountDownLatch externalWaiting = new CountDownLatch(1);

        Deadlock(long secondCampaign) {
            this.secondCampaign = secondCampaign;
        }
    }
}
