package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.SemaphoreGate.Admission;
import com.loopers.application.coupon.SemaphoreGate.Status;
import com.loopers.concurrency.ConcurrentRunner;
import com.loopers.concurrency.ConcurrentRunner.WorkerResult;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 Redis에서 C=3 수용이 원자적으로 지켜지는지, 자기 token만 반환되는지 확인한다. mock이나 thread-local 카운트를 쓰지 않는다. */
@SpringBootTest
@TestPropertySource(properties = "coupon.admission.capacity=3")
class CouponAdmissionTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private RedisSemaphoreGate gate;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RedisCleanUp redisCleanUp;

    @BeforeEach
    @AfterEach
    void cleanRedis() {
        redisCleanUp.truncateAll();
    }

    @DisplayName("C=3이면, 세 실행이 자리를 얻고 네 번째는 자리 없음이다. 한 실행이 반환하면 네 번째가 다시 들어가고, 모두 반환하면 활성 token은 0이다.")
    @Test
    void acceptsThree_rejectsFourth_reusesReleasedSlot() {
        // act 1 — 세 실행이 자리를 얻는다
        Admission first = gate.acquire();
        Admission second = gate.acquire();
        Admission third = gate.acquire();

        // assert 1 — 활성 token 3, 네 번째는 거절
        assertThat(List.of(first, second, third)).extracting(Admission::status).containsOnly(Status.ACQUIRED);
        assertThat(gate.activeCount()).isEqualTo(3);
        Admission fourth = gate.acquire();
        assertThat(fourth.status()).isEqualTo(Status.FULL);
        assertThat(gate.activeCount()).as("거절된 요청은 token을 남기지 않는다").isEqualTo(3);

        // act 2 — 한 실행이 끝나 자기 token을 반환한다
        gate.release(first);

        // assert 2 — 자리가 하나 비고, 네 번째 재요청이 들어간다
        assertThat(gate.activeCount()).isEqualTo(2);
        Admission retry = gate.acquire();
        assertThat(retry.status()).isEqualTo(Status.ACQUIRED);
        assertThat(gate.activeCount()).isEqualTo(3);

        // act 3 — 나머지도 끝난다
        gate.release(second);
        gate.release(third);
        gate.release(retry);

        // assert 3
        assertThat(gate.activeCount()).isZero();
        assertThat(gate.unreleasedTokens()).isEmpty();
    }

    @DisplayName("같은 순간 12개 실행이 몰려도, 정확히 3개만 자리를 얻는다.")
    @Test
    void admitsExactlyCapacity_underConcurrency() {
        // arrange
        List<Callable<Admission>> tasks = IntStream.range(0, 12)
            .<Callable<Admission>>mapToObj(i -> gate::acquire)
            .toList();

        // act
        List<WorkerResult<Admission>> results = ConcurrentRunner.runTogether(tasks, TIMEOUT);

        // assert
        assertThat(ConcurrentRunner.count(results, ConcurrentRunner.Outcome.SUCCESS)).isEqualTo(12);
        assertThat(results.stream().filter(r -> r.value().status() == Status.ACQUIRED)).hasSize(3);
        assertThat(results.stream().filter(r -> r.value().status() == Status.FULL)).hasSize(9);
        assertThat(gate.activeCount()).isEqualTo(3);
    }

    @DisplayName("같은 반환을 두 번 하거나 모르는 token을 반환해도, 다른 활성 token은 남는다.")
    @Test
    void releasingTwiceOrUnknownToken_keepsOtherActiveTokens() {
        // arrange
        Admission mine = gate.acquire();
        Admission other = gate.acquire();

        // act
        gate.release(mine);
        gate.release(mine);
        gate.release(new Admission(Status.ACQUIRED, "local-1:never-issued"));

        // assert
        assertThat(gate.activeCount()).isEqualTo(1);
        assertThat(redisTemplate.opsForSet().isMember(RedisSemaphoreGate.PERMITS_KEY, other.token())).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(RedisSemaphoreGate.PERMITS_KEY, mine.token())).isFalse();
    }

    @DisplayName("같은 token으로 다시 획득해도, 자리를 하나 더 쓰지 않는다.")
    @Test
    void sameTokenAcquiredTwice_takesOneSlot() {
        // act
        Admission first = gate.acquire("local-1:fixed-token");
        Admission again = gate.acquire("local-1:fixed-token");

        // assert
        assertThat(first.status()).isEqualTo(Status.ACQUIRED);
        assertThat(again.status()).isEqualTo(Status.ACQUIRED);
        assertThat(gate.activeCount()).isEqualTo(1);
    }

    @DisplayName("token은 인스턴스 ID와 실행마다 다른 UUID로 만들고, permits 키에 TTL을 걸지 않는다.")
    @Test
    void tokenIsInstanceIdWithUniqueUuid_andKeyHasNoTtl() {
        // act
        Admission first = gate.acquire();
        Admission second = gate.acquire();

        // assert
        assertThat(first.token()).startsWith("local-1:").isNotEqualTo(second.token());
        assertThat(redisTemplate.getExpire(RedisSemaphoreGate.PERMITS_KEY)).as("TTL 없음(-1)").isEqualTo(-1L);
    }
}
