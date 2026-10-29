package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 장부의 processed_at 을 찍는 시계 (설계 D10). 테스트에서 고정 Clock 으로 바꿀 수 있게 빈으로 둔다.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
