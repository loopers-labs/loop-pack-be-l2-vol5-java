package com.loopers.infrastructure.payment.persistence;

import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.enums.PaymentStatus;
import com.loopers.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentRepositoryImpl implements PaymentRepository {
    private final PaymentJpaRepository paymentJpaRepository;

    @Override
    public Payment save(Payment payment) {
        return paymentJpaRepository.save(payment);
    }

    @Override
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<Payment> findByAttemptKey(String attemptKey) {
        return paymentJpaRepository.findByAttemptKey(attemptKey);
    }

    @Override
    public Optional<Payment> findByProviderOrderId(String providerOrderId) {
        return paymentJpaRepository.findByProviderOrderId(providerOrderId);
    }

    @Override
    public List<Payment> findAllByOrderIds(Collection<Long> orderIds) {
        if (orderIds.isEmpty()) {
            return List.of();
        }
        return paymentJpaRepository.findAllByOrderIdIn(orderIds);
    }

    @Override
    public Map<PaymentStatus, Long> countUnresolvedByStatus() {
        List<PaymentStatus> unresolved = PaymentStatus.unresolved();
        Map<PaymentStatus, Long> counts = new EnumMap<>(PaymentStatus.class);
        unresolved.forEach(status -> counts.put(status, 0L));
        for (Object[] row : paymentJpaRepository.countByStatusIn(unresolved)) {
            counts.put((PaymentStatus) row[0], (Long) row[1]);
        }
        return counts;
    }
}
