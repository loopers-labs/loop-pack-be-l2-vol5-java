package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "commerce.test-pg", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TestPgProperties.class)
public class TestPgConfiguration {
    @Bean(destroyMethod = "close")
    public CircuitBreakingPaymentGateway paymentGateway(ObjectMapper mapper, TestPgProperties properties) {
        PgHttpTimeouts timeouts = properties.timeouts();
        PgHttpTimeouts limits = PgHttpTimeouts.defaults();
        if (timeouts.createTotal().compareTo(limits.createTotal()) > 0
            || timeouts.lookupTotal().compareTo(limits.lookupTotal()) > 0) {
            throw new IllegalArgumentException("PG whole-call timeouts must fit the confirmation budget");
        }
        var client = new HttpTestPgClient(properties.baseUrl(), properties.callbackUrl(), mapper, timeouts);
        return new CircuitBreakingPaymentGateway(client, PgLookupCircuit.create());
    }
}
