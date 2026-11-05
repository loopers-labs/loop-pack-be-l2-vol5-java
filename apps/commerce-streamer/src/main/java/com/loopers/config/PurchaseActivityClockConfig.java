package com.loopers.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PurchaseActivityClockConfig {

    @Bean
    Clock purchaseActivityClock() {
        return Clock.systemUTC();
    }
}
