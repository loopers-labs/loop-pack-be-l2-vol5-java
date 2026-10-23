package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.ProviderOrderId;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentJpaRepository paymentJpaRepository;

    @Override
    public Payment save(Payment payment) {
        if (payment.getId() == null) {
            return paymentJpaRepository.saveAndFlush(PaymentEntity.from(payment)).toDomain();
        }

        PaymentEntity entity = paymentJpaRepository.findById(payment.getId())
            .orElseThrow(() -> new IllegalStateException(
                "저장할 결제 행이 없습니다: id=" + payment.getId()));
        entity.apply(payment);
        return entity.toDomain();
    }

    @Override
    public Optional<Payment> findByAttemptKey(PaymentAttemptKey attemptKey) {
        return paymentJpaRepository.findByAttemptKey(attemptKey.value()).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByProviderOrderId(ProviderOrderId providerOrderId) {
        return paymentJpaRepository.findByProviderOrderId(providerOrderId.value()).map(PaymentEntity::toDomain);
    }

    @Override
    public Optional<Long> findOrderIdById(Long paymentId) {
        return paymentJpaRepository.findOrderIdById(paymentId);
    }

    @Override
    public Optional<Payment> findByIdForUpdate(Long paymentId) {
        return paymentJpaRepository.findByIdForUpdate(paymentId).map(PaymentEntity::toDomain);
    }

    @Override
    public List<Payment> findDueForCheckSkippingLocked(Instant requestedBefore, Instant checkedBefore, int limit) {
        return paymentJpaRepository.findDueForCheck(
                List.of(PaymentStatus.REQUESTED, PaymentStatus.PENDING, PaymentStatus.UNKNOWN),
                requestedBefore, checkedBefore, PageRequest.of(0, limit)).stream()
            .map(PaymentEntity::toDomain)
            .toList();
    }
}
