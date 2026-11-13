package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponAdmissionProperties;
import com.loopers.application.coupon.LocalLimiter;
import com.loopers.application.coupon.SemaphoreGate.Admission;
import com.loopers.application.coupon.SemaphoreGate.Status;
import com.loopers.config.redis.RedisConfig;
import com.loopers.utils.RedisCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이전 프로세스가 남긴 고아 token(반환하지 못한 자기 인스턴스 token)을 실제 Redis에서 정리하는 규칙을 확인한다.
 * 고아 상태는 재시작 뒤의 Redis와 같게 Set에 token을 미리 넣어 만든다(앱 재시작 자체를 흉내 내지 않고 결과 상태를 쓴다).
 */
@SpringBootTest
@TestPropertySource(properties = "coupon.admission.capacity=3")
class CouponAdmissionRecoveryTest {

    @Autowired
    @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER)
    private RedisTemplate<String, String> masterTemplate;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private CouponAdmissionProperties properties;

    @Autowired
    private LocalLimiter localLimiter;

    @Autowired
    private RedisCleanUp redisCleanUp;

    /** 기동 뒤 한 번만 하는 이전 프로세스 정리를 테스트마다 새로 겪도록, 테스트마다 새 게이트(새 "프로세스")를 만든다. 연결은 실제 Redis다. */
    private RedisSemaphoreGate gate;

    @BeforeEach
    void setUp() {
        redisCleanUp.truncateAll();
        gate = new RedisSemaphoreGate(masterTemplate, properties, localLimiter);
    }

    @AfterEach
    void cleanRedis() {
        redisCleanUp.truncateAll();
    }

    private void seed(String... tokens) {
        redisTemplate.opsForSet().add(RedisSemaphoreGate.PERMITS_KEY, tokens);
    }

    private boolean isMember(String token) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(RedisSemaphoreGate.PERMITS_KEY, token));
    }

    @DisplayName("고아 token이 용량을 모두 차지하면 Redis가 살아 있어도 자리가 없고, 정리하면 자기 인스턴스 token만 지워져 다시 자리가 난다.")
    @Test
    void orphansConsumeCapacity_untilOwnTokensAreSwept() {
        // arrange — 이전 프로세스가 반환하지 못한 자기 token 3개(C=3)와 다른 인스턴스의 token 1개
        seed("local-1:orphan-1", "local-1:orphan-2", "local-1:orphan-3");

        // act & assert 1 — 정리 전에는 자리 없음
        assertThat(gate.acquire().status()).isEqualTo(Status.FULL);

        // act 2
        gate.recoverOwnTokens();

        // assert 2
        assertThat(gate.activeCount()).isZero();
        Admission afterSweep = gate.acquire();
        assertThat(afterSweep.status()).isEqualTo(Status.ACQUIRED);
        assertThat(gate.activeCount()).isEqualTo(1);
    }

    @DisplayName("정리는 다른 인스턴스의 token과 비슷한 접두어의 token, 이 프로세스가 진행 중인 실행의 token을 지우지 않는다.")
    @Test
    void sweepKeepsOtherInstancesAndLiveExecutions() {
        // arrange
        Admission live = gate.acquire();
        seed("local-1:orphan", "other-1:keep", "local-10:keep-too", "local-1x:keep-too");

        // act
        gate.recoverOwnTokens();

        // assert
        assertThat(isMember("local-1:orphan")).as("자기 인스턴스의 고아는 정리").isFalse();
        assertThat(isMember("other-1:keep")).as("다른 인스턴스 token").isTrue();
        assertThat(isMember("local-10:keep-too")).as("접두어만 비슷한 인스턴스 token").isTrue();
        assertThat(isMember("local-1x:keep-too")).isTrue();
        assertThat(isMember(live.token())).as("진행 중인 자기 실행의 token").isTrue();
    }

    @DisplayName("이전 프로세스의 고아 정리는 기동 뒤 한 번만 하고, 이후에는 이 프로세스가 기록한 미반환 token만 정리한다.")
    @Test
    void previousRunSweepRunsOnce() {
        // arrange
        seed("local-1:orphan-a");
        gate.recoverOwnTokens();
        assertThat(isMember("local-1:orphan-a")).isFalse();

        // act — 첫 정리 뒤에 생긴 접두어 token은 이 프로세스가 기록하지 않았다면 건드리지 않는다
        seed("local-1:late");
        gate.recoverOwnTokens();

        // assert
        assertThat(isMember("local-1:late")).isTrue();
    }
}
