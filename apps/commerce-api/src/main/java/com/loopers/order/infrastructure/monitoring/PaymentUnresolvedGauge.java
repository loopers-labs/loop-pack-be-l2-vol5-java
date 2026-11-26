package com.loopers.order.infrastructure.monitoring;

import com.loopers.order.domain.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class PaymentUnresolvedGauge {
    private static final String METRIC_NAME = "commerce.payment.unresolved";
    private static final double NOT_YET_OBSERVED = Double.NaN;

    private final OrderRepository orderRepository;
    private final AtomicReference<Double> lastSuccessfulCount = new AtomicReference<>(NOT_YET_OBSERVED);

    public PaymentUnresolvedGauge(OrderRepository orderRepository, MeterRegistry meterRegistry) {
        this.orderRepository = orderRepository;
        meterRegistry.config().meterFilter(new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                return METRIC_NAME.equals(id.getName()) ? id.replaceTags(java.util.List.of()) : id;
            }
        });
        Gauge.builder(METRIC_NAME, lastSuccessfulCount, PaymentUnresolvedGauge::currentValue)
            .register(meterRegistry);
    }

    @PostConstruct
    public void initializeSnapshot() {
        refreshSnapshot();
    }

    @Scheduled(fixedDelay = 5000)
    public void refreshSnapshot() {
        try {
            lastSuccessfulCount.set((double) orderRepository.countUnresolvedPaymentOrders());
        } catch (RuntimeException ignored) {
            // Keep the last successful database snapshot.
        }
    }

    private static double currentValue(AtomicReference<Double> value) {
        return value.get();
    }
}
