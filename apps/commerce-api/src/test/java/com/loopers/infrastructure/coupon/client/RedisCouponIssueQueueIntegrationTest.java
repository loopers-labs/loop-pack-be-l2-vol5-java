package com.loopers.infrastructure.coupon.client;

import com.loopers.config.CouponRedisConfig;
import com.loopers.domain.coupon.enums.CouponErrorCode;
import com.loopers.domain.coupon.enums.IssueState;
import com.loopers.domain.coupon.vo.IssueAcceptance;
import com.loopers.domain.coupon.vo.IssueSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest
class RedisCouponIssueQueueIntegrationTest {

    private static final long CAMPAIGN_ID = 42L;
    private static final int QUOTA = 2;
    private static final int RESERVE_LIMIT = 3;
    private static final long NOW = 1_700_000_000_000L;

    @Autowired
    private RedisCouponIssueQueue queue;

    @Autowired
    @Qualifier(CouponRedisConfig.MASTER_TEMPLATE)
    private StringRedisTemplate redis;

    @Autowired
    private RedisCleanUp redisCleanUp;

    private final CouponIssueKeys keys = CouponIssueKeys.of(CAMPAIGN_ID);

    @BeforeEach
    void open() {
        redis.opsForValue().set(keys.epoch(), "1");
    }

    @AfterEach
    void tearDown() {
        redisCleanUp.truncateAll();
    }

    private IssueAcceptance accept(long userId) {
        return queue.accept(CAMPAIGN_ID, userId, QUOTA, RESERVE_LIMIT, NOW);
    }

    @DisplayName("접수할 때, ")
    @Nested
    class Accept {
        @DisplayName("처음이면 순번을 받고 PENDING_CONFIRM 으로 남으며, 아직 대기열에는 없다. (D-133)")
        @Test
        void pendsWithSequence_andNotQueuedYet() {
            // act
            IssueAcceptance result = accept(7L);

            // assert
            assertAll(
                () -> assertThat(result).isEqualTo(new IssueAcceptance.Pending(1)),
                () -> assertThat(redis.opsForHash().get(keys.users(), "7")).isEqualTo("PENDING_CONFIRM:1"),
                () -> assertThat(redis.opsForZSet().score(keys.pending(), "7")).isEqualTo((double) NOW),
                () -> assertThat(redis.hasKey(keys.stream())).isFalse()
            );
        }

        @DisplayName("같은 사용자가 다시 접수하면, 새 순번 없이 기존 상태를 돌려준다. (D-117)")
        @Test
        void returnsExisting_withoutNewSequence() {
            // arrange
            accept(7L);

            // act
            IssueAcceptance again = accept(7L);

            // assert
            assertAll(
                () -> assertThat(again).isEqualTo(new IssueAcceptance.Existing(
                    new IssueSnapshot(IssueState.PENDING_CONFIRM, 1, null, null))),
                () -> assertThat(redis.opsForValue().get(keys.sequence())).isEqualTo("1")
            );
        }

        @DisplayName("예비 범위(한도 + 예비)를 넘으면, 순번을 쓰지 않고 기록 없이 품절이다. (D-121)")
        @Test
        void soldOutWithoutRecord_beyondReserveLimit() {
            // arrange
            accept(1L);
            accept(2L);
            accept(3L);

            // act
            IssueAcceptance fourth = accept(4L);

            // assert
            assertAll(
                () -> assertThat(fourth).isInstanceOf(IssueAcceptance.SoldOut.class),
                () -> assertThat(redis.opsForHash().hasKey(keys.users(), "4")).isFalse(),
                () -> assertThat(redis.opsForValue().get(keys.sequence())).isEqualTo("3")
            );
        }

        @DisplayName("품절 플래그가 서 있으면, 예비 범위 안이어도 품절이다. (D-125)")
        @Test
        void soldOut_whenFlagIsSet() {
            // arrange
            redis.opsForValue().set(keys.soldOut(), "1");

            // act & assert
            assertThat(accept(1L)).isInstanceOf(IssueAcceptance.SoldOut.class);
        }

        @DisplayName("표식 키가 없으면, 순번을 나눠 주지 않고 COUPON_ISSUE_UNAVAILABLE 이다. (D-139)")
        @Test
        void unavailable_whenEpochIsMissing() {
            // arrange
            redis.delete(keys.epoch());

            // act & assert
            assertAll(
                () -> assertThatThrownBy(() -> accept(1L))
                    .isInstanceOfSatisfying(CoreException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CouponErrorCode.COUPON_ISSUE_UNAVAILABLE)),
                () -> assertThat(redis.hasKey(keys.sequence())).isFalse()
            );
        }

        @DisplayName("서로 다른 50명이 동시에 접수하면, 예비 범위까지만 겹치지 않는 순번을 받는다. (D-115)")
        @Test
        void assignsDistinctSequences_underConcurrency() throws Exception {
            // arrange
            int users = 50;
            int limit = 30;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(16);
            List<Future<IssueAcceptance>> results = new ArrayList<>();

            // act
            try {
                for (int i = 1; i <= users; i++) {
                    long userId = i;
                    results.add(executor.submit(() -> {
                        start.await();
                        return queue.accept(CAMPAIGN_ID, userId, 20, limit, NOW);
                    }));
                }
                start.countDown();
            } finally {
                executor.shutdown();
                assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            }

            // assert
            Set<Long> sequences = new HashSet<>();
            int soldOut = 0;
            for (Future<IssueAcceptance> result : results) {
                if (result.get() instanceof IssueAcceptance.Pending pending) {
                    sequences.add(pending.sequence());
                } else {
                    soldOut++;
                }
            }
            int soldOutCount = soldOut;
            assertAll(
                () -> assertThat(sequences).hasSize(limit),
                () -> assertThat(sequences).allMatch(sequence -> sequence >= 1 && sequence <= limit),
                () -> assertThat(soldOutCount).isEqualTo(users - limit)
            );
        }
    }

    @DisplayName("확정할 때, ")
    @Nested
    class Confirm {
        @DisplayName("순번이 한도 안이면 WAITING 이 되고 슬롯 = 순번으로 대기열에 들어간다. (D-121, D-141)")
        @Test
        void queuesWaiting_withSequenceAsSlot() {
            // arrange
            accept(7L);

            // act
            IssueSnapshot snapshot = queue.confirm(CAMPAIGN_ID, 7L, QUOTA).orElseThrow();

            // assert
            var entries = redis.opsForStream().range(keys.stream(), org.springframework.data.domain.Range.unbounded());
            assertAll(
                () -> assertThat(snapshot.state()).isEqualTo(IssueState.WAITING),
                () -> assertThat(entries).hasSize(1),
                () -> assertThat(entries.get(0).getValue())
                    .containsEntry("userId", "7").containsEntry("slotNo", "1").containsEntry("epoch", "1"),
                () -> assertThat(redis.opsForZSet().score(keys.pending(), "7")).isNull()
            );
        }

        @DisplayName("순번이 한도 밖이면 RESERVE 가 되고 예비 목록에 순번 순으로 들어가며, 현재 예비 위치를 돌려준다. (D-121, D-149)")
        @Test
        void reserves_withCurrentPosition() {
            // arrange
            accept(1L);
            accept(2L);
            accept(3L);

            // act
            IssueSnapshot snapshot = queue.confirm(CAMPAIGN_ID, 3L, QUOTA).orElseThrow();

            // assert
            assertAll(
                () -> assertThat(snapshot).isEqualTo(new IssueSnapshot(IssueState.RESERVE, 3, 1, null)),
                () -> assertThat(redis.opsForZSet().score(keys.reserve(), "3")).isEqualTo(3.0),
                () -> assertThat(redis.hasKey(keys.stream())).isFalse()
            );
        }

        @DisplayName("두 번 확정해도, 대기열에는 한 번만 들어간다. (D-134 재전송)")
        @Test
        void confirmsOnce_whenRepeated() {
            // arrange
            accept(7L);
            queue.confirm(CAMPAIGN_ID, 7L, QUOTA);

            // act
            IssueSnapshot again = queue.confirm(CAMPAIGN_ID, 7L, QUOTA).orElseThrow();

            // assert
            assertAll(
                () -> assertThat(again.state()).isEqualTo(IssueState.WAITING),
                () -> assertThat(redis.opsForStream().size(keys.stream())).isEqualTo(1L)
            );
        }

        @DisplayName("접수 기록이 없으면, 비어 있다.")
        @Test
        void empty_whenNoRecord() {
            assertThat(queue.confirm(CAMPAIGN_ID, 7L, QUOTA)).isEmpty();
        }
    }

    @DisplayName("읽을 때, ")
    @Nested
    class Read {
        @DisplayName("품절 플래그가 서 있으면, 예비는 SOLD_OUT 으로 읽힌다. (D-125)")
        @Test
        void readsReserveAsSoldOut_whenFlagIsSet() {
            // arrange
            accept(1L);
            accept(2L);
            accept(3L);
            queue.confirm(CAMPAIGN_ID, 3L, QUOTA);
            redis.opsForValue().set(keys.soldOut(), "1");

            // act & assert
            assertThat(queue.read(CAMPAIGN_ID, 3L, QUOTA).orElseThrow().state()).isEqualTo(IssueState.SOLD_OUT);
        }

        @DisplayName("승격된 인원만큼 예비 위치가 줄어든다. (D-149)")
        @Test
        void reservePositionShrinks_byPromotedCount() {
            // arrange
            for (long userId = 1; userId <= 4; userId++) {
                queue.accept(CAMPAIGN_ID, userId, QUOTA, 4, NOW);
            }
            queue.confirm(CAMPAIGN_ID, 4L, QUOTA);
            int before = queue.read(CAMPAIGN_ID, 4L, QUOTA).orElseThrow().reservePosition();
            redis.opsForValue().set(keys.promoted(), "1");

            // act
            int after = queue.read(CAMPAIGN_ID, 4L, QUOTA).orElseThrow().reservePosition();

            // assert
            assertAll(
                () -> assertThat(before).isEqualTo(2),
                () -> assertThat(after).isEqualTo(1)
            );
        }

        @DisplayName("발급된 기록이면, couponId 를 함께 돌려준다.")
        @Test
        void readsIssuedWithCouponId() {
            // arrange
            redis.opsForHash().put(keys.users(), "7", "ISSUED:1:900");

            // act
            IssueSnapshot snapshot = queue.read(CAMPAIGN_ID, 7L, QUOTA).orElseThrow();

            // assert
            assertThat(snapshot).isEqualTo(new IssueSnapshot(IssueState.ISSUED, 1, null, 900L));
        }
    }
}
