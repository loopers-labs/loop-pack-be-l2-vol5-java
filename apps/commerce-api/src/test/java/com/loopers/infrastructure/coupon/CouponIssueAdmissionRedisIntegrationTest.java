package com.loopers.infrastructure.coupon;

import com.loopers.application.coupon.CouponIssueAdmissionState;
import com.loopers.application.coupon.CouponIssuePermits;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(properties = {
    "coupon.issue.capacity=3",
    "coupon.issue.admission.open-on-startup=true",
    "product.list-cache.warm-up.enabled=false",
    "payment.periodic-check.enabled=false",
    "order.confirmed-event.relay.enabled=false",
    "order.confirmed-event.outbox.metrics.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponIssueAdmissionRedisIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.0"))
        .withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Autowired private CouponIssueAdmissionState admission;
    @Autowired private CouponIssueAdmissionStartupRunner startupRunner;
    @Autowired private CouponIssuePermits permits;
    @Autowired
    @Qualifier("redisTemplateMaster")
    private RedisTemplate<String, String> redisTemplate;

    @AfterEach
    void cleanUpOwnState() {
        admission.close();
        redisTemplate.delete(RedisCouponIssuePermits.PERMITS_KEY);
    }

    @DisplayName("앱 시작 시 실제 primary Set이 비었으면 열고 token이 남은 상태에서는 닫는다.")
    @Test
    void opensOnStartupOnlyWhenPrimarySetIsEmpty() throws Exception {
        assertThat(permits.isEmpty()).isTrue();
        assertThat(admission.isOpen()).isTrue();

        redisTemplate.opsForSet().add(RedisCouponIssuePermits.PERMITS_KEY, "stale-token");
        startupRunner.run(mock(ApplicationArguments.class));

        assertThat(admission.isOpen()).isFalse();
    }
}
