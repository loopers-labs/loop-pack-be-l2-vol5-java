package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentService;
import com.loopers.domain.payment.PaymentStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PaymentUnresolvedMetrics {

    private static final List<PaymentStatus> UNRESOLVED =
        List.of(PaymentStatus.REQUESTED, PaymentStatus.PENDING, PaymentStatus.UNKNOWN, PaymentStatus.CONFLICT);

    private final PaymentService paymentService;
    private final Map<PaymentStatus, AtomicLong> counts = new EnumMap<>(PaymentStatus.class);
    private final Map<PaymentStatus, AtomicLong> oldestSeconds = new EnumMap<>(PaymentStatus.class);

    public PaymentUnresolvedMetrics(PaymentService paymentService, MeterRegistry meterRegistry) {
        this.paymentService = paymentService;
        for (PaymentStatus status : UNRESOLVED) {
            String tag = status.name().toLowerCase(Locale.ROOT);
            counts.put(status, gauge(meterRegistry, "payment.unresolved", tag, null));
            oldestSeconds.put(status, gauge(meterRegistry, "payment.unresolved.oldest", tag, "seconds"));
        }
    }

    private static AtomicLong gauge(MeterRegistry meterRegistry, String name, String status, String unit) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::get).tag("status", status).baseUnit(unit).register(meterRegistry);
        return value;
    }

    @Transactional(readOnly = true)
    public void refresh(Instant now) {
        Map<PaymentStatus, PaymentRepository.Unresolved> byStatus = paymentService.summarizeUnresolved().stream()
            .collect(Collectors.toMap(PaymentRepository.Unresolved::status, Function.identity()));
        for (PaymentStatus status : UNRESOLVED) {
            PaymentRepository.Unresolved unresolved = byStatus.get(status);
            counts.get(status).set(unresolved == null ? 0 : unresolved.count());
            oldestSeconds.get(status).set(unresolved == null
                ? 0
                : Math.max(0, Duration.between(unresolved.oldestRequestedAt(), now).toSeconds()));
        }
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "payment.unresolved-metrics.enabled", havingValue = "true")
    @RequiredArgsConstructor
    static class Schedule {

        private final PaymentUnresolvedMetrics metrics;

        @Scheduled(fixedDelayString = "${payment.unresolved-metrics.interval:30s}")
        void refresh() {
            metrics.refresh(Instant.now());
        }
    }
}
