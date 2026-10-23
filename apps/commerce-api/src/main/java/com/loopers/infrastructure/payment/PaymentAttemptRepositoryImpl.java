package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.order.OrderStatus;
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
    public List<PaymentAttempt> findByOrderIds(Collection<Long> orderIds) {
        return orderIds.isEmpty() ? List.of() : paymentAttemptJpaRepository.findByOrderIdIn(orderIds);
    }

    @Override
    public Optional<PaymentAttempt> findByProviderOrderId(String providerOrderId) {
        return paymentAttemptJpaRepository.findByProviderOrderId(providerOrderId);
    }

    @Override
    public List<PaymentAttempt> findUnfinishedCreatedBefore(ZonedDateTime cutoff, Long afterId, int limit) {
        return paymentAttemptJpaRepository.findByOrderStatusCreatedBefore(
            OrderStatus.PAYMENT_PENDING, cutoff, afterId, PageRequest.of(0, limit));
    }
}
