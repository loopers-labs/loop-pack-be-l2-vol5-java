package com.loopers.infrastructure.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 캐시 연결의 명령 타임아웃이 실제로 50ms 근처에서 끝나는지 — 설정값이 아니라 동작으로 확인한다.
 *
 * <p>Redis를 일시정지(docker pause)하면 연결은 살아 있지만 응답이 오지 않는 "멈춤" 상태가 된다. 공용 테스트 Redis를
 * 멈추면 다른 테스트가 깨지므로 이 테스트만 전용 컨테이너를 쓴다(docs/week4/design.md 8번 섹션).
 */
@SpringBootTest
class CacheRedisTimeoutTest {

    private static final GenericContainer<?> redis =
        new GenericContainer<>(DockerImageName.parse("redis:7.0")).withExposedPorts(6379);

    static {
        redis.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", redis::getHost);
        registry.add("datasource.redis.master.port", () -> redis.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", redis::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    @Qualifier(CacheRedisConfig.CACHE_REDIS_TEMPLATE)
    private RedisTemplate<String, String> cacheRedisTemplate;

    @AfterEach
    void unpause() {
        if (Boolean.TRUE.equals(redis.getDockerClient().inspectContainerCmd(redis.getContainerId()).exec().getState().getPaused())) {
            redis.getDockerClient().unpauseContainerCmd(redis.getContainerId()).exec();
        }
    }

    @DisplayName("Redis가 응답하지 않으면, 명령은 연결 수립 타임아웃(1초)이 아니라 명령 타임아웃(50ms) 근처에서 실패한다.")
    @Test
    void pausedRedis_commandFailsNear50ms() {
        cacheRedisTemplate.opsForValue().set("timeout-test", "ok", Duration.ofSeconds(5)); // 연결을 미리 맺어 둔다
        redis.getDockerClient().pauseContainerCmd(redis.getContainerId()).exec();

        long start = System.nanoTime();
        assertThatThrownBy(() -> cacheRedisTemplate.opsForValue().get("timeout-test"))
            .isInstanceOf(RuntimeException.class);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(elapsedMillis).isBetween(40L, 500L);
    }
}
