package com.loopers.infrastructure.payment;

import com.loopers.application.payment.PaymentGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgLookupPolicyProperties.class)
@ConditionalOnProperty(prefix = "payment.pg.lookup-circuit-breaker", name = "enabled", havingValue = "true")
public class PgLookupPolicyConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService pgLookupExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean("pgLookupDelegate")
    public PaymentGateway pgLookupDelegate(TestPgPaymentGateway http, PgLookupPolicyProperties properties,
                                           @Qualifier("pgLookupExecutor") ExecutorService pgLookupExecutor) {
        return new ResilientPgLookupGateway(http, properties, pgLookupExecutor);
    }
}
