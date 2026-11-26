package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 미확인 결제 지표가 DB의 UNKNOWN 수를 그대로 내보내고, 유한한 tag 하나만 쓰며, 캐시·DB 실패를 정한 대로 다루는지 확인한다.
 * 실제 DB 대신 수를 정해 주는 저장소로 바꿔 끼운다. 실제 DB·Prometheus 응답은 W10 사고 E2E가 확인한다.
 */
class UnresolvedPaymentMetricsTest {

    @DisplayName("지표는 UNKNOWN 시도 수를 status=UNKNOWN tag 하나로 내보내고, 캐시가 없으면 scrape마다 새로 센다.")
    @Test
    void exposesUnknownCount_withSingleFiniteTag() {
        // arrange
        CountingRepository repository = new CountingRepository(3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new UnresolvedPaymentMetrics(repository, registry, Duration.ZERO);
        Gauge gauge = registry.get(UnresolvedPaymentMetrics.METRIC_NAME).gauge();

        // act
        double first = gauge.value();
        repository.unknown.set(0);
        double second = gauge.value();

        // assert
        assertThat(first).isEqualTo(3.0);
        assertThat(second).isZero();
        assertThat(gauge.getId().getTags()).extracting(tag -> tag.getKey() + "=" + tag.getValue()).containsExactly("status=UNKNOWN");
        assertThat(repository.askedStatus.get()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(repository.calls.get()).isEqualTo(2);
    }

    @DisplayName("캐시 시간 안의 scrape는 DB를 다시 세지 않고 마지막 값을 쓴다.")
    @Test
    void reusesValue_withinCacheTtl() {
        // arrange
        CountingRepository repository = new CountingRepository(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new UnresolvedPaymentMetrics(repository, registry, Duration.ofMinutes(1));
        Gauge gauge = registry.get(UnresolvedPaymentMetrics.METRIC_NAME).gauge();

        // act
        double first = gauge.value();
        repository.unknown.set(9);
        double second = gauge.value();

        // assert
        assertThat(first).isEqualTo(2.0);
        assertThat(second).isEqualTo(2.0);
        assertThat(repository.calls.get()).isEqualTo(1);
    }

    @DisplayName("DB를 읽지 못하면 0으로 꾸미지 않고 NaN을 내보내며, 다음 scrape에서 다시 센다.")
    @Test
    void reportsNaN_whenCountFails() {
        // arrange
        CountingRepository repository = new CountingRepository(4);
        repository.fail.set(true);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new UnresolvedPaymentMetrics(repository, registry, Duration.ZERO);
        Gauge gauge = registry.get(UnresolvedPaymentMetrics.METRIC_NAME).gauge();

        // act
        double failed = gauge.value();
        repository.fail.set(false);
        double recovered = gauge.value();

        // assert
        assertThat(failed).isNaN();
        assertThat(recovered).isEqualTo(4.0);
    }

    private static final class CountingRepository implements PaymentAttemptRepository {
        private final AtomicLong unknown;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<PaymentAttemptStatus> askedStatus = new AtomicReference<>();
        private final AtomicBoolean fail = new AtomicBoolean();

        private CountingRepository(long unknown) {
            this.unknown = new AtomicLong(unknown);
        }

        @Override
        public long countByStatus(PaymentAttemptStatus status) {
            calls.incrementAndGet();
            askedStatus.set(status);
            if (fail.get()) {
                throw new IllegalStateException("DB 연결 실패");
            }
            return unknown.get();
        }

        @Override
        public PaymentAttemptModel save(PaymentAttemptModel attempt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PaymentAttemptModel> findByOrderId(Long orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PaymentAttemptModel> findByOrderIdForUpdate(Long orderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<PaymentAttemptModel> findByProviderOrderId(String providerOrderId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean existsByAttemptKey(String attemptKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<PaymentAttemptModel> findAllByOrderIds(Collection<Long> orderIds) {
            throw new UnsupportedOperationException();
        }
    }
}
