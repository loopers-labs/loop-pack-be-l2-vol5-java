package com.loopers.infrastructure.payment.monitoring;

import com.loopers.domain.payment.enums.PaymentStatus;
import com.loopers.domain.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 미종결 결제 시도 수를 상태별 gauge 로 노출함 (10주차 D-201, D-202).
 * 수집마다 DB 를 읽지 않도록 주기적으로 센 값을 들고 있음. 값은 DB 전체 기준이라 인스턴스 사이에서는 max 로 모음
 */
@Slf4j
@ConditionalOnProperty(name = "payment.metrics.unresolved.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class UnresolvedPaymentMetrics {

    static final String UNRESOLVED = "commerce.payment.unresolved";
    static final String REFRESHED = "commerce.payment.unresolved.refreshed";

    private final PaymentRepository paymentRepository;
    private final Clock clock;
    private final Map<PaymentStatus, AtomicLong> counts = new EnumMap<>(PaymentStatus.class);
    /** 한 번도 성공하지 않았으면 0. 값이 얼마나 오래됐는지 보는 기준 */
    private final AtomicLong refreshedEpochSeconds = new AtomicLong();

    public UnresolvedPaymentMetrics(PaymentRepository paymentRepository, Clock clock, MeterRegistry registry) {
        this.paymentRepository = paymentRepository;
        this.clock = clock;
        for (PaymentStatus status : PaymentStatus.unresolved()) {
            AtomicLong count = new AtomicLong();
            counts.put(status, count);
            Gauge.builder(UNRESOLVED, count, AtomicLong::get)
                .description("종결되지 않은 PG 결제 시도 수 (DB 기준, 갱신 주기만큼 늦을 수 있음. 인스턴스 사이는 max)")
                .tag("status", status.name())
                .register(registry);
        }
        Gauge.builder(REFRESHED, refreshedEpochSeconds, AtomicLong::get)
            .description("미종결 결제 수를 마지막으로 성공해 센 시각 (epoch 초, 0 이면 아직 없음)")
            .register(registry);
    }

    /** 실패하면 이전 값을 그대로 둠. 갱신 시각이 멈춰 오래된 값임을 알 수 있음 */
    @Scheduled(fixedDelayString = "${payment.metrics.unresolved.refresh-ms:15000}")
    public void refresh() {
        Map<PaymentStatus, Long> current;
        try {
            current = paymentRepository.countUnresolvedByStatus();
        } catch (RuntimeException e) {
            log.warn("미종결 결제 수를 세지 못해 이전 값을 유지함. error={}", e.getClass().getSimpleName());
            return;
        }
        counts.forEach((status, count) -> count.set(current.getOrDefault(status, 0L)));
        refreshedEpochSeconds.set(clock.instant().getEpochSecond());
    }
}
