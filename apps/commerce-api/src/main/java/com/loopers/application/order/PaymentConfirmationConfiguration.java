package com.loopers.application.order;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class PaymentConfirmationConfiguration {
    /** Stored business timestamps only; elapsed-time budgets keep their monotonic timing source. */
    @Bean
    public Clock orderPaymentClock() {
        return Clock.systemUTC();
    }

    @Bean
    public PaymentConfirmationTiming paymentConfirmationTiming() {
        return new PaymentConfirmationTiming() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void sleep(Duration duration) throws InterruptedException {
                TimeUnit.NANOSECONDS.sleep(duration.toNanos());
            }

            @Override
            public double jitterFactor() {
                return ThreadLocalRandom.current().nextDouble(0.8, 1.2);
            }
        };
    }

    @Bean
    public PaymentConfirmationWaiter paymentConfirmationWaiter(PaymentConfirmationTiming timing) {
        return new PaymentConfirmationWaiter(timing);
    }
}
