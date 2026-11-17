package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueSemaphoreUnavailableException;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Timeout(30)
class RedisCouponIssueSemaphoreIntegrationTest {
    private static final String STATE = "commerce:coupon-issue:v1:{global}:state";
    private static final String TOKENS = "commerce:coupon-issue:v1:{global}:tokens";
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private RedisCouponIssueSemaphore slots;

    @BeforeEach
    void connectToThisTestsMaster() {
        RedisConfig config = new RedisConfig(new RedisProperties(0,
            new RedisNodeInfo(REDIS.getHost(), REDIS.getFirstMappedPort()), List.of()));
        factory = config.masterRedisConnectionFactory(Duration.ofSeconds(2));
        factory.afterPropertiesSet();
        redis = config.masterRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.delete(List.of(STATE, TOKENS));
        slots = new RedisCouponIssueSemaphore(redis, 3);
        assertThat(factory.getClientConfiguration().getReadFrom()).contains(ReadFrom.MASTER);
        assertThat(factory.getClientConfiguration().getCommandTimeout()).isGreaterThan(Duration.ZERO);
    }

    @AfterEach
    void disconnect() {
        factory.destroy();
    }

    @Test
    void onlyThreeExecutionsEnterAndRepeatingOrReturningOneTokenCannotReleaseAnother() {
        var one = UUID.randomUUID();
        var two = UUID.randomUUID();
        var three = UUID.randomUUID();
        var four = UUID.randomUUID();
        assertThatThrownBy(() -> slots.tryAcquire(one)).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        slots.open();
        var peer = new RedisCouponIssueSemaphore(redis, 3);
        peer.open();
        assertThat(slots.tryAcquire(one)).isTrue();
        assertThat(peer.tryAcquire(two)).isTrue();
        assertThat(slots.tryAcquire(three)).isTrue();
        assertThat(slots.tryAcquire(one)).isTrue();
        assertThat(peer.tryAcquire(four)).isFalse();
        assertThat(redis.getExpire(TOKENS)).isEqualTo(-1L);
        assertThat(redis.opsForSet().members(TOKENS))
            .containsExactlyInAnyOrder(one.toString(), two.toString(), three.toString());

        slots.release(two);
        assertThat(redis.opsForSet().size(TOKENS)).isEqualTo(3L);
        slots.release(one);
        slots.release(one);
        assertThat(peer.tryAcquire(four)).isTrue();
        assertThat(redis.opsForSet().members(TOKENS))
            .containsExactlyInAnyOrder(two.toString(), three.toString(), four.toString());
        slots.release(three);
        peer.release(two);
        peer.release(four);
        assertThat(redis.opsForSet().size(TOKENS)).isZero();
    }

    @Test
    void twoIndependentWorkersContendingForOneRemainingSlotGetOneGrant() throws Exception {
        slots.open();
        var peer = new RedisCouponIssueSemaphore(redis, 3);
        peer.open();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var left = UUID.randomUUID();
        var right = UUID.randomUUID();
        assertThat(slots.tryAcquire(first)).isTrue();
        assertThat(slots.tryAcquire(second)).isTrue();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return slots.tryAcquire(left);
            });
            var b = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return peer.tryAcquire(right);
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(true, false);
            assertThat(redis.opsForSet().size(TOKENS)).isEqualTo(3L);
        } finally {
            start.countDown();
            slots.release(first);
            slots.release(second);
            slots.release(left);
            peer.release(right);
        }
        assertThat(redis.opsForSet().size(TOKENS)).isZero();
    }

    @Test
    void lossOfTheActiveSetStopsNewGrantsAndReopenIsRefusedDuringAnActiveExecution() {
        var running = UUID.randomUUID();
        slots.open();
        assertThat(slots.tryAcquire(running)).isTrue();
        redis.delete(TOKENS);
        assertThatThrownBy(() -> slots.tryAcquire(UUID.randomUUID())).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThat(slots.isOpen()).isFalse();
        assertThatThrownBy(slots::open).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThatThrownBy(() -> slots.release(running)).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThatThrownBy(slots::open).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
    }

    @Test
    void failedReturnPreservesTokenUntilExplicitReconciliationOfTheFinishedExecution() {
        var token = UUID.randomUUID();
        slots.open();
        assertThat(slots.tryAcquire(token)).isTrue();
        String backup = STATE + ":test-backup";
        redis.rename(STATE, backup);
        try {
            assertThatThrownBy(() -> slots.release(token)).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
            assertThat(slots.isOpen()).isFalse();
            assertThat(redis.opsForSet().members(TOKENS)).containsExactly(token.toString());
        } finally {
            redis.rename(backup, STATE);
        }
        assertThatThrownBy(() -> slots.tryAcquire(UUID.randomUUID())).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        var foreign = new RedisCouponIssueSemaphore(redis, 3);
        assertThatThrownBy(foreign::open).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThat(redis.opsForSet().members(TOKENS)).containsExactly(token.toString());
        slots.open();
        assertThat(redis.opsForSet().size(TOKENS)).isZero();
        assertThat(slots.isOpen()).isTrue();
    }

    @Test
    void anotherProcessCannotClaimOrCleanTheSameTokenAsItsOwn() {
        var token = UUID.randomUUID();
        var peer = new RedisCouponIssueSemaphore(redis, 3);
        slots.open();
        peer.open();
        assertThat(slots.tryAcquire(token)).isTrue();
        assertThatThrownBy(() -> peer.tryAcquire(token)).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        peer.release(token);
        assertThatThrownBy(peer::open).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThat(redis.opsForSet().members(TOKENS)).containsExactly(token.toString());
        slots.release(token);
        peer.open();
        assertThat(peer.isOpen()).isTrue();
    }

    @Test
    void operatorCanRetireOnlyTheMatchedTokenFromTheExpectedGeneration() {
        var stoppedToken = UUID.randomUUID();
        var otherToken = UUID.randomUUID();
        var otherWorker = new RedisCouponIssueSemaphore(redis, 3);
        slots.open();
        otherWorker.open();
        assertThat(slots.tryAcquire(stoppedToken)).isTrue();
        assertThat(otherWorker.tryAcquire(otherToken)).isTrue();

        var state = redis.<String, String>opsForHash();
        var before = state.entries(STATE);
        String epoch = before.get("epoch");
        String stoppedOwner = before.get("owner:" + stoppedToken);
        String otherOwner = before.get("owner:" + otherToken);
        assertThat(stoppedOwner).isNotBlank().isNotEqualTo(otherOwner);
        for (List<String> args : List.of(
            List.of("recover-one", UUID.randomUUID().toString(), "3", stoppedOwner, stoppedToken.toString()),
            List.of("recover-one", epoch, "4", stoppedOwner, stoppedToken.toString()),
            List.of("recover-one", epoch, "3", otherOwner, stoppedToken.toString()),
            List.of("recover-one", epoch, "3", stoppedOwner, otherToken.toString()),
            List.of("recover-one", epoch, "3", stoppedOwner, UUID.randomUUID().toString()),
            List.of("recover-one", epoch, "3", stoppedOwner, stoppedToken.toString(), otherToken.toString()))) {
            assertThat(operatorCommand(args)).isNull();
            assertThat(state.entries(STATE)).isEqualTo(before);
            assertThat(redis.opsForSet().members(TOKENS)).containsExactlyInAnyOrder(stoppedToken.toString(), otherToken.toString());
        }

        var cleanup = List.of("recover-one", epoch, "3", stoppedOwner, stoppedToken.toString());
        assertThat(operatorCommand(cleanup)).isEqualTo(1L);
        assertThat(operatorCommand(cleanup)).isNull();
        assertThat(redis.opsForSet().members(TOKENS)).containsExactly(otherToken.toString());
        assertThat(state.entries(STATE)).hasSize(4).containsEntry("epoch", epoch).containsEntry("capacity", "3")
            .containsEntry("held", "1").containsEntry("owner:" + otherToken, otherOwner);
        var restartedWorker = new RedisCouponIssueSemaphore(redis, 3);
        assertThatThrownBy(restartedWorker::open).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThat(restartedWorker.isOpen()).isFalse();
        otherWorker.release(otherToken);
        restartedWorker.open();
        assertThat(restartedWorker.isOpen()).isTrue();
    }

    private Long operatorCommand(List<String> args) {
        var script = RedisScript.of(new ClassPathResource("redis/coupon-issue-semaphore.lua"), Long.class);
        return redis.execute(script, List.of(STATE, TOKENS), args.toArray());
    }

    @Test
    void losingTheCollisionReplyNeverLetsTheUncertainCallerCleanAnotherWorkersActiveToken() {
        var peer = new RedisCouponIssueSemaphore(dropNextAcquireReply(), 3);
        var token = UUID.randomUUID();
        slots.open();
        peer.open();
        assertThat(slots.tryAcquire(token)).isTrue();
        assertThatThrownBy(() -> peer.tryAcquire(token)).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThatThrownBy(peer::open).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThatThrownBy(() -> peer.release(token)).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThat(redis.opsForSet().members(TOKENS)).containsExactly(token.toString());
        slots.release(token);
        peer.open();
        assertThat(peer.isOpen()).isTrue();
    }

    private RedisTemplate<String, String> dropNextAcquireReply() {
        var loseNextAcquireReply = new AtomicBoolean(true);
        return new RedisTemplate<String, String>() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                T actualReply = redis.execute(script, keys, args);
                if ("acquire".equals(args[0]) && loseNextAcquireReply.getAndSet(false)) {
                    throw new QueryTimeoutException("Test dropped the master's reply after executing Lua");
                }
                return actualReply;
            }
        };
    }

    @Test
    void lostAcquireAcknowledgmentRetainsThePossibleTokenUntilExplicitReopen() {
        var worker = new RedisCouponIssueSemaphore(dropNextAcquireReply(), 3);
        var token = UUID.randomUUID();
        worker.open();
        assertThatThrownBy(() -> worker.tryAcquire(token)).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThat(worker.isOpen()).isFalse();
        assertThat(redis.opsForSet().members(TOKENS)).containsExactly(token.toString());
        assertThatThrownBy(() -> worker.tryAcquire(UUID.randomUUID())).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        worker.open();
        assertThat(redis.opsForSet().size(TOKENS)).isZero();
        assertThat(worker.isOpen()).isTrue();
    }

    @Test
    void malformedKeyAndDifferentConfiguredCapacityCannotGrant() {
        slots.open();
        var misconfigured = new RedisCouponIssueSemaphore(redis, 4);
        assertThatThrownBy(misconfigured::open).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        redis.opsForValue().set(TOKENS, "corrupted");
        assertThatThrownBy(() -> slots.tryAcquire(UUID.randomUUID())).isInstanceOf(CouponIssueSemaphoreUnavailableException.class);
        assertThat(redis.opsForValue().get(TOKENS)).isEqualTo("corrupted");
        assertThat(slots.isOpen()).isFalse();
        assertThatThrownBy(() -> new RedisCouponIssueSemaphore(redis, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
