package com.loopers.infrastructure.coupon;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.coupon.SemaphoreGate.Admission;
import com.loopers.application.coupon.SemaphoreGate.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 이 테스트만 쓰는 Redis 컨테이너를 실제로 멈췄다가 다시 시작해, 장애 중 반환·획득이 남긴 자기 token을 복구 뒤 정리하는지 확인한다.
 * 다른 테스트가 함께 쓰는 Redis는 건드리지 않고, 재시작해도 데이터와 주소가 유지되도록 AOF와 고정 호스트 포트를 쓴다.
 */
@SpringBootTest
@TestPropertySource(properties = "coupon.admission.capacity=3")
class CouponAdmissionRedisOutageTest {

    private static final int REDIS_PORT = freePort();
    private static final GenericContainer<?> OUTAGE_REDIS = new GenericContainer<>(DockerImageName.parse("redis:latest"))
        .withExposedPorts(6379)
        .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always")
        .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindPort(REDIS_PORT), new ExposedPort(6379))
        ));

    static {
        OUTAGE_REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", OUTAGE_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS_PORT);
        registry.add("datasource.redis.replicas[0].host", OUTAGE_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS_PORT);
    }

    @Autowired
    private RedisSemaphoreGate gate;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private final DockerClient docker = DockerClientFactory.instance().client();

    @AfterEach
    void tearDown() {
        if (!isRedisRunning()) {
            docker.startContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> gate.activeCount());
        }
        redisTemplate.delete(RedisSemaphoreGate.PERMITS_KEY);
    }

    @DisplayName("Redis가 멈춘 동안 반환에 실패한 token은 기록되고, 복구되면 자기 token만 정리되어 다른 실행의 자리는 남는다.")
    @Test
    void releasesFailedDuringOutage_areCleanedAfterRecovery_withoutTouchingOthers() {
        // arrange — 정상 상태에서 두 실행이 자리를 얻고, 다른 인스턴스의 token도 하나 있다
        Admission failsToRelease = gate.acquire();
        Admission keepsRunning = gate.acquire();
        redisTemplate.opsForSet().add(RedisSemaphoreGate.PERMITS_KEY, "other-1:keep");
        assertThat(gate.activeCount()).isEqualTo(3);

        // act 1 — Redis가 멈춘 동안 한 실행이 끝나 반환을 시도하고(실패), 새 요청의 획득 결과는 불확실하다
        docker.stopContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
        gate.release(failsToRelease);
        Admission duringOutage = gate.acquire();

        // assert 1 — 반환 실패와 불확실한 획득은 자기 token으로 기록되고, 정리를 시도해도 Redis가 없으면 예외 없이 기록이 남는다
        assertThat(duringOutage.status()).isEqualTo(Status.UNCERTAIN);
        assertThat(gate.unreleasedTokens()).containsExactlyInAnyOrder(failsToRelease.token(), duringOutage.token());
        gate.recoverOwnTokens();
        assertThat(gate.unreleasedTokens()).as("복구 전에는 기록이 남는다").hasSize(2);

        // act 2 — 같은 Redis가 복구되면 정리가 성공한다
        docker.startContainerCmd(OUTAGE_REDIS.getContainerId()).exec();
        // 정리는 스케줄러의 한 회차(재개 확인 → 자기 token 정리)와 같게 부른다. 최소 대기가 지나야 돌아온다
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            gate.tryResume();
            gate.recoverOwnTokens();
            assertThat(gate.unreleasedTokens()).isEmpty();
        });

        // assert 2 — 반환하지 못했던 자기 token만 사라지고, 진행 중인 실행과 다른 인스턴스의 token은 남는다
        assertThat(redisTemplate.opsForSet().isMember(RedisSemaphoreGate.PERMITS_KEY, failsToRelease.token())).isFalse();
        assertThat(redisTemplate.opsForSet().isMember(RedisSemaphoreGate.PERMITS_KEY, keepsRunning.token())).isTrue();
        assertThat(redisTemplate.opsForSet().isMember(RedisSemaphoreGate.PERMITS_KEY, "other-1:keep")).isTrue();
        assertThat(gate.activeCount()).isEqualTo(2);
    }

    private boolean isRedisRunning() {
        return Boolean.TRUE.equals(docker.inspectContainerCmd(OUTAGE_REDIS.getContainerId()).exec().getState().getRunning());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
