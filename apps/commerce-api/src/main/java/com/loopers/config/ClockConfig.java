package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 주문 확정 시각의 기준. 테스트는 고정 시각의 Clock으로 바꿔 확정 시각을 재현할 수 있게 한다. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
