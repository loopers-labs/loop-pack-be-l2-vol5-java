package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponAdmissionService;
import com.loopers.application.coupon.CouponIssuanceService;
import com.loopers.config.redis.RedisConfig;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.NameMatchMethodPointcutAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@DirtiesContext
@ActiveProfiles({"test", "coupon-admission-http-test"})
@SpringBootTest(classes = CouponHttpIntegrationFixture.IsolatedApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false", "management.health.redis.enabled=false",
        "commerce.coupon.worker.enabled=true", "commerce.coupon.worker.campaign-ids=1099,2099,3099",
        "commerce.coupon.worker.capacity=3", "commerce.coupon.worker.poll-delay=50ms"
    })
@Timeout(60)
class CouponIssuanceWorkerHttpIntegrationTest extends CouponHttpIntegrationFixture {
    private static final long SECOND_CAMPAIGN = 2099L;
    private static final long RETRY_CAMPAIGN = 3099L;
    private static final String TOKEN_SET = "commerce:coupon-issue:v1:{global}:tokens";
    @Autowired private CouponHttpDatabaseProbe applicationDatabase;
    @Autowired private CouponAdmissionService admission;
    @Autowired private CouponIssuanceService issuance;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> master;

    @Test
    void fullWorkersKeepFourthReservationUntilItsTurnAndIssueExactlyOneCouponWithoutReposting() throws Exception {
        var observer = new JdbcTemplate(ownedDataSource());
        observer.update("UPDATE coupon_campaign SET total_quota=4 WHERE id=?", SCHEDULED_CAMPAIGN);
        insertCampaign(observer, SECOND_CAMPAIGN, 1);
        admission.open(SECOND_CAMPAIGN);
        admission.open(SCHEDULED_CAMPAIGN);

        var gates = new IssuanceGates();
        var advisor = new NameMatchMethodPointcutAdvisor(gates.intercept());
        advisor.setMappedName("issue");
        var transactionalIssuance = (Advised) issuance;
        transactionalIssuance.addAdvisor(0, advisor);
        try {
            requestOnce(SCHEDULED_CAMPAIGN, 1);
            requestOnce(SECOND_CAMPAIGN, 2);
            requestOnce(SCHEDULED_CAMPAIGN, 3);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(gates.started).containsExactlyInAnyOrder(1L, 2L, 3L));
            Set<String> firstTokens = master.opsForSet().members(TOKEN_SET);
            assertThat(firstTokens).hasSize(3);
            assertThat(master.getExpire(TOKEN_SET)).isEqualTo(-1);

            long fourth = requestOnce(SCHEDULED_CAMPAIGN, 4);
            long fifth = requestOnce(SCHEDULED_CAMPAIGN, 5);
            assertThat(fourth).isLessThan(fifth);
            assertCounts(observer, 5, 0);
            assertThat(status(observer, fourth)).isEqualTo("WAITING");
            assertThat(gates.started).containsExactlyInAnyOrder(1L, 2L, 3L);

            var commit = applicationDatabase.blockNextIssuanceCommit();
            gates.release(1);
            try {
                assertThat(commit.entered.await(8, TimeUnit.SECONDS)).as("issued coupon SQL ran, commit pending").isTrue();
                assertCounts(observer, 5, 0);
                assertThat(master.opsForSet().members(TOKEN_SET)).isEqualTo(firstTokens);
                assertThat(gates.started).containsExactlyInAnyOrder(1L, 2L, 3L);
            } finally {
                commit.release.countDown();
            }
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(gates.started).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
                assertCounts(observer, 4, 1);
            });
            Set<String> nextTokens = master.opsForSet().members(TOKEN_SET);
            assertThat(nextTokens).hasSize(3);
            assertThat(nextTokens.stream().filter(firstTokens::contains).count()).isEqualTo(2);
            assertThat(status(observer, fifth)).isEqualTo("WAITING");

            gates.release(4);
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(gates.started).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
                assertThat(status(observer, fourth)).isEqualTo("ISSUED");
                assertCounts(observer, 3, 2);
            });
            List<Long> fourthCoupon = couponIds(observer, fourth);
            assertThat(fourthCoupon).hasSize(1);

            gates.releaseAll();
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertCounts(observer, 0, 5);
                assertThat(master.opsForSet().size(TOKEN_SET)).isZero();
            });
            assertThat(couponIds(observer, fourth)).isEqualTo(fourthCoupon);
            assertThat(gates.startedInsideTransaction).isEmpty();
            assertThat(gates.started).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        } finally {
            gates.releaseAll();
            transactionalIssuance.removeAdvisor(advisor);
        }
    }

    @Test
    void rolledBackIssuanceReleasesItsTokenAndRetriesTheSameReservationWithoutAnotherPost() throws Exception {
        var observer = new JdbcTemplate(ownedDataSource());
        insertCampaign(observer, RETRY_CAMPAIGN, 1);
        admission.open(RETRY_CAMPAIGN);
        var committedWrites = applicationDatabase.blockNextIssuanceCommit();
        var retryEntered = new CountDownLatch(1);
        var allowRetry = new CountDownLatch(1);
        var calls = new AtomicInteger();
        MethodInterceptor pauseSecondAttempt = invocation -> {
            if (calls.incrementAndGet() == 2) {
                retryEntered.countDown();
                if (!allowRetry.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release the issuance retry");
                }
            }
            return invocation.proceed();
        };
        var advisor = new NameMatchMethodPointcutAdvisor(pauseSecondAttempt);
        advisor.setMappedName("issue");
        var proxy = (Advised) issuance;
        proxy.addAdvisor(0, advisor);
        try {
            long reservation = requestOnce(RETRY_CAMPAIGN, 11);
            assertThat(committedWrites.entered.await(8, TimeUnit.SECONDS)).isTrue();
            Set<String> firstToken = master.opsForSet().members(TOKEN_SET);
            assertThat(firstToken).hasSize(1);
            committedWrites.failAndRelease();
            assertThat(retryEntered.await(8, TimeUnit.SECONDS)).as("automatic retry after physical rollback").isTrue();
            assertThat(status(observer, reservation)).isEqualTo("WAITING");
            assertThat(couponIds(observer, reservation)).isEmpty();
            assertThat(master.opsForSet().members(TOKEN_SET)).hasSize(1).doesNotContainAnyElementsOf(firstToken);
            allowRetry.countDown();
            await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
                assertThat(status(observer, reservation)).isEqualTo("ISSUED");
                assertThat(couponIds(observer, reservation)).hasSize(1);
                assertThat(master.opsForSet().size(TOKEN_SET)).isZero();
            });
            assertThat(calls).hasValue(2);
        } finally {
            committedWrites.release.countDown();
            allowRetry.countDown();
            proxy.removeAdvisor(advisor);
        }
    }

    private long requestOnce(long campaign, long userId) {
        var result = apply(campaign, userId);
        assertThat(result.getStatusCode().value()).isEqualTo(202);
        assertThat(result.getBody().at("/data/status").asText()).isEqualTo("WAITING");
        assertThat(result.getBody().at("/data").has("couponId")).isFalse();
        return result.getBody().at("/data/reservationId").longValue();
    }

    private void assertCounts(JdbcTemplate observer, int waiting, int issued) {
        assertThat(observer.queryForObject("SELECT SUM(waiting_count) FROM coupon_campaign WHERE id IN (?,?)", Integer.class,
            SCHEDULED_CAMPAIGN, SECOND_CAMPAIGN)).isEqualTo(waiting);
        assertThat(observer.queryForObject("SELECT SUM(issued_count) FROM coupon_campaign WHERE id IN (?,?)", Integer.class,
            SCHEDULED_CAMPAIGN, SECOND_CAMPAIGN)).isEqualTo(issued);
        assertThat(observer.queryForObject("""
            SELECT COUNT(*) FROM owned_coupon c JOIN coupon_reservation r ON c.reservation_id=r.id WHERE r.campaign_id IN (?,?)
            """, Integer.class, SCHEDULED_CAMPAIGN, SECOND_CAMPAIGN)).isEqualTo(issued);
    }

    private String status(JdbcTemplate observer, long reservation) {
        return observer.queryForObject("SELECT status FROM coupon_reservation WHERE id=?", String.class, reservation);
    }

    private List<Long> couponIds(JdbcTemplate observer, long reservation) {
        return observer.queryForList("SELECT id FROM owned_coupon WHERE reservation_id=?", Long.class, reservation);
    }

    private static class IssuanceGates {
        private final Map<Long, CountDownLatch> permits = LongStream.rangeClosed(1, 5).boxed()
            .collect(Collectors.toMap(id -> id, id -> new CountDownLatch(1)));
        private final List<Long> started = new CopyOnWriteArrayList<>();
        private final Set<Long> startedInsideTransaction = ConcurrentHashMap.newKeySet();

        // The outermost advisor runs after the worker acquired its token and before issue() begins its DB transaction.
        MethodInterceptor intercept() {
            return invocation -> {
                long user = (Long) invocation.getArguments()[1];
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    startedInsideTransaction.add(user);
                }
                started.add(user);
                if (!permits.get(user).await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release issue gate for user " + user);
                }
                return invocation.proceed();
            };
        }

        void release(long user) {
            permits.get(user).countDown();
        }

        void releaseAll() {
            permits.values().forEach(CountDownLatch::countDown);
        }
    }
}
