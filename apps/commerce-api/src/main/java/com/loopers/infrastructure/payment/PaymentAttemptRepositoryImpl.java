package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {

    private static final List<PaymentAttemptStatus> UNFINISHED =
        List.of(PaymentAttemptStatus.REQUESTED, PaymentAttemptStatus.PENDING, PaymentAttemptStatus.UNKNOWN);

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
    public Optional<PaymentAttempt> findByOrderIdForUpdate(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderIdForUpdate(orderId);
    }

    @Override
    public Optional<PaymentAttempt> findByAttemptKey(String attemptKey) {
        return paymentAttemptJpaRepository.findByAttemptKey(attemptKey);
    }

    @Override
    public List<PaymentAttempt> findAllByOrderIdIn(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return paymentAttemptJpaRepository.findAllByOrderIdIn(orderIds);
    }

    @Override
    public List<PaymentAttempt> findDueForCheck(ZonedDateTime now, int limit) {
        return paymentAttemptJpaRepository.findDueForCheck(UNFINISHED, PaymentAttemptStatus.FAILED, now, PageRequest.of(0, limit));
    }

    @Override
    public Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId) {
        return paymentAttemptJpaRepository.findByProviderOrderId(providerOrderId);
    }

    @Override
    public Optional<PaymentAttempt> findByTransactionKey(String transactionKey) {
        return paymentAttemptJpaRepository.findByTransactionKey(transactionKey);
    }
}
