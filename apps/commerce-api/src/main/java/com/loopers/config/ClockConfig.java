package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 현재 시각의 기준. 주문서 만료 · 결제 시각 판단에 쓰며, 테스트에서는 고정한 Clock 으로 바꿔 끼움 (설계 2.3).
 * 시간대는 기존 ZonedDateTime.now() 와 같은 시스템 시간대를 따름.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
