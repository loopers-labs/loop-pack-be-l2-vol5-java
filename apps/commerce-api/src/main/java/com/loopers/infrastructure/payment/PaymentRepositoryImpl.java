package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Repository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Transactional
public class PaymentRepositoryImpl implements PaymentRepository {
    private final PaymentJpaRepository repository;

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity entity = repository.findByOrderId(payment.orderId())
                .orElseGet(() -> new PaymentJpaEntity(payment));
        entity.update(payment);
        try {
            return repository.saveAndFlush(entity).toDomain();
        } catch (DataIntegrityViolationException exception) {
            throw new CoreException(ErrorType.CONFLICT);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByOrderId(long orderId) {
        return repository.findByOrderId(orderId).map(PaymentJpaEntity::toDomain);
    }

    @Override
    public Optional<Payment> findByOrderIdForUpdate(long orderId) {
        return repository.findByOrderIdForUpdate(orderId).map(PaymentJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByProviderOrderId(String providerOrderId) {
        return repository.findByProviderOrderId(providerOrderId).map(PaymentJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Payment> findByPaymentAttemptKey(UUID paymentAttemptKey) {
        return repository.findByPaymentAttemptKey(paymentAttemptKey.toString()).map(PaymentJpaEntity::toDomain);
    }
}
