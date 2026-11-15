package com.loopers.infrastructure.coupon;

import com.loopers.config.redis.RedisConfig;
import com.loopers.domain.coupon.CouponIssueGate;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.testcontainers.RedisTestContainersConfig;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis 수량·중복 판단만 검증한다. 처리 슬롯이나 발급 순서를 검증하는 테스트가 아니다. */
@SpringBootTest
@Import({MySqlTestContainersConfig.class, RedisTestContainersConfig.class})
class RedisCouponIssueGateTest {
    private static final long CAMPAIGN = 8_888_888L;
    private static final String STOCK = "coupon-issue:{" + CAMPAIGN + "}:stock";
    private static final String USERS = "coupon-issue:{" + CAMPAIGN + "}:users";
    private final Instant now = Instant.now();
    @Autowired private CouponIssueGate gate;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;

    @AfterEach
    void cleanUp() { redis.delete(List.of(STOCK, USERS)); }

    @Test
    void unpreparedJudgmentAndCompensationDoNotCreateState() {
        assertThat(gate.enter(CAMPAIGN, 1L, now).decision()).isEqualTo(CouponIssueGate.Decision.NOT_PREPARED);
        gate.cancel(CAMPAIGN, 1L, "R:unknown");
        assertThat(gate.confirm(CAMPAIGN, 1L, "R:unknown", false)).isFalse();
        assertThat(redis.hasKey(STOCK)).isFalse();
        assertThat(redis.hasKey(USERS)).isFalse();
    }

    @Test
    void reservesOnlyRemainingQuantityAndRecognizesExistingUserFirst() {
        prepare(1);
        var entry = gate.enter(CAMPAIGN, 1L, now);
        assertThat(entry.decision()).isEqualTo(CouponIssueGate.Decision.PASSED);
        assertThat(gate.enter(CAMPAIGN, 2L, now).decision()).isEqualTo(CouponIssueGate.Decision.SOLD_OUT);
        assertThat(gate.confirm(CAMPAIGN, 1L, entry.reservation(), true)).isTrue();
        assertThat(gate.enter(CAMPAIGN, 1L, now.plusSeconds(7200)).decision()).isEqualTo(CouponIssueGate.Decision.ALREADY_ACCEPTED);
        assertThat(remaining()).isEqualTo("0");
    }

    @Test
    void elapsedTimeDoesNotTransferAnotherExecutionReservation() {
        prepare(2);
        var reserved = gate.enter(CAMPAIGN, 1L, now);
        assertThat(gate.enter(CAMPAIGN, 1L, now.plusSeconds(30)).decision()).isEqualTo(CouponIssueGate.Decision.ALREADY_RESERVED);
        assertThat(redis.opsForHash().get(USERS, "1")).isEqualTo(reserved.reservation());
        assertThat(remaining()).isEqualTo("1");
    }

    @Test
    void compensationChecksOwnershipAndReturnsQuantityOnlyOnce() {
        prepare(2);
        var reserved = gate.enter(CAMPAIGN, 1L, now);
        gate.cancel(CAMPAIGN, 1L, "R:other-execution");
        assertThat(remaining()).isEqualTo("1");
        gate.cancel(CAMPAIGN, 1L, reserved.reservation());
        gate.cancel(CAMPAIGN, 1L, reserved.reservation());
        assertThat(remaining()).isEqualTo("2");
        assertThat(redis.opsForHash().hasKey(USERS, "1")).isFalse();
        var replacement = gate.enter(CAMPAIGN, 1L, now);
        assertThat(replacement.reservation()).isNotEqualTo(reserved.reservation());
        gate.cancel(CAMPAIGN, 1L, reserved.reservation());
        assertThat(remaining()).isEqualTo("1");
    }

    @Test
    void existingDatabaseResultRefundsReservationOnceAndKeepsUser() {
        prepare(2);
        var reserved = gate.enter(CAMPAIGN, 1L, now);
        assertThat(gate.confirm(CAMPAIGN, 1L, "wrong", false)).isFalse();
        assertThat(gate.confirm(CAMPAIGN, 1L, reserved.reservation(), false)).isTrue();
        assertThat(gate.confirm(CAMPAIGN, 1L, reserved.reservation(), false)).isFalse();
        gate.cancel(CAMPAIGN, 1L, reserved.reservation());
        assertThat(remaining()).isEqualTo("2");
        assertThat(redis.opsForHash().get(USERS, "1")).isEqualTo("A");
    }

    @Test
    void firstReservationSharesCampaignExpiryAndPreparationIncludesIssuedUsers() {
        gate.prepare(CAMPAIGN, 2, LongStream.rangeClosed(1, 2500).boxed().toList(), now.plusSeconds(3600), now.plusSeconds(7200));
        assertThat(redis.opsForHash().size(USERS)).isEqualTo(2500);
        assertThat(gate.enter(CAMPAIGN, 1L, now).decision()).isEqualTo(CouponIssueGate.Decision.ALREADY_ACCEPTED);
        redis.delete(USERS);
        gate.enter(CAMPAIGN, 2501L, now);
        assertThat(redis.getExpire(USERS, TimeUnit.MILLISECONDS)).isPositive();
        assertThat(Math.abs(redis.getExpire(STOCK, TimeUnit.MILLISECONDS) - redis.getExpire(USERS, TimeUnit.MILLISECONDS))).isLessThan(1000);
    }

    @Test
    void endedCampaignRejectsBeforeQuantityChange() {
        prepare(2);
        assertThat(gate.enter(CAMPAIGN, 1L, now.plusSeconds(3600)).decision()).isEqualTo(CouponIssueGate.Decision.ENDED);
        assertThat(remaining()).isEqualTo("2");
    }

    @Test
    void concurrentTwentyUsersOnlyReserveFive() throws Exception {
        prepare(5);
        var executor = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CouponIssueGate.Entry>> tasks = new ArrayList<>();
            for (long user = 1; user <= 20; user++) {
                long id = user;
                tasks.add(executor.submit(() -> { start.await(); return gate.enter(CAMPAIGN, id, now); }));
            }
            start.countDown();
            long passed = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS).decision() == CouponIssueGate.Decision.PASSED) passed++;
            assertThat(passed).isEqualTo(5);
            assertThat(remaining()).isEqualTo("0");
            assertThat(redis.opsForHash().size(USERS)).isEqualTo(5);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void prepare(int count) { gate.prepare(CAMPAIGN, count, List.of(), now.plusSeconds(3600), now.plusSeconds(7200)); }
    private Object remaining() { return redis.opsForHash().get(STOCK, "remaining"); }
}
