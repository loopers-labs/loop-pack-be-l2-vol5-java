package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UnknownPaymentGauge implements MeterBinder {
    private final PaymentJpaRepository payments;

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("commerce.payment.unknown", payments, repository -> repository.countByStatus(PaymentStatus.UNKNOWN))
                .description("Payments in UNKNOWN status in the commerce database at scrape time")
                .register(registry);
    }
}
