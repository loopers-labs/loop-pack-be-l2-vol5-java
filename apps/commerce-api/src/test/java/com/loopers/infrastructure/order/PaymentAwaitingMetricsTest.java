package com.loopers.infrastructure.order;

import com.loopers.domain.order.AwaitingPaymentCounts;
import com.loopers.domain.order.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/** commerce.payment.awaiting gauge: 태그 두 값, 갱신 전·실패 NaN, 갱신 시 DB 값. DB 는 OrderRepository mock (Spring 없음). */
class PaymentAwaitingMetricsTest {
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private SimpleMeterRegistry registry;
    private PaymentAwaitingMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PaymentAwaitingMetrics(orderRepository, registry);
    }

    private double value(String tag) {
        return registry.get(PaymentAwaitingMetrics.METRIC).tag(PaymentAwaitingMetrics.TAG, tag).gauge().value();
    }

    @DisplayName("gauge 는 pg_transaction 태그 known·missing 두 개뿐이고 설명이 있다. 다른 태그는 없다.")
    @Test
    void registersTwoBoundedGauges() {
        assertThat(registry.get(PaymentAwaitingMetrics.METRIC).gauges())
            .extracting(gauge -> gauge.getId().getTag(PaymentAwaitingMetrics.TAG))
            .containsExactlyInAnyOrder("known", "missing");
        for (Gauge gauge : registry.get(PaymentAwaitingMetrics.METRIC).gauges()) {
            assertThat(gauge.getId().getTags()).hasSize(1);
            assertThat(gauge.getId().getDescription()).isNotBlank();
        }
    }

    @DisplayName("첫 갱신 전에는 0 이 아니라 NaN 이다 (아직 센 적 없음).")
    @Test
    void nanBeforeFirstRefresh() {
        assertThat(value("known")).isNaN();
        assertThat(value("missing")).isNaN();
    }

    @DisplayName("갱신하면 DB 에서 센 값으로 바뀐다. 다시 갱신하면 새 값.")
    @Test
    void refreshesFromRepository() {
        given(orderRepository.countAwaitingPayment()).willReturn(new AwaitingPaymentCounts(2, 3), new AwaitingPaymentCounts(0, 0));

        metrics.refresh();
        assertThat(value("known")).isEqualTo(2.0);
        assertThat(value("missing")).isEqualTo(3.0);

        metrics.refresh();
        assertThat(value("known")).isZero();
        assertThat(value("missing")).isZero();
    }

    @DisplayName("갱신이 실패하면 이전 값을 남기지 않고 NaN 으로 둔다. 예외는 밖으로 내보내지 않는다.")
    @Test
    void nanWhenRefreshFails() {
        given(orderRepository.countAwaitingPayment())
            .willReturn(new AwaitingPaymentCounts(1, 1))
            .willThrow(new IllegalStateException("DB 연결 실패"));
        metrics.refresh();

        metrics.refresh();

        assertThat(value("known")).isNaN();
        assertThat(value("missing")).isNaN();
    }
}
