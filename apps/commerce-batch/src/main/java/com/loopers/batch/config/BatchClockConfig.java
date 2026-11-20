package com.loopers.batch.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** 입력 고정 시각·공개 시각·기간 종료 판단의 시계. 테스트는 고정 시계로 바꾼다. */
@Configuration
public class BatchClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
