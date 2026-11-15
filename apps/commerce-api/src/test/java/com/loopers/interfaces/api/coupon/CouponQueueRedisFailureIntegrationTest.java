package com.loopers.interfaces.api.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.config.redis.RedisConfig;
import com.loopers.infrastructure.coupon.CouponJpaEntity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.show-sql=false", "coupon.queue.worker.enabled=false", "coupon.queue.open-on-start=true"
})
class CouponQueueRedisFailureIntegrationTest extends CouponApiTestSupport {
    private static final int PORT = availablePort();
    // This Redis container belongs exclusively to this test.
    @Container
    private static final GenericContainer<?> OWNED_REDIS = new GenericContainer<>("redis:7.0")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(PORT), ExposedPort.tcp(6379))))
            .withCommand("redis-server", "--save", "", "--appendonly", "no");

    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;

    @DynamicPropertySource
    static void redisEndpoint(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", OWNED_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> OWNED_REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", OWNED_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> OWNED_REDIS.getMappedPort(6379));
    }

    @Test
    void Redis_장애_복구_후에도_신규_접수는_닫히고_기발급자는_기존_쿠폰을_받는다() {
        // 활성 캠페인에 5장 중 1장 발급되어 있고, 두 번째 사용자는 발급받지 않았다.
        long campaignId = activeCampaignWithNoIssues(5);
        long issuedUser = user();
        long newUser = user();
        long couponId = coupons.saveAndFlush(new CouponJpaEntity(campaignId, issuedUser, "5,000원 할인권")).getId();
        jdbc.update("update coupon_campaigns set issued_count = 1 where id = ?", campaignId);

        String ownContainer = OWNED_REDIS.getContainerId();
        OWNED_REDIS.getDockerClient().stopContainerCmd(ownContainer).withTimeout(1).exec();
        try {
            var unavailable = submit(campaignId, newUser);
            assertThat(unavailable.getStatusCode().value()).isEqualTo(503);
            assertThat(unavailable.getBody().requiredAt("/meta/errorCode").asText())
                    .isEqualTo("COUPON_ADMISSION_UNAVAILABLE");
            var existing = submit(campaignId, issuedUser);
            assertThat(existing.getStatusCode().value()).isEqualTo(200);
            assertThat(existing.getBody().requiredAt("/data/couponId").asLong()).isEqualTo(couponId);
        } finally {
            OWNED_REDIS.getDockerClient().startContainerCmd(ownContainer).exec();
        }

        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().until(() -> {
            try (var connection = redis.getConnectionFactory().getConnection()) {
                return "PONG".equals(connection.ping());
            }
        });
        assertThat(submit(campaignId, newUser).getStatusCode().value()).isEqualTo(503);
        assertThat(submit(campaignId, issuedUser).getBody().requiredAt("/data/couponId").asLong()).isEqualTo(couponId);
        assertThat(coupons.countByCampaignId(campaignId)).isEqualTo(1);
    }

    private static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
