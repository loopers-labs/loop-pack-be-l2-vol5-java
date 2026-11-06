package com.loopers.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Outbox relay를 켠 인스턴스에서만 스케줄링을 켠다. 기본 스케줄러는 스레드 하나라 회차가 겹치지 않는다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "order-outbox.relay.scheduling-enabled", havingValue = "true")
public class SchedulingConfig {
}
