package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository paymentJpaRepository;

    @Override
    public Optional<PaymentModel> findByOrderId(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId);
    }

    @Override
    public boolean existsByAttemptKey(String attemptKey) {
        return paymentJpaRepository.existsByAttemptKey(attemptKey);
    }

    @Override
    public List<Long> findOrderIdsByProviderOrderId(String provider, String providerAccount, String providerOrderId) {
        return paymentJpaRepository.findOrderIdsByProviderOrderId(provider, providerAccount, providerOrderId);
    }

    @Override
    public List<PaymentModel> findAllByOrderIds(Collection<Long> orderIds) {
        return paymentJpaRepository.findAllByOrderIdIn(orderIds);
    }

    @Override
    public PaymentModel save(PaymentModel payment) {
        return paymentJpaRepository.save(payment);
    }
}
