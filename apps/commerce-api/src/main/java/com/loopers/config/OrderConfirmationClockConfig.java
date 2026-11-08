package com.loopers.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 최초 PG 확정 시각 전용 Clock(설계 W7.4-1). W6 의 OrderEventKafkaConfig 에 있던 조립을 이름·값 그대로 옮긴 중립 설정이다.
 * 테스트는 이 bean 만 고정 Clock 으로 바꾸며, 다른 생성·감사 시각과 Outbox 기록 시각(outboxRecordClock)은 이 Clock 을 쓰지 않는다.
 */
@Configuration
public class OrderConfirmationClockConfig {

    public static final String ORDER_CONFIRMATION_CLOCK = "orderConfirmationClock";

    @Bean(ORDER_CONFIRMATION_CLOCK)
    Clock orderConfirmationClock() {
        return Clock.systemUTC();
    }
}
