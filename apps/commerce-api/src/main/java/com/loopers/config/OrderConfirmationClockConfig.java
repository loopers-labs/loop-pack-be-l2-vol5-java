package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class OrderConfirmationClockConfig {
    @Bean
    public Clock orderConfirmationClock() {
        return Clock.systemUTC();
    }
}
