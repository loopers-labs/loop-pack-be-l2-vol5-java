package com.loopers.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

// 업무 시각을 한 곳에서 제공하고 테스트가 고정할 수 있게 한다. 순간은 UTC로만 다룬다.
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
