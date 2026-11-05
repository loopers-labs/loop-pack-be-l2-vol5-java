package com.loopers.config;

import com.loopers.outbox.domain.OrderOutboxBackoff;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

/**
 * relay가 쓰는 시계와 backoff (docs/week7/plan.md D-0 lease 책임 나눔, D-3). 테스트는 둘 다 고정한 것으로 바꾼다.
 */
@Configuration
public class OrderOutboxRelayConfig {

    /** 현재 시각은 앱이 정한다(DB NOW()가 아니다). 단일 relay라 서버 간 시계 차이가 없다 */
    @Bean
    Clock relayClock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    OrderOutboxBackoff orderOutboxBackoff() {
        return new OrderOutboxBackoff(() -> ThreadLocalRandom.current().nextDouble());
    }
}
