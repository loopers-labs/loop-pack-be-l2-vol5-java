package com.loopers.application.payment;

import jakarta.persistence.EntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class PaymentTxTestConfig {

    @Bean
    @Primary
    public ControllableProbe controllableProbe(EntityManager entityManager) {
        return new ControllableProbe(entityManager);
    }
}
