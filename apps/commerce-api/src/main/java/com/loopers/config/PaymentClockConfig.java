package com.loopers.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentClockConfig {
    @Bean
    Clock paymentClock() {
        return Clock.systemUTC();
    }
}
