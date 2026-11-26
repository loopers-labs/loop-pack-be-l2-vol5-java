package com.loopers.infrastructure.payment.monitoring;

import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.PaymentStatus;
import com.loopers.domain.payment.repository.FakePaymentRepository;
import com.loopers.domain.payment.enums.ProviderTransactionStatus;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.vo.ProviderTransaction;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class UnresolvedPaymentMetricsTest {

    private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
    private static final ZonedDateTime AT = NOW.atZone(ZoneOffset.UTC);

    /** 갱신 실패를 켜고 끌 수 있는 저장소 */
    private static class SwitchableRepository extends FakePaymentRepository {
        private boolean failing;

        @Override
        public Map<PaymentStatus, Long> countUnresolvedByStatus() {
            if (failing) {
                throw new IllegalStateException("테스트에서 주입한 실패");
            }
            return super.countUnresolvedByStatus();
        }
    }

    private SwitchableRepository repository;
    private SimpleMeterRegistry registry;
    private UnresolvedPaymentMetrics metrics;
    private long orderSequence;

    @BeforeEach
    void setUp() {
        repository = new SwitchableRepository();
        registry = new SimpleMeterRegistry();
        metrics = new UnresolvedPaymentMetrics(repository, Clock.fixed(NOW, ZoneOffset.UTC), registry);
    }

    @DisplayName("갱신 전에는 세 상태 모두 0 이고 갱신 시각도 0 이다.")
    @Test
    void startsAtZero() {
        assertAll(
            () -> assertThat(unresolved(PaymentStatus.REQUESTED)).isZero(),
            () -> assertThat(unresolved(PaymentStatus.UNKNOWN)).isZero(),
            () -> assertThat(unresolved(PaymentStatus.PENDING)).isZero(),
            () -> assertThat(refreshed()).isZero()
        );
    }

    @DisplayName("갱신하면 미종결 상태별 수를 세고, 종결된 시도는 세지 않으며, 갱신 시각을 남긴다.")
    @Test
    void countsUnresolvedByStatus() {
        // arrange
        requested();
        unknown();
        unknown();
        pending();
        repository.save(Payment.notRequired(++orderSequence, "attempt-" + orderSequence));

        // act
        metrics.refresh();

        // assert
        assertAll(
            () -> assertThat(unresolved(PaymentStatus.REQUESTED)).isEqualTo(1),
            () -> assertThat(unresolved(PaymentStatus.UNKNOWN)).isEqualTo(2),
            () -> assertThat(unresolved(PaymentStatus.PENDING)).isEqualTo(1),
            () -> assertThat(refreshed()).isEqualTo(NOW.getEpochSecond())
        );
    }

    @DisplayName("미확인이 종결되면 다음 갱신에서 값이 줄고, 남은 시도가 없는 상태는 0 이 된다.")
    @Test
    void decreasesWhenResolved() {
        // arrange
        Payment payment = unknown();
        metrics.refresh();

        // act
        ProviderTransaction success = new ProviderTransaction(
            "TX-1", payment.getProviderOrderId(), payment.getAmount(), ProviderTransactionStatus.SUCCESS);
        payment.apply(new PaymentLookupResult.Found(List.of(success)), AT);
        metrics.refresh();

        // assert
        assertAll(
            () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCESS),
            () -> assertThat(unresolved(PaymentStatus.UNKNOWN)).isZero()
        );
    }

    @DisplayName("세지 못하면 이전 값과 갱신 시각을 그대로 둔다.")
    @Test
    void keepsPreviousValues_whenRefreshFails() {
        // arrange
        unknown();
        metrics.refresh();
        unknown();
        repository.failing = true;

        // act
        metrics.refresh();

        // assert
        assertAll(
            () -> assertThat(unresolved(PaymentStatus.UNKNOWN)).isEqualTo(1),
            () -> assertThat(refreshed()).isEqualTo(NOW.getEpochSecond())
        );
    }

    @DisplayName("미종결 gauge 는 세 상태 값만 가지는 status 태그 하나로 나뉜다. 개별 식별자를 태그로 쓰지 않는다.")
    @Test
    void usesOnlyBoundedStatusTag() {
        // act
        List<Meter> meters = registry.find(UnresolvedPaymentMetrics.UNRESOLVED).meters().stream().toList();

        // assert
        assertAll(
            () -> assertThat(meters).hasSize(3),
            () -> assertThat(meters).allSatisfy(meter -> assertThat(meter.getId().getTags())
                .extracting(tag -> tag.getKey()).containsExactly("status")),
            () -> assertThat(meters).extracting(meter -> meter.getId().getTag("status"))
                .containsExactlyInAnyOrder("REQUESTED", "UNKNOWN", "PENDING")
        );
    }

    private double unresolved(PaymentStatus status) {
        Gauge gauge = registry.find(UnresolvedPaymentMetrics.UNRESOLVED).tag("status", status.name()).gauge();
        assertThat(gauge).isNotNull();
        return gauge.value();
    }

    private double refreshed() {
        return registry.get(UnresolvedPaymentMetrics.REFRESHED).gauge().value();
    }

    private Payment requested() {
        orderSequence++;
        return repository.save(Payment.request(orderSequence, "attempt-" + orderSequence, 1_000L));
    }

    private Payment unknown() {
        Payment payment = requested();
        payment.apply(new PaymentCreateResult.Unknown(), AT);
        return payment;
    }

    private Payment pending() {
        Payment payment = requested();
        payment.apply(new PaymentCreateResult.Accepted("TX-" + payment.getOrderId()), AT);
        return payment;
    }
}
