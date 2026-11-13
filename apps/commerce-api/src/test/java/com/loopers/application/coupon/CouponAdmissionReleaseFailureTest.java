package com.loopers.application.coupon;

import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponAdmissionQueue;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponPermitSemaphore;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.coupon.RedisCouponPermitSemaphore;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import com.loopers.utils.RedisCleanUp;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 8주차 설계 INV-W8-07 (반납 ≠ 되돌림). 반납(ZREM)만 실패하는 세마포어로 바꿔 끼워, 반납 실패가 DB 결과를 바꾸지 않고 메트릭으로만 드러남을 본다.
 * 운영 코드에는 훅을 두지 않는다. 실제 Redis 구현을 감싸는 테스트 전용 포장지가 release 만 가로챈다 (FaultInjectingProductListCache 와 같은 방식).
 */
@SpringBootTest
class CouponAdmissionReleaseFailureTest {

    private static final ZonedDateTime EXPIRES_AT = ZonedDateTime.of(2026, 12, 31, 23, 59, 0, 0, ZoneId.of("Asia/Seoul"));

    @TestConfiguration
    static class FailingReleaseConfig {
        @Primary
        @Bean
        FailingReleaseSemaphore failingReleaseSemaphore(RedisCouponPermitSemaphore delegate) {
            return new FailingReleaseSemaphore(delegate);
        }
    }

    /** release 만 항상 예외. 나머지는 실제 구현에 그대로 넘긴다. */
    static class FailingReleaseSemaphore implements CouponPermitSemaphore {
        private final CouponPermitSemaphore delegate;
        final AtomicInteger releaseAttempts = new AtomicInteger();

        FailingReleaseSemaphore(CouponPermitSemaphore delegate) {
            this.delegate = delegate;
        }

        @Override
        public ClaimOutcome tryAcquire(Long campaignId, Long userId, String token, String expectedGen) {
            return delegate.tryAcquire(campaignId, userId, token, expectedGen);
        }

        @Override
        public boolean heartbeat(Long campaignId, String token) {
            return delegate.heartbeat(campaignId, token);
        }

        @Override
        public boolean release(Long campaignId, String token) {
            releaseAttempts.incrementAndGet();
            throw new DataAccessResourceFailureException("ZREM 실패(주입)");
        }

        @Override
        public long permitCount(Long campaignId) {
            return delegate.permitCount(campaignId);
        }
    }

    private final CouponAdmissionFacade facade;
    private final CouponAdmissionGate gate;
    private final CouponAdmissionQueue queue;
    private final FailingReleaseSemaphore semaphore;
    private final CouponCampaignRepository couponCampaignRepository;
    private final UserJpaRepository userJpaRepository;
    private final MeterRegistry meterRegistry;
    private final DatabaseCleanUp databaseCleanUp;
    private final RedisCleanUp redisCleanUp;

    @Autowired
    CouponAdmissionReleaseFailureTest(
        CouponAdmissionFacade facade,
        CouponAdmissionGate gate,
        CouponAdmissionQueue queue,
        FailingReleaseSemaphore semaphore,
        CouponCampaignRepository couponCampaignRepository,
        UserJpaRepository userJpaRepository,
        MeterRegistry meterRegistry,
        DatabaseCleanUp databaseCleanUp,
        RedisCleanUp redisCleanUp
    ) {
        this.facade = facade;
        this.gate = gate;
        this.queue = queue;
        this.semaphore = semaphore;
        this.couponCampaignRepository = couponCampaignRepository;
        this.userJpaRepository = userJpaRepository;
        this.meterRegistry = meterRegistry;
        this.databaseCleanUp = databaseCleanUp;
        this.redisCleanUp = redisCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        redisCleanUp.truncateAll();
    }

    @DisplayName("INV-W8-07 발급 성공 뒤 반납이 3회 재시도까지 실패해도 쿠폰과 issued_count 는 유지되고 release{result=failed} 만 1 는다. 자리는 permits 에 남아 TTL 로 회수된다.")
    @Test
    void releaseFailureDoesNotRevertIssue() {
        Long campaignId = couponCampaignRepository.save(
            new CouponCampaign("선착순 쿠폰", 10, Coupon.BenefitType.FIXED, 1000L, EXPIRES_AT)).getId();
        String gen = gate.open(campaignId);
        Long userId = userJpaRepository.save(new User()).getId();
        facade.apply(campaignId, userId);
        queue.promote(campaignId, gen);
        double failedBefore = meterRegistry.counter(CouponAdmissionMetrics.RELEASE, "result", "failed").count();

        CouponAdmissionFacade.ClaimInfo result = facade.claim(campaignId, userId);

        assertThat(result.created()).isTrue();
        assertThat(couponCampaignRepository.findById(campaignId).orElseThrow().getIssuedCount()).isEqualTo(1);
        assertThat(semaphore.releaseAttempts.get()).isEqualTo(1 + CouponAdmissionFacade.RELEASE_RETRIES);
        assertThat(meterRegistry.counter(CouponAdmissionMetrics.RELEASE, "result", "failed").count() - failedBefore).isEqualTo(1.0);
        assertThat(semaphore.permitCount(campaignId)).as("반납 실패한 자리는 남아 있다가 하트비트 TTL 뒤 회수된다").isEqualTo(1);
        assertThat(queue.isAdmitted(campaignId, userId)).as("업무 결과는 확정됐으므로 승격은 지운다").isFalse();
        gate.close(campaignId);
    }
}
