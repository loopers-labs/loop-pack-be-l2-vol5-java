package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponSelectionStore.Selection;
import com.loopers.application.coupon.CouponSelectionUnavailableException;
import com.loopers.config.redis.RedisConfig;
import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import io.lettuce.core.ReadFrom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Timeout(40)
class RedisCouponSelectionStoreIntegrationTest {
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private RedisCouponSelectionStore store;

    @BeforeEach
    void connectToOwnedRedisMaster() {
        RedisConfig config = new RedisConfig(new RedisProperties(0,
            new RedisNodeInfo(REDIS.getHost(), REDIS.getMappedPort(6379)), List.of()));
        factory = config.masterRedisConnectionFactory(Duration.ofSeconds(2));
        factory.afterPropertiesSet();
        redis = config.masterRedisTemplate(factory);
        redis.afterPropertiesSet();
        store = new RedisCouponSelectionStore(redis);
        assertThat(factory.getClientConfiguration().getReadFrom()).contains(ReadFrom.MASTER);
        assertThat(factory.getClientConfiguration().getCommandTimeout()).isGreaterThan(Duration.ZERO);
    }

    @AfterEach
    void disconnect() {
        factory.destroy();
    }

    @Test
    void checksExistingMemberBeforeFullAndOnlyCommittedUsersLeaveTheStream() {
        long campaign = 101;
        assertThatThrownBy(() -> store.select(campaign, 1)).isInstanceOf(CouponSelectionUnavailableException.class);
        store.open(campaign, 3, List.of(5L));
        assertThat(store.select(campaign, 5)).isEqualTo(Selection.EXISTING);
        assertThat(store.select(campaign, 8)).isEqualTo(Selection.NEW);
        assertThat(store.select(campaign, Long.MAX_VALUE)).isEqualTo(Selection.NEW);
        assertThat(store.select(campaign, 9)).isEqualTo(Selection.FULL);
        assertThat(store.select(campaign, Long.MAX_VALUE)).isEqualTo(Selection.EXISTING);
        assertThat(store.pending(campaign, 1)).containsExactly(8L);
        assertThat(store.pending(campaign, 3)).containsExactly(8L, Long.MAX_VALUE);
        store.complete(campaign, 8);
        store.complete(campaign, 8);
        assertThat(store.pending(campaign, 3)).containsExactly(Long.MAX_VALUE);
        assertThat(store.select(campaign, 8)).isEqualTo(Selection.EXISTING);
        assertThat(store.select(campaign, 9)).isEqualTo(Selection.FULL);
    }

    @Test
    void restartKeepsPendingSelectionsAndReconcilesAnAlreadyCommittedUser() {
        long campaign = 102;
        store.open(campaign, 2, List.of());
        assertThat(store.select(campaign, 1)).isEqualTo(Selection.NEW);
        assertThat(store.select(campaign, 2)).isEqualTo(Selection.NEW);
        store.close(campaign);
        RedisCouponSelectionStore restarted = new RedisCouponSelectionStore(redis);
        assertThatThrownBy(() -> restarted.select(campaign, 12)).isInstanceOf(CouponSelectionUnavailableException.class);
        restarted.open(campaign, 2, List.of(2L));
        assertThat(restarted.pending(campaign, 5)).containsExactly(1L);
        assertThat(restarted.select(campaign, 2)).isEqualTo(Selection.EXISTING);
        assertThat(restarted.select(campaign, 3)).isEqualTo(Selection.FULL);
    }

    @Test
    void inactiveCampaignPreservesSelectedUsersWithoutAddingNewWork() {
        long campaign = 106;
        store.open(campaign, 2, List.of(1L), false);
        assertThat(store.select(campaign, 1)).isEqualTo(Selection.EXISTING);
        assertThat(store.select(campaign, 2)).isEqualTo(Selection.INACTIVE);
        assertThat(store.pending(campaign, 5)).isEmpty();
    }

    @Test
    void reopeningRejectsCommittedMembersMissingFromTheDatabaseSnapshot() {
        long campaign = 107;
        store.open(campaign, 2, List.of(1L));
        assertThat(store.select(campaign, 2)).isEqualTo(Selection.NEW);
        assertThatThrownBy(() -> store.open(campaign, 2, List.of()))
            .isInstanceOf(CouponSelectionUnavailableException.class);
        assertThat(store.isOpen(campaign)).isFalse();
        assertThatThrownBy(() -> store.select(campaign, 3)).isInstanceOf(CouponSelectionUnavailableException.class);
        store.open(campaign, 2, List.of(1L));
        assertThat(store.isOpen(campaign)).isTrue();
        assertThat(store.pending(campaign, 2)).containsExactly(2L);
    }

    @Test
    void losingOneCommittedHashFieldClosesAdmissionWithoutSellingItsSlotAgain() {
        long campaign = 108;
        store.open(campaign, 1, List.of(1L));
        redis.opsForHash().delete("commerce:coupon-selection:v1:{108}:state", "1");
        assertThatThrownBy(() -> store.select(campaign, 2)).isInstanceOf(CouponSelectionUnavailableException.class);
    }

    @Test
    void concurrentSelectionsAtOneSlotCreateOneWorkRecordAcrossTwoInstances() throws Exception {
        long campaign = 103;
        var second = new RedisCouponSelectionStore(redis);
        store.open(campaign, 1, List.of());
        second.open(campaign, 1, List.of());
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return store.select(campaign, 1);
            });
            var secondResult = executor.submit(() -> {
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return second.select(campaign, 2);
            });
            start.countDown();
            assertThat(List.of(firstResult.get(5, TimeUnit.SECONDS), secondResult.get(5, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(Selection.NEW, Selection.FULL);
        }
        assertThat(store.pending(campaign, 3)).hasSize(1);
    }

    @Test
    void lossOfStateClosesLocalAdmissionUntilExplicitReconciliation() {
        long campaign = 104;
        store.open(campaign, 1, List.of(1L));
        redis.delete("commerce:coupon-selection:v1:{104}:state");
        assertThatThrownBy(() -> store.select(campaign, 2)).isInstanceOf(CouponSelectionUnavailableException.class);
        var another = new RedisCouponSelectionStore(redis);
        another.open(campaign, 1, List.of(1L));
        assertThatThrownBy(() -> store.select(campaign, 2)).isInstanceOf(CouponSelectionUnavailableException.class);
        store.open(campaign, 1, List.of(1L));
        assertThat(store.select(campaign, 2)).isEqualTo(Selection.FULL);
    }

    @Test
    void malformedWorkKeyDoesNotCreateANewMemberOrReturnFull() {
        long campaign = 105;
        store.open(campaign, 1, List.of());
        redis.opsForValue().set("commerce:coupon-selection:v1:{105}:work", "wrong type");
        assertThatThrownBy(() -> store.select(campaign, 1)).isInstanceOf(CouponSelectionUnavailableException.class);
        assertThat(redis.opsForHash().hasKey("commerce:coupon-selection:v1:{105}:state", "1")).isFalse();
        redis.delete("commerce:coupon-selection:v1:{105}:work");
        assertThatThrownBy(() -> store.select(campaign, 1)).isInstanceOf(CouponSelectionUnavailableException.class);
        store.open(campaign, 1, List.of());
        assertThat(store.select(campaign, 1)).isEqualTo(Selection.NEW);
    }
}
