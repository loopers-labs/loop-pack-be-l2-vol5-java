package com.loopers.domains.coupon.in;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이 클래스만 쓰는 Redis 컨테이너를 띄워 실제로 멈추고 되살린다. 공용 static 컨테이너는 건드리지 않는다(7주차 Redis 장애 테스트 방식).
// stop/start 대신 docker pause/unpause를 쓴다. 컨테이너를 다시 만들면 매핑 포트가 바뀌어 앱의 연결이 돌아올 수 없기 때문이다.
// pause 동안 명령은 응답 없이 수용 전용 timeout(500ms)에 걸린다.
class CouponAdmissionRedisFailureTest extends CouponIntegrationSupport {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:latest")).withExposedPorts(6379);
    private static final Duration DEADLINE = Duration.ofSeconds(20);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        REDIS.start();
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Test
    @DisplayName("F1~F2: 수용 연결의 Redis가 멈추면 새 발급은 503이고 DB 효과 없이 설정 행이 닫히며 기존 쿠폰은 200이다. Redis가 다시 PONG해도 닫힘은 그대로라 여전히 503이다")
    void redisOutageClosesAdmissionAndStaysClosedAfterRecovery() throws Exception {
        user(1);
        user(2);
        long campaignId = campaign(true, 10, 0, 3000);
        issue(1, campaignId).andExpect(status().isCreated());
        long owned = couponId(campaignId, 1);
        boolean paused = false;
        try {
            DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
            paused = true;

            // F1
            issue(2, campaignId).andExpect(status().isServiceUnavailable());
            assertThat(couponId(campaignId, 2)).isNull();
            assertThat(issuedCount(campaignId)).isEqualTo(1);
            assertThat(admissionRow()).containsEntry("open", false);
            assertThat((String) admissionRow().get("closed_reason")).contains("acquire");
            issue(1, campaignId).andExpect(status().isOk());
            mine(1).andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].couponId").value(owned));

            // F2
            DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
            paused = false;
            assertThat(awaitPong()).as("Redis가 " + DEADLINE.toSeconds() + "초 안에 PONG").isTrue();
            issue(2, campaignId).andExpect(status().isServiceUnavailable());
            assertThat(admissionRow()).containsEntry("open", false);
            assertThat(couponId(campaignId, 2)).isNull();
        } finally {
            if (paused) {
                DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
            }
        }
    }

    private boolean awaitPong() throws InterruptedException {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
            new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        try {
            Instant deadline = Instant.now().plus(DEADLINE);
            while (Instant.now().isBefore(deadline)) {
                try (RedisConnection connection = factory.getConnection()) {
                    if ("PONG".equals(connection.ping())) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    Thread.sleep(200);
                }
            }
            return false;
        } finally {
            factory.destroy();
        }
    }
}
