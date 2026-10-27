package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 업무 시각의 원천. 지금은 주문 확정 시각(confirmedAt)만 이 Clock 을 쓴다. 테스트는 고정 Clock 으로 바꿔 재현한다. */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
