package com.loopers.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * batch가 기록하는 시각(입력 고정 시점, created_at·updated_at)의 기준. DB에는 UTC로 넣는다 (W9 D-2).
 * 테스트는 고정 clock을 @Primary로 둔다.
 */
@Configuration
public class BatchClockConfig {

    @Bean
    public Clock batchClock() {
        return Clock.systemUTC();
    }
}
