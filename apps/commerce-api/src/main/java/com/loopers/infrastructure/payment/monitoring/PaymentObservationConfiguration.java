package com.loopers.infrastructure.payment.monitoring;

import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "commerce.payment-observation.enabled", havingValue = "true", matchIfMissing = true)
class PaymentObservationConfiguration {
    @Bean
    UnknownPaymentGauge unknownPaymentGauge(PaymentAttemptJpaRepository attempts, MeterRegistry registry,
            @Value(UnknownPaymentGauge.REFRESH_INTERVAL) String interval) {
        return new UnknownPaymentGauge(attempts, registry, DurationStyle.detectAndParse(interval));
    }

    @Bean
    ThreadPoolTaskScheduler paymentObservationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("payment-observation-");
        return scheduler;
    }
}
