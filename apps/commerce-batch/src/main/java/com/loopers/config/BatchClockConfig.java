package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 회차의 frozen_at·공개의 published_at 을 찍는 시계 (설계 D12). 테스트에서 고정 Clock 으로 바꿀 수 있게 빈으로 둔다.
 * streamer 의 ClockConfig 와 같은 패턴이며, crossAppTest 에서 두 jar 가 한 classpath 에 올라도 FQCN 이 겹치지 않도록 이름만 다르게 둔다.
 */
@Configuration
public class BatchClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
