package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;

    @Override
    public PaymentAttemptModel save(PaymentAttemptModel attempt) {
        return paymentAttemptJpaRepository.save(attempt);
    }

    @Override
    public Optional<PaymentAttemptModel> findByOrderId(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<PaymentAttemptModel> findByOrderIdForUpdate(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderIdForUpdate(orderId);
    }

    @Override
    public Optional<PaymentAttemptModel> findByProviderOrderId(String providerOrderId) {
        return paymentAttemptJpaRepository.findByProviderOrderId(providerOrderId);
    }

    @Override
    public boolean existsByAttemptKey(String attemptKey) {
        return paymentAttemptJpaRepository.existsByAttemptKey(attemptKey);
    }

    @Override
    public List<PaymentAttemptModel> findAllByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return paymentAttemptJpaRepository.findAllByOrderIdIn(orderIds);
    }
}
