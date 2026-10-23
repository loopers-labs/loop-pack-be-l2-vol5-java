package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        return paymentAttemptJpaRepository.save(attempt);
    }

    @Override
    public Optional<PaymentAttempt> findByOrderId(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<PaymentAttempt> findByAttemptKey(String attemptKey) {
        return paymentAttemptJpaRepository.findByAttemptKey(attemptKey);
    }

    @Override
    public Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId) {
        return paymentAttemptJpaRepository.findByProviderOrderId(providerOrderId);
    }
}
