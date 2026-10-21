package com.loopers.payment.adapter.out.pg;

import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.application.port.out.PgTransaction;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 로컬 테스트 PG 어댑터 (plan.md 7장). Red 단계 뼈대.
 */
@Component
public class LocalPgPaymentGatewayAdapter implements PaymentGatewayPort {

    public LocalPgPaymentGatewayAdapter(PgProperties properties, CircuitBreakerRegistry circuitBreakerRegistry, RetryRegistry retryRegistry) {
    }

    @Override
    public PgTransaction request(PgPaymentRequest request) {
        throw new UnsupportedOperationException("아직 구현하지 않았습니다");
    }

    @Override
    public Optional<PgTransaction> findByTransactionKey(String transactionKey) {
        throw new UnsupportedOperationException("아직 구현하지 않았습니다");
    }

    @Override
    public List<PgTransaction> findByProviderOrderId(String providerOrderId) {
        throw new UnsupportedOperationException("아직 구현하지 않았습니다");
    }
}
