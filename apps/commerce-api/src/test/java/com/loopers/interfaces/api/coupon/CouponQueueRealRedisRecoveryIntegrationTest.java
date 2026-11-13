package com.loopers.interfaces.api.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.loopers.application.coupon.queue.CouponQueueWorkerState;
import com.loopers.application.coupon.queue.CouponRequestQueue;
import com.loopers.config.redis.RedisConfig;
import com.loopers.infrastructure.coupon.queue.RedisCouponQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.show-sql=false", "coupon.queue.worker.enabled=true", "coupon.queue.open-on-start=true"
})
@Import(CouponQueueRealRedisRecoveryIntegrationTest.RecoveryConfiguration.class)
class CouponQueueRealRedisRecoveryIntegrationTest extends CouponApiTestSupport {
    private static final int PORT = availablePort();
    private static final String KEY = "coupon:{queue-recovery-" + UUID.randomUUID() + "}";

    // AOF is fsynced on each write so this test checks retained PROCESSING records,
    // not Redis crash or data-loss durability guarantees.
    @Container
    private static final GenericContainer<?> OWNED_REDIS = new GenericContainer<>("redis:7.0")
            .withExposedPorts(6379)
            .withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindPort(PORT), ExposedPort.tcp(6379))))
            .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always", "--save", "");

    @DynamicPropertySource
    static void redisEndpoint(DynamicPropertyRegistry registry) {
        registry.add("datasource.redis.master.host", OWNED_REDIS::getHost);
        registry.add("datasource.redis.master.port", () -> OWNED_REDIS.getMappedPort(6379));
        registry.add("datasource.redis.replicas[0].host", OWNED_REDIS::getHost);
        registry.add("datasource.redis.replicas[0].port", () -> OWNED_REDIS.getMappedPort(6379));
        registry.add("coupon.queue.key", () -> KEY);
    }

    @Autowired private CompletionBarrierQueue barrierQueue;
    @Autowired private CouponQueueWorkerState state;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;

    @Test
    void DB_발급_직후_Redis가_중단되면_기록을_보존하고_복구_후에도_신규_접수를_닫는다() throws Exception {
        // 활성 캠페인 5장, 발급 0장, 대기 중인 신청 없음.
        long campaignId = activeCampaignWithNoIssues(5);
        long issuedUser = user();
        long newUser = user();
        var accepted = submit(campaignId, issuedUser);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        String requestId = (String) redis.opsForHash().get(KEY + ":users", campaignId + ":" + issuedUser);
        assertThat(requestId).isNotNull();

        // 처리 기록을 정리하기 직전에 도달했다면 DB 트랜잭션은 이미 커밋됐다.
        assertThat(barrierQueue.awaitCompletion()).isTrue();
        long couponId = coupons.findByCampaignIdAndUserId(campaignId, issuedUser).orElseThrow().getId();
        assertThat(campaigns.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
        Object originalOwner = redis.opsForHash().get(KEY + ":processing", requestId);
        assertThat(originalOwner).isNotNull();

        String containerId = OWNED_REDIS.getContainerId();
        OWNED_REDIS.getDockerClient().stopContainerCmd(containerId).withTimeout(1).exec();
        try {
            // 실제 Redis 중단 상태에서 처리 기록 정리가 실패하도록 기다린다.
            barrierQueue.releaseCompletion();
            await().atMost(Duration.ofSeconds(15)).until(() -> !state.acceptingNewRequests());
            assertThat(submit(campaignId, issuedUser).getBody().requiredAt("/data/couponId").asLong())
                    .isEqualTo(couponId);
        } finally {
            OWNED_REDIS.getDockerClient().startContainerCmd(containerId).exec();
        }

        await().atMost(Duration.ofSeconds(15)).ignoreExceptions().until(() -> {
            try (var connection = redis.getConnectionFactory().getConnection()) {
                return "PONG".equals(connection.ping());
            }
        });
        assertThat(submit(campaignId, newUser).getStatusCode().value()).isEqualTo(503);
        var existing = submit(campaignId, issuedUser);
        assertThat(existing.getStatusCode().value()).isEqualTo(200);
        assertThat(existing.getBody().requiredAt("/data/couponId").asLong()).isEqualTo(couponId);
        assertThat(coupons.countByCampaignId(campaignId)).isEqualTo(1);
        assertThat(redis.opsForHash().get(KEY + ":processing", requestId)).isEqualTo(originalOwner);
        var result = list(issuedUser);
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody().requiredAt("/data")).hasSize(1);
        assertThat(result.getBody().requiredAt("/data/0/couponId").asLong()).isEqualTo(couponId);
    }

    @AfterEach
    void releaseBarrierAndRemoveQueue() {
        barrierQueue.releaseCompletion();
        redis.delete(List.of(KEY + ":waiting", KEY + ":requests", KEY + ":users",
                KEY + ":processing", KEY + ":completed", KEY + ":campaigns"));
    }

    @TestConfiguration
    static class RecoveryConfiguration {
        @Bean
        @Primary
        CompletionBarrierQueue completionBarrierQueue(RedisCouponQueue delegate) {
            return new CompletionBarrierQueue(delegate);
        }
    }

    static class CompletionBarrierQueue implements CouponRequestQueue {
        private final CouponRequestQueue delegate;
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        CompletionBarrierQueue(CouponRequestQueue delegate) {
            this.delegate = delegate;
        }

        boolean awaitCompletion() throws InterruptedException {
            return reached.await(15, TimeUnit.SECONDS);
        }

        void releaseCompletion() {
            release.countDown();
        }

        @Override
        public void complete(Claim claim) {
            reached.countDown();
            try {
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("completion barrier timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("completion barrier interrupted", interrupted);
            }
            delegate.complete(claim);
        }

        @Override
        public SubmitResult submit(long campaignId, long userId) {
            return delegate.submit(campaignId, userId);
        }

        @Override
        public void markSoldOut(long campaignId) {
            delegate.markSoldOut(campaignId);
        }

        @Override
        public Optional<Claim> claim(String workerId) {
            return delegate.claim(workerId);
        }

        @Override
        public AttemptReservation reserveAttempt(Claim claim) {
            return delegate.reserveAttempt(claim);
        }

        @Override
        public RetrySchedule scheduleRetry(Claim claim) {
            return delegate.scheduleRetry(claim);
        }

        @Override
        public boolean retry(Claim claim) {
            return delegate.retry(claim);
        }

    }

    private static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
