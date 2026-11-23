package com.loopers.config;

import com.loopers.application.payment.PaymentStatusQuery;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 현재 UNKNOWN 결제 수 gauge(checkout-incident §3). 수집할 때마다 DB 를 읽고 값·초기 0·직전 값을 보관하지 않는다.
 * 조회 실패는 0 이 아니라 NaN(모름)이며, 정적 오류 코드와 예외 클래스만 기록한다.
 */
@Configuration
public class PaymentMetricsConfig {

    static final String UNKNOWN_GAUGE = "commerce.payment.unknown";
    static final String UNKNOWN_GAUGE_DESCRIPTION = "Current payment rows in UNKNOWN state; excludes PENDING";
    static final String QUERY_FAILED_CODE = "PAYMENT_UNKNOWN_QUERY_FAILED";

    /** Boot 가 애플리케이션 registry 마다 한 번 bindTo 한다. singleton bean 이 gauge 대상의 강한 참조를 유지한다. */
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

        /** 수집 스레드에서 Spring 프록시를 통해 조회한다. Error 는 잡지 않는다. */
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
