package com.loopers.interfaces.scheduler;

import com.loopers.application.payment.PaymentMonitoringFacade;
import com.loopers.application.payment.dto.UnresolvedPaymentInfo;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.UnresolvedPayments;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 미종결 결제 수와 가장 오래 기다린 시도의 대기시간을 상태별 gauge 로 내보낸다. DB 를 주기적으로 읽어 갱신하므로 값은
 * 최대 갱신 주기만큼 늦다. label 은 유한한 status 뿐이고 주문 식별자는 넣지 않는다.
 */
@Component
public class UnresolvedPaymentMetrics {

    static final String COUNT = "commerce.payment.unresolved";
    static final String OLDEST_AGE = "commerce.payment.unresolved.oldest.age.seconds";

    private final PaymentMonitoringFacade paymentMonitoringFacade;
    private final Map<PaymentAttemptStatus, AtomicLong> counts = new EnumMap<>(PaymentAttemptStatus.class);
    private final Map<PaymentAttemptStatus, AtomicLong> oldestAges = new EnumMap<>(PaymentAttemptStatus.class);

    public UnresolvedPaymentMetrics(PaymentMonitoringFacade paymentMonitoringFacade, MeterRegistry meterRegistry) {
        this.paymentMonitoringFacade = paymentMonitoringFacade;
        for (PaymentAttemptStatus status : UnresolvedPayments.STATUSES) {
            // 건수는 단위를 두지 않는다. 단위를 주면 Prometheus 이름에 접미사가 붙어 알람 규칙의 이름과 달라진다.
            counts.put(status, register(meterRegistry, COUNT, "미종결 결제 시도 수", null, status));
            oldestAges.put(status, register(meterRegistry, OLDEST_AGE, "가장 오래된 미종결 결제 시도의 대기시간", "seconds", status));
        }
    }

    @Scheduled(fixedDelayString = "${payment.metrics.unresolved-refresh-interval}")
    public void refresh() {
        for (UnresolvedPaymentInfo info : paymentMonitoringFacade.unresolved()) {
            counts.get(info.status()).set(info.count());
            oldestAges.get(info.status()).set(info.oldestAgeSeconds());
        }
    }

    private static AtomicLong register(MeterRegistry registry, String name, String description, String unit, PaymentAttemptStatus status) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::get)
            .description(description)
            .baseUnit(unit)
            .tag("status", status.name())
            .register(registry);
        return value;
    }
}
