package com.loopers.application.ordering.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class WeeklyScenarioConfirmationTime {
    @Bean
    @Primary
    OrderConfirmationRecordFactory scenarioConfirmationRecords() {
        // UTC 월요일 자정을 지나도 같은 주의 구매 20건을 집계한다. PG와 지표의 시계는 실제 시간으로 흐른다.
        return new OrderConfirmationRecordFactory(Clock.fixed(Instant.now(), ZoneOffset.UTC), UUID::randomUUID);
    }
}
