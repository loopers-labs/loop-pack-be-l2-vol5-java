package com.loopers.infrastructure.coupon;

import com.loopers.config.coupon.CouponAdmissionRedisConfig;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponAdmissionGate.CloseReason;
import com.loopers.domain.coupon.CouponAdmissionGate.Closure;
import com.loopers.domain.coupon.CouponAdmissionQueue;
import com.loopers.domain.coupon.CouponAdmissionQueue.EnqueueOutcome;
import com.loopers.domain.coupon.CouponAdmissionQueue.EnqueueResult;
import com.loopers.domain.coupon.CouponPermitSemaphore;
import com.loopers.domain.coupon.CouponPermitSemaphore.ClaimOutcome;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8주차 설계 §4·§5·§7 의 Redis 어댑터를 Testcontainers Redis 로 검증한다.
 * test 프로파일: capacity 3, permit TTL 1초, admitted TTL 1초, 하트비트 200ms. 시간 의존 테스트는 TTL 보다 조금 긴 1.2초를 기다린다.
 */
@SpringBootTest
class RedisCouponAdmissionAdapterTest {

    private static final Long CAMPAIGN = 10L;
    private static final long ACCEPT_LIMIT = 100L;
    private static final long TTL_PASSED_MS = 1_200L;

    private final CouponAdmissionGate gate;
    private final CouponAdmissionQueue queue;
    private final CouponPermitSemaphore semaphore;
    private final RedisTemplate<String, String> redis;
    private final RedisCleanUp redisCleanUp;
    private final DatabaseCleanUp databaseCleanUp;

    private String gen;

    @Autowired
    RedisCouponAdmissionAdapterTest(
        CouponAdmissionGate gate,
        CouponAdmissionQueue queue,
        CouponPermitSemaphore semaphore,
        @Qualifier(CouponAdmissionRedisConfig.COUPON_ADMISSION_REDIS_TEMPLATE) RedisTemplate<String, String> redis,
        RedisCleanUp redisCleanUp,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.gate = gate;
        this.queue = queue;
        this.semaphore = semaphore;
        this.redis = redis;
        this.redisCleanUp = redisCleanUp;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void openGate() {
        gen = gate.open(CAMPAIGN);
    }

    @AfterEach
    void tearDown() {
        gate.close(CAMPAIGN);
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    private EnqueueResult enqueue(long userId) {
        return queue.enqueue(CAMPAIGN, userId, ACCEPT_LIMIT, gen);
    }

    /** 승격을 거치지 않고 admitted 에 직접 넣는다. 세마포어만 떼어 보기 위한 준비 단계. */
    private void admitDirectly(long... userIds) {
        for (long userId : userIds) {
            redis.opsForZSet().add(CouponAdmissionKeys.admitted(CAMPAIGN), String.valueOf(userId), System.currentTimeMillis());
        }
    }

    private Set<String> admittedMembers() {
        return redis.opsForZSet().range(CouponAdmissionKeys.admitted(CAMPAIGN), 0, -1);
    }

    private long permitCount() {
        Long size = redis.opsForZSet().size(CouponAdmissionKeys.permits(CAMPAIGN));
        return size == null ? 0 : size;
    }

    private Double permitScore(String token) {
        return redis.opsForZSet().score(CouponAdmissionKeys.permits(CAMPAIGN), token);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @DisplayName("gate (§7-3, INV-W8-10)")
    @Nested
    class Gate {
        @DisplayName("한 번도 열지 않은 캠페인은 닫혀 있고 세대값이 없다.")
        @Test
        void closedByDefault() {
            assertThat(gate.isOpen(99L)).isFalse();
            assertThat(gate.generation(99L)).isEmpty();
        }

        @DisplayName("open 은 새 세대값을 Redis gen 키에 쓰고 메모리에 기억한다. 다시 open 하면 세대값이 바뀐다.")
        @Test
        void openWritesGeneration() {
            assertThat(gate.isOpen(CAMPAIGN)).isTrue();
            assertThat(gate.generation(CAMPAIGN)).contains(gen);
            assertThat(redis.opsForValue().get(CouponAdmissionKeys.gen(CAMPAIGN))).isEqualTo(gen);

            String reopened = gate.open(CAMPAIGN);

            assertThat(reopened).isNotEqualTo(gen);
            assertThat(redis.opsForValue().get(CouponAdmissionKeys.gen(CAMPAIGN))).isEqualTo(reopened);
        }

        @DisplayName("forceClose 뒤에는 닫혀 있고 이유·시각이 남는다. Redis 가 멀쩡해도 open 전까지 다시 열리지 않는다.")
        @Test
        void forceCloseStaysClosed() {
            gate.forceClose(CAMPAIGN, CloseReason.REDIS_ERROR);

            assertThat(gate.isOpen(CAMPAIGN)).isFalse();
            assertThat(gate.generation(CAMPAIGN)).isEmpty();
            assertThat(gate.closure(CAMPAIGN)).hasValueSatisfying(closure -> {
                assertThat(closure.reason()).isEqualTo(CloseReason.REDIS_ERROR);
                assertThat(closure.closedAt()).isNotNull();
            });
            assertThat(redis.getConnectionFactory().getConnection().ping()).isEqualTo("PONG");
            assertThat(gate.isOpen(CAMPAIGN)).isFalse();
        }

        @DisplayName("관리자 close 는 이유가 ADMIN 이고, open 하면 closure 가 비워진다.")
        @Test
        void adminCloseRecordsReason() {
            gate.close(CAMPAIGN);

            assertThat(gate.closure(CAMPAIGN)).map(Closure::reason).contains(CloseReason.ADMIN);

            gate.open(CAMPAIGN);

            assertThat(gate.closure(CAMPAIGN)).isEmpty();
        }

        @DisplayName("INV-W8-10① 자동 재개가 꺼져 있으면(test 프로파일 기본) Redis 복구 후 세대가 일치해도 probe 는 열지 않고, 관리자 open 으로만 재개한다.")
        @Test
        void probeDoesNothing_whenAutoReopenDisabled() {
            gate.forceClose(CAMPAIGN, CloseReason.REDIS_ERROR);
            assertThat(redis.opsForValue().get(CouponAdmissionKeys.gen(CAMPAIGN))).as("Redis 의 세대는 그대로 보존됨").isEqualTo(gen);

            for (int i = 0; i < 5; i++) {
                assertThat(gate.probeOnce(CAMPAIGN)).isFalse();
            }

            assertThat(gate.isOpen(CAMPAIGN)).isFalse();
            assertThat(gate.closure(CAMPAIGN)).map(Closure::reason).contains(CloseReason.REDIS_ERROR);
            gate.open(CAMPAIGN);
            assertThat(gate.isOpen(CAMPAIGN)).isTrue();
        }

        @DisplayName("§7-3 forceClose 뒤 관리자 open 은 기존 키를 지우지 않는다. 기존 대기자는 순번대로 승격되고, 재접수는 같은 번호를 받는다.")
        @Test
        void reopenKeepsWaitingAndSeq() {
            enqueue(1L);
            enqueue(2L);
            enqueue(3L);
            gate.forceClose(CAMPAIGN, CloseReason.REDIS_ERROR);

            String reopened = gate.open(CAMPAIGN);

            assertThat(reopened).isNotEqualTo(gen);
            assertThat(queue.acceptedCount(CAMPAIGN)).isEqualTo(3L);
            assertThat(queue.enqueue(CAMPAIGN, 1L, ACCEPT_LIMIT, reopened)).isEqualTo(new EnqueueResult(EnqueueOutcome.ALREADY_ACCEPTED, 1L));
            assertThat(queue.enqueue(CAMPAIGN, 4L, ACCEPT_LIMIT, reopened)).isEqualTo(new EnqueueResult(EnqueueOutcome.ACCEPTED, 4L));
            assertThat(queue.promote(CAMPAIGN, reopened)).isEqualTo(3);
            assertThat(admittedMembers()).containsExactlyInAnyOrder("1", "2", "3");
            assertThat(queue.waitingRank(CAMPAIGN, 4L)).contains(0L);
        }

        @DisplayName("§7-3 자리 3개 점유 중 forceClose → 관리자 open 뒤에도 permits 가 남아 4번째 획득은 NO_PERMIT 이다.")
        @Test
        void reopenKeepsPermits() {
            admitDirectly(1L, 2L, 3L, 4L);
            semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen);
            semaphore.tryAcquire(CAMPAIGN, 2L, "t2", gen);
            semaphore.tryAcquire(CAMPAIGN, 3L, "t3", gen);
            gate.forceClose(CAMPAIGN, CloseReason.REDIS_ERROR);

            String reopened = gate.open(CAMPAIGN);

            assertThat(permitCount()).isEqualTo(3);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 4L, "t4", reopened)).isEqualTo(ClaimOutcome.NO_PERMIT);
            assertThat(semaphore.release(CAMPAIGN, "t1")).isTrue();
            assertThat(semaphore.tryAcquire(CAMPAIGN, 4L, "t4", reopened)).isEqualTo(ClaimOutcome.ACQUIRED);
        }
    }

    @DisplayName("접수 Lua (§5-1)")
    @Nested
    class Enqueue {
        @DisplayName("처음 접수한 사용자는 ACCEPTED 와 1 부터 증가하는 접수 번호를 받고, 대기열 순위는 접수 순서다.")
        @Test
        void acceptsInOrder() {
            EnqueueResult first = enqueue(1L);
            EnqueueResult second = enqueue(2L);

            assertThat(first).isEqualTo(new EnqueueResult(EnqueueOutcome.ACCEPTED, 1L));
            assertThat(second).isEqualTo(new EnqueueResult(EnqueueOutcome.ACCEPTED, 2L));
            assertThat(queue.waitingRank(CAMPAIGN, 1L)).contains(0L);
            assertThat(queue.waitingRank(CAMPAIGN, 2L)).contains(1L);
            assertThat(queue.waitingRank(CAMPAIGN, 3L)).isEmpty();
            assertThat(queue.acceptedCount(CAMPAIGN)).isEqualTo(2L);
        }

        @DisplayName("대기 중인 사용자가 다시 접수하면 ALREADY_ACCEPTED 와 첫 접수 번호를 받고 번호는 늘지 않는다.")
        @Test
        void returnsOriginalSeq_whenWaitingUserRetries() {
            enqueue(1L);
            enqueue(2L);

            EnqueueResult retry = enqueue(1L);

            assertThat(retry).isEqualTo(new EnqueueResult(EnqueueOutcome.ALREADY_ACCEPTED, 1L));
            assertThat(queue.acceptedCount(CAMPAIGN)).isEqualTo(2L);
        }

        @DisplayName("이미 승격된 사용자가 다시 접수하면 ALREADY_ACCEPTED(seq 0) 이고 대기열에 다시 들어가지 않는다.")
        @Test
        void returnsAlreadyAccepted_whenAdmittedUserRetries() {
            enqueue(1L);
            assertThat(queue.promote(CAMPAIGN, gen)).isEqualTo(1);
            assertThat(queue.isAdmitted(CAMPAIGN, 1L)).isTrue();

            EnqueueResult retry = enqueue(1L);

            assertThat(retry).isEqualTo(new EnqueueResult(EnqueueOutcome.ALREADY_ACCEPTED, 0L));
            assertThat(queue.waitingRank(CAMPAIGN, 1L)).isEmpty();
            assertThat(queue.acceptedCount(CAMPAIGN)).isEqualTo(1L);
        }

        @DisplayName("접수 상한에 도달하면 SOLD_OUT 이고 seq 는 증가하지 않는다. 상한 안의 재요청은 그대로 ALREADY_ACCEPTED 다.")
        @Test
        void soldOutDoesNotConsumeSeq() {
            queue.enqueue(CAMPAIGN, 1L, 2L, gen);
            queue.enqueue(CAMPAIGN, 2L, 2L, gen);

            EnqueueResult third = queue.enqueue(CAMPAIGN, 3L, 2L, gen);
            EnqueueResult fourth = queue.enqueue(CAMPAIGN, 4L, 2L, gen);

            assertThat(third).isEqualTo(new EnqueueResult(EnqueueOutcome.SOLD_OUT, 0L));
            assertThat(fourth).isEqualTo(new EnqueueResult(EnqueueOutcome.SOLD_OUT, 0L));
            assertThat(queue.acceptedCount(CAMPAIGN)).isEqualTo(2L);
            assertThat(queue.waitingRank(CAMPAIGN, 3L)).isEmpty();
            assertThat(queue.enqueue(CAMPAIGN, 1L, 2L, gen).outcome()).isEqualTo(EnqueueOutcome.ALREADY_ACCEPTED);
        }

        @DisplayName("INV-W8-12 gate open 뒤 Redis 가 비면(FLUSHALL) 세대 불일치로 STALE_GENERATION 이고 접수 효과가 없다.")
        @Test
        void staleGeneration_afterFlush() {
            redisCleanUp.truncateAll();

            EnqueueResult result = enqueue(1L);

            assertThat(result.outcome()).isEqualTo(EnqueueOutcome.STALE_GENERATION);
            assertThat(queue.acceptedCount(CAMPAIGN)).isZero();
            assertThat(queue.waitingRank(CAMPAIGN, 1L)).isEmpty();
        }
    }

    @DisplayName("승격 Lua (§5-2, INV-W8-06)")
    @Nested
    class Promote {
        @DisplayName("10명이 순서대로 접수하면 C=3 만큼 접수 번호 순으로 승격되고, 하나를 비우면 다음 번호가 승격된다.")
        @Test
        void promotesInSeqOrder_upToCapacity() {
            for (long userId = 1; userId <= 10; userId++) {
                enqueue(userId);
            }

            int promoted = queue.promote(CAMPAIGN, gen);

            assertThat(promoted).isEqualTo(3);
            assertThat(admittedMembers()).containsExactlyInAnyOrder("1", "2", "3");
            assertThat(queue.waitingRank(CAMPAIGN, 4L)).contains(0L);
            assertThat(queue.promote(CAMPAIGN, gen)).as("자리가 모두 예약된 상태에서는 승격 0").isZero();

            assertThat(queue.removeAdmitted(CAMPAIGN, 1L)).isTrue();
            assertThat(queue.promote(CAMPAIGN, gen)).isEqualTo(1);
            assertThat(admittedMembers()).containsExactlyInAnyOrder("2", "3", "4");
        }

        @DisplayName("실행 중인 자리(permits)도 예약으로 계산해 admitted + permits 가 C 를 넘지 않는다.")
        @Test
        void countsPermitsAsBusy() {
            admitDirectly(1L, 2L);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 2L, "t2", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            for (long userId = 3; userId <= 6; userId++) {
                enqueue(userId);
            }

            int promoted = queue.promote(CAMPAIGN, gen);

            assertThat(promoted).as("permits 2 + admitted 2 = busy 4 > C 3 → 승격 0").isZero();
            queue.removeAdmitted(CAMPAIGN, 1L);
            queue.removeAdmitted(CAMPAIGN, 2L);
            assertThat(queue.promote(CAMPAIGN, gen)).as("permits 2 → 빈자리 1").isEqualTo(1);
            assertThat(admittedMembers()).containsExactly("3");
        }

        @DisplayName("INV-W8-12 Redis 가 비면 -1 을 돌려주고 아무도 승격하지 않는다.")
        @Test
        void staleGeneration_returnsMinusOne() {
            enqueue(1L);
            redisCleanUp.truncateAll();

            assertThat(queue.promote(CAMPAIGN, gen)).isEqualTo(-1);
            assertThat(admittedMembers()).isEmpty();
        }
    }

    @DisplayName("세마포어 Lua (§5-3, INV-W8-01)")
    @Nested
    class Acquire {
        @DisplayName("C=3 이면 승격된 3명은 자리를 얻고 4번째는 NO_PERMIT 이다. 한 명이 반납하면 4번째가 들어간다.")
        @Test
        void capsAtCapacity_andReusesReleasedPermit() {
            admitDirectly(1L, 2L, 3L, 4L);

            assertThat(semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 2L, "t2", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 3L, "t3", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 4L, "t4", gen)).isEqualTo(ClaimOutcome.NO_PERMIT);
            assertThat(permitCount()).isEqualTo(3);

            assertThat(semaphore.release(CAMPAIGN, "t2")).isTrue();
            assertThat(semaphore.tryAcquire(CAMPAIGN, 4L, "t4", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(permitCount()).isEqualTo(3);
        }

        @DisplayName("승격되지 않은 사용자는 NOT_ADMITTED 이고 자리를 차지하지 않는다.")
        @Test
        void rejectsNotAdmitted() {
            assertThat(semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen)).isEqualTo(ClaimOutcome.NOT_ADMITTED);
            assertThat(permitCount()).isZero();
        }

        @DisplayName("같은 token 으로 다시 호출하면 ACQUIRED 이고 자리는 늘지 않는다.")
        @Test
        void sameTokenIsIdempotent() {
            admitDirectly(1L);
            semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen);

            assertThat(semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(permitCount()).isEqualTo(1);
        }

        @DisplayName("INV-W8-12 Redis 가 비면 STALE_GENERATION 이고 자리를 차지하지 않는다.")
        @Test
        void staleGeneration() {
            admitDirectly(1L);
            redisCleanUp.truncateAll();

            assertThat(semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen)).isEqualTo(ClaimOutcome.STALE_GENERATION);
            assertThat(permitCount()).isZero();
        }
    }

    @DisplayName("반납 (INV-W8-04)")
    @Nested
    class Release {
        @DisplayName("자기 token 2회 반납과 모르는 token 반납은 다른 활성 token 에 영향을 주지 않는다.")
        @Test
        void releasesOnlyOwnToken() {
            admitDirectly(1L, 2L, 3L);
            semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen);
            semaphore.tryAcquire(CAMPAIGN, 2L, "t2", gen);
            semaphore.tryAcquire(CAMPAIGN, 3L, "t3", gen);

            assertThat(semaphore.release(CAMPAIGN, "t1")).isTrue();
            assertThat(semaphore.release(CAMPAIGN, "t1")).isFalse();
            assertThat(semaphore.release(CAMPAIGN, "unknown")).isFalse();

            assertThat(permitCount()).isEqualTo(2);
            assertThat(permitScore("t2")).isNotNull();
            assertThat(permitScore("t3")).isNotNull();
        }
    }

    @DisplayName("하트비트 회수 (§7-2, INV-W8-09·11)")
    @Nested
    class Heartbeat {
        @DisplayName("INV-W8-09① 하트비트 없이 TTL 이 지나면 다음 Lua 호출이 token 을 회수하고 새 claim 이 들어간다.")
        @Test
        void reclaimsPermit_whenHeartbeatStops() {
            admitDirectly(1L, 2L, 3L, 4L);
            semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen);
            semaphore.tryAcquire(CAMPAIGN, 2L, "t2", gen);
            semaphore.tryAcquire(CAMPAIGN, 3L, "t3", gen);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 4L, "t4", gen)).isEqualTo(ClaimOutcome.NO_PERMIT);

            sleep(TTL_PASSED_MS);

            assertThat(semaphore.tryAcquire(CAMPAIGN, 4L, "t4", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(permitScore("t1")).isNull();
            assertThat(permitScore("t2")).isNull();
            assertThat(permitScore("t3")).isNull();
            assertThat(permitCount()).isEqualTo(1);
        }

        @DisplayName("INV-W8-09② 하트비트를 유지하면 TTL 이 지나도 token 이 남아 4번째 claim 은 여전히 NO_PERMIT 이다.")
        @Test
        void keepsPermit_whileHeartbeatContinues() {
            admitDirectly(1L, 2L, 3L, 4L);
            semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen);
            // 운영 하트비트처럼 획득 뒤 한 주기(200ms) 지나서 첫 갱신을 보낸다. 같은 밀리초 안의 갱신은 score 가 같아 false 가 된다
            long deadline = System.currentTimeMillis() + TTL_PASSED_MS;
            while (System.currentTimeMillis() < deadline) {
                sleep(200);
                assertThat(semaphore.heartbeat(CAMPAIGN, "t1")).isTrue();
            }

            assertThat(semaphore.tryAcquire(CAMPAIGN, 2L, "t2", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 3L, "t3", gen)).isEqualTo(ClaimOutcome.ACQUIRED);
            assertThat(semaphore.tryAcquire(CAMPAIGN, 4L, "t4", gen)).isEqualTo(ClaimOutcome.NO_PERMIT);
            assertThat(permitScore("t1")).isNotNull();
        }

        @DisplayName("INV-W8-11 회수된 token 은 늦은 하트비트로 되살아나지 않고, 반납은 false(recovered) 다.")
        @Test
        void doesNotResurrectReclaimedToken() {
            admitDirectly(1L, 2L);
            semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen);
            sleep(TTL_PASSED_MS);
            semaphore.tryAcquire(CAMPAIGN, 2L, "t2", gen);
            assertThat(permitScore("t1")).as("TTL 경과 후 Lua 가 t1 을 회수").isNull();

            boolean refreshed = semaphore.heartbeat(CAMPAIGN, "t1");

            assertThat(refreshed).isFalse();
            assertThat(permitScore("t1")).isNull();
            assertThat(semaphore.release(CAMPAIGN, "t1")).isFalse();
            assertThat(permitScore("t2")).isNotNull();
        }

        @DisplayName("살아 있는 token 의 하트비트는 score 를 현재 시각으로 올린다.")
        @Test
        void heartbeatAdvancesScore() {
            admitDirectly(1L);
            semaphore.tryAcquire(CAMPAIGN, 1L, "t1", gen);
            Double before = permitScore("t1");
            sleep(50);

            assertThat(semaphore.heartbeat(CAMPAIGN, "t1")).isTrue();

            assertThat(permitScore("t1")).isGreaterThan(before);
        }
    }

    @DisplayName("상태 조회용 단일 명령")
    @Nested
    class Lookup {
        @DisplayName("removeAdmitted 는 지운 경우만 true 이고, isAdmitted·waitingRank 는 읽기만 한다.")
        @Test
        void readOnlyLookups() {
            enqueue(1L);
            assertThat(queue.isAdmitted(CAMPAIGN, 1L)).isFalse();
            queue.promote(CAMPAIGN, gen);

            assertThat(queue.isAdmitted(CAMPAIGN, 1L)).isTrue();
            assertThat(queue.waitingRank(CAMPAIGN, 1L)).isEqualTo(Optional.empty());
            assertThat(queue.removeAdmitted(CAMPAIGN, 1L)).isTrue();
            assertThat(queue.removeAdmitted(CAMPAIGN, 1L)).isFalse();
            assertThat(queue.isAdmitted(CAMPAIGN, 1L)).isFalse();
        }
    }
}
