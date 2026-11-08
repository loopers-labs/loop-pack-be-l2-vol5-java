package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Outbox 기록·시도 시각 전용 Clock(설계 W7.2-5). 사건의 confirmedAt(orderConfirmationClock)·BaseEntity 감사 시각과 분리해
 * 테스트가 따로 고정할 수 있게 한다. 이번 단위에서는 기록 서비스만 사용하며 relay 조립은 후속 단위다.
 */
@Configuration
public class OrderOutboxClockConfig {

    public static final String OUTBOX_RECORD_CLOCK = "outboxRecordClock";

    @Bean(OUTBOX_RECORD_CLOCK)
    Clock outboxRecordClock() {
        return Clock.systemUTC();
    }
}
