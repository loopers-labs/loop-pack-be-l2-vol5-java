package com.loopers.interfaces.api.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.jpa.show-sql=false", "coupon.queue.worker.enabled=true", "coupon.queue.open-on-start=true"
})
@Import(CouponQueueWorkerPauseIntegrationTest.FailureConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CouponQueueWorkerPauseIntegrationTest extends CouponApiTestSupport {
    private static final String KEY = "coupon:{queue-pause-" + UUID.randomUUID() + "}";

    @DynamicPropertySource
    static void isolateQueue(DynamicPropertyRegistry registry) {
        registry.add("coupon.queue.key", () -> KEY);
    }

    @Autowired private ClaimFailureQueue failureQueue;
    @Autowired private CouponQueueWorkerState workerState;
    @Autowired @Qualifier(RedisConfig.REDIS_TEMPLATE_MASTER) private RedisTemplate<String, String> redis;

    @Test
    void 작업자_요청_실패_후_기존_신청은_유지하고_신규_접수는_중단한다() {
        long campaignId = activeCampaignWithNoIssues(5);
        long waitingUser = user();
        long newUser = user();

        // 작업자는 첫 claim에서 기다리고 있으며 Redis 대기열은 비어 있다.
        var accepted = submit(campaignId, waitingUser);
        assertThat(accepted.getStatusCode().value()).isEqualTo(202);
        String requestId = (String) redis.opsForHash().get(KEY + ":users", campaignId + ":" + waitingUser);
        assertThat(requestId).isNotNull();
        assertThat(redis.opsForSet().isMember(KEY + ":waiting", requestId)).isTrue();

        // claim 오류는 I/O 경계에서 주입하고, 신청과 조회는 실제 Redis를 사용한다.
        failureQueue.failClaim();
        await().atMost(Duration.ofSeconds(10)).until(() -> !workerState.acceptingNewRequests());

        var existing = submit(campaignId, waitingUser);
        assertThat(existing.getStatusCode().value()).isEqualTo(202);
        assertThat(existing.getBody().at("/data/requestId").isMissingNode()).isTrue();
        assertThat(redis.opsForHash().get(KEY + ":users", campaignId + ":" + waitingUser)).isEqualTo(requestId);
        assertThat(redis.opsForSet().isMember(KEY + ":waiting", requestId)).isTrue();

        var rejected = submit(campaignId, newUser);
        assertThat(rejected.getStatusCode().value()).isEqualTo(503);
        assertThat(rejected.getBody().requiredAt("/meta/errorCode").asText())
                .isEqualTo("COUPON_ADMISSION_UNAVAILABLE");
        assertThat(redis.opsForSet().size(KEY + ":waiting")).isEqualTo(1);
        assertThat(coupons.countByCampaignId(campaignId)).isZero();
    }

    @AfterEach
    void releaseWorkerAndRemoveQueue() {
        failureQueue.failClaim();
        redis.delete(List.of(KEY + ":waiting", KEY + ":requests", KEY + ":users",
                KEY + ":processing", KEY + ":completed", KEY + ":campaigns"));
    }

    @TestConfiguration
    static class FailureConfiguration {
        @Bean
        @Primary
        ClaimFailureQueue claimFailureQueue(RedisCouponQueue delegate) {
            return new ClaimFailureQueue(delegate);
        }
    }

    static class ClaimFailureQueue implements CouponRequestQueue {
        private final CouponRequestQueue delegate;
        private final CountDownLatch failure = new CountDownLatch(1);

        ClaimFailureQueue(CouponRequestQueue delegate) {
            this.delegate = delegate;
        }

        void failClaim() {
            failure.countDown();
        }

        @Override
        public Optional<Claim> claim(String workerId) {
            try {
                failure.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("claim interrupted", interrupted);
            }
            throw new IllegalStateException("claim response unavailable");
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
        public void complete(Claim claim) {
            delegate.complete(claim);
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
}
