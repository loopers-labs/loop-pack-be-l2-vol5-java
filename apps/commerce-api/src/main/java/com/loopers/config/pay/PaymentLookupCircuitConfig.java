package com.loopers.config.pay;

import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.policy.PaymentLookupPolicy;
import com.loopers.infrastructure.resilience.pay.CircuitPaymentLookupGateway;
import com.loopers.infrastructure.resilience.pay.IgnoredPaymentLookupException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.common.circuitbreaker.configuration.CircuitBreakerConfigCustomizer;
import io.github.resilience4j.core.registry.EntryAddedEvent;
import io.github.resilience4j.core.registry.EntryRemovedEvent;
import io.github.resilience4j.core.registry.EntryReplacedEvent;
import io.github.resilience4j.core.registry.RegistryEventConsumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class PaymentLookupCircuitConfig {
    @Bean
    public CircuitBreakerConfigCustomizer paymentLookupCircuitClassifier() {
        return CircuitBreakerConfigCustomizer.of(CircuitPaymentLookupGateway.CIRCUIT_NAME, builder -> builder
            .recordResult(result -> result instanceof PaymentObservation observation
                && PaymentLookupPolicy.circuit(observation) == PaymentLookupPolicy.Circuit.FAILURE)
            .ignoreExceptions(IgnoredPaymentLookupException.class));
    }

    @Bean
    public RegistryEventConsumer<CircuitBreaker> paymentLookupCircuitEvents() {
        return new RegistryEventConsumer<>() {
            @Override
            public void onEntryAddedEvent(EntryAddedEvent<CircuitBreaker> event) { listen(event.getAddedEntry()); }

            @Override
            public void onEntryRemovedEvent(EntryRemovedEvent<CircuitBreaker> event) {}

            @Override
            public void onEntryReplacedEvent(EntryReplacedEvent<CircuitBreaker> event) { listen(event.getNewEntry()); }

            private void listen(CircuitBreaker circuit) {
                if (CircuitPaymentLookupGateway.CIRCUIT_NAME.equals(circuit.getName())) {
                    circuit.getEventPublisher().onStateTransition(event -> log.info("PG 조회 회로 전이. circuit={} transition={}",
                        circuit.getName(), event.getStateTransition()));
                }
            }
        };
    }
}
