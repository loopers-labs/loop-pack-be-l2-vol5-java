package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.UnresolvedPaymentGroup;
import com.loopers.domain.payment.UnresolvedPayments;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
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

    @Override
    public List<UnresolvedPaymentGroup> summarizeUnresolved() {
        return paymentAttemptJpaRepository.summarizeByStatus(UnresolvedPayments.STATUSES);
    }
}
