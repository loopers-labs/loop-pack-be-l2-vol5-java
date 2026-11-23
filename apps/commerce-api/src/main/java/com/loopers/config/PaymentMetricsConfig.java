package com.loopers.config;

import com.loopers.application.payment.PaymentStatusQuery;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 수집마다 현재 UNKNOWN 건수를 조회하며, 조회 실패 시 NaN 을 반환한다(checkout-incident §3).
 */
@Configuration
public class PaymentMetricsConfig {

    static final String UNKNOWN_GAUGE = "commerce.payment.unknown";
    static final String UNKNOWN_GAUGE_DESCRIPTION = "Current payment rows in UNKNOWN state; excludes PENDING";
    static final String QUERY_FAILED_CODE = "PAYMENT_UNKNOWN_QUERY_FAILED";

    /** singleton bean 으로 gauge 대상의 수명을 유지한다. */
    @Bean
    public PaymentUnknownGauge paymentUnknownGauge(PaymentStatusQuery paymentStatusQuery) {
        return new PaymentUnknownGauge(paymentStatusQuery);
    }

    @Slf4j
    public static final class PaymentUnknownGauge implements MeterBinder {

        private final PaymentStatusQuery query;

        PaymentUnknownGauge(PaymentStatusQuery query) {
            this.query = query;
        }

        @Override
        public void bindTo(MeterRegistry registry) {
            Gauge.builder(UNKNOWN_GAUGE, this, PaymentUnknownGauge::measure)
                .description(UNKNOWN_GAUGE_DESCRIPTION)
                .strongReference(true)
                .register(registry);
        }

        /** 수집 스레드에서 조회 서비스의 TX 프록시를 호출한다. */
        double measure() {
            try {
                return query.countUnknown();
            } catch (RuntimeException e) {
                log.warn("{} exception={}", QUERY_FAILED_CODE, e.getClass().getName());
                return Double.NaN;
            }
        }
    }
}
