package com.loopers.infrastructure.payment;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PgProperties.class)
public class PgClientConfig {

    /** PG 조회 회로를 담는다. 테스트가 이 registry에서 회로 상태를 읽고, 테스트 사이에 초기화한다. */
    @Bean
    public CircuitBreakerRegistry paymentCircuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }
}
