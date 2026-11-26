package com.loopers.domains.order.config;

import com.loopers.domains.order.app.ReconcileBatchPolicy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PaymentReconcileBatchConfig {
    @Bean
    ReconcileBatchPolicy reconcileBatchPolicy(@Value("${payment.reconcile-batch.max-orders:50}") int maxOrders,
        @Value("${payment.reconcile-batch.interval:200ms}") Duration interval,
        @Value("${payment.reconcile-batch.consecutive-failure-limit:3}") int consecutiveFailureLimit) {
        return new ReconcileBatchPolicy(maxOrders, interval, consecutiveFailureLimit);
    }
}
