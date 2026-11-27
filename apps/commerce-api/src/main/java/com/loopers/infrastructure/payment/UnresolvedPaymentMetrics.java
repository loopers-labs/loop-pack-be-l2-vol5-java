package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 결과를 확인하지 못한 결제 시도 수를 업무 지표로 내보낸다 (W10 과제 2절).
 *
 * <p>{@value #METRIC_NAME}{@code {status="UNKNOWN"}}: 지금 DB에 {@link PaymentAttemptStatus#UNKNOWN}으로 남은 결제 시도 수.
 * 처리 중인 정상 결제(PENDING·REQUESTED)는 세지 않는다. 고객은 "결제 확인 중"을 보고 있는데 기존 거래 조회로 마무리해야 하는 주문의 수다.
 *
 * <ul>
 *   <li>값은 scrape 시점에 DB를 세어 만든다. 별도 스케줄러에 기대지 않는다. 이 앱의 스케줄링은 relay를 켠 인스턴스에서만 켜지기 때문이다.</li>
 *   <li>scrape마다 DB를 세지 않도록 {@code payment.metrics.unresolved.cache-ttl} 동안 마지막 값을 다시 쓴다. 그만큼 늦게 보일 수 있다.</li>
 *   <li>tag는 유한한 상태 하나뿐이다. 주문·사용자·추적 id는 tag로 만들지 않고 구조화 로그에서 찾는다.</li>
 *   <li>DB를 읽지 못하면 값을 꾸미지 않고 NaN으로 내보낸다. 0은 "미확인 주문이 없다"는 뜻이라 대신 쓸 수 없다.</li>
 * </ul>
 */
@Slf4j
@Component
public class UnresolvedPaymentMetrics {

    public static final String METRIC_NAME = "commerce.payment.unresolved";

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final long cacheTtlNanos;
    private final Object lock = new Object();
    private double cachedValue = Double.NaN;
    private long cachedAtNanos;
    private boolean cached;

    public UnresolvedPaymentMetrics(
        PaymentAttemptRepository paymentAttemptRepository,
        MeterRegistry meterRegistry,
        @Value("${payment.metrics.unresolved.cache-ttl:5s}") Duration cacheTtl
    ) {
        this.paymentAttemptRepository = paymentAttemptRepository;
        this.cacheTtlNanos = cacheTtl.toNanos();
        Gauge.builder(METRIC_NAME, this, UnresolvedPaymentMetrics::unknownCount)
            .description("결과 미확인(UNKNOWN) 결제 시도 수. DB 기준이며 cache-ttl만큼 늦을 수 있다. 처리 중(PENDING)은 포함하지 않는다")
            .tag("status", PaymentAttemptStatus.UNKNOWN.name())
            .register(meterRegistry);
    }

    /** 캐시가 살아 있으면 마지막 값을, 아니면 DB를 새로 센 값을 돌려준다. */
    double unknownCount() {
        synchronized (lock) {
            long now = System.nanoTime();
            if (cached && now - cachedAtNanos < cacheTtlNanos) {
                return cachedValue;
            }
            try {
                cachedValue = paymentAttemptRepository.countByStatus(PaymentAttemptStatus.UNKNOWN);
            } catch (RuntimeException e) {
                log.warn("미확인 결제 수를 세지 못했다. 지표를 NaN으로 내보낸다. error={}", e.getClass().getSimpleName());
                cachedValue = Double.NaN;
            }
            cachedAtNanos = now;
            cached = true;
            return cachedValue;
        }
    }
}
