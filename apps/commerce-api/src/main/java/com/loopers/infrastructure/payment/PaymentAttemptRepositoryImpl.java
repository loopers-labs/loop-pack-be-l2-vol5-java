package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {

    private final PaymentAttemptJpaRepository jpaRepository;

    @Override
    public PaymentAttemptModel save(PaymentAttemptModel attempt) {
        return jpaRepository.save(attempt);
    }

    @Override
    public Optional<PaymentAttemptModel> findByOrderId(Long orderId) {
        return jpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<PaymentAttemptModel> findByPaymentAttemptKey(String key) {
        return jpaRepository.findByPaymentAttemptKey(key);
    }

    @Override
    public List<PaymentAttemptModel> findPendingBefore(ZonedDateTime sentBefore) {
        return jpaRepository.findAllByStatusAndSentAtBefore(PaymentAttemptStatus.PENDING, sentBefore);
    }

    @Override
    public List<PaymentAttemptModel> findUnknownBefore(ZonedDateTime sentBefore) {
        return jpaRepository.findAllByStatusAndSentAtBefore(PaymentAttemptStatus.UNKNOWN, sentBefore);
    }
}
