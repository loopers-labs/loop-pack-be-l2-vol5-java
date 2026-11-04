package com.loopers.config;

import com.loopers.application.order.OrderOutboxExecutionGate;
import com.loopers.application.order.OrderOutboxRelayProbe;
import com.loopers.application.order.OrderOutboxRetryPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 수동 relay·재개에 필요한 policy·공유 gate·기본 probe 조립(설계 W7.3-2). Clock 은 기존 outboxRecordClock 을 그대로 쓴다.
 * 전용 scheduler·lifecycle 종료 조립은 이 단위에 없다. enabled=true 로도 자동 발행이 시작되지 않는다.
 */
@Configuration
public class OrderOutboxRelayConfig {

    @Bean
    OrderOutboxRetryPolicy orderOutboxRetryPolicy(OrderOutboxRelayProperties properties) {
        return new OrderOutboxRetryPolicy(properties.initialRetryDelay(), properties.maxRetryDelay(), properties.maxCycleAttempts());
    }

    @Bean
    OrderOutboxExecutionGate orderOutboxExecutionGate() {
        return new OrderOutboxExecutionGate();
    }

    @Bean
    OrderOutboxRelayProbe orderOutboxRelayProbe() {
        return OrderOutboxRelayProbe.NO_OP;
    }
}
