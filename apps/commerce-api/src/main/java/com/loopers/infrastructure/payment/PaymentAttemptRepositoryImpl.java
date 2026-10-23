package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentRequestConflictException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {
    private final PaymentAttemptJpaRepository jpaRepository;

    public PaymentAttemptRepositoryImpl(PaymentAttemptJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        try {
            return jpaRepository.save(attempt);
        } catch (DataIntegrityViolationException failure) {
            // Only the UUID unique constraint proves a conflicting request. Keep other storage/lock errors technical.
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation) {
                    String constraint = violation.getConstraintName();
                    if ("uk_payment_attempt_key".equals(constraint)
                        || "payment_attempt.uk_payment_attempt_key".equals(constraint)) {
                        throw new PaymentRequestConflictException();
                    }
                }
            }
            throw failure;
        }
    }

    @Override
    public Optional<PaymentAttempt> findByOrderId(long orderId) {
        return jpaRepository.findByOrderId(orderId);
    }

    @Override
    public List<PaymentAttempt> findByOrderIds(List<Long> orderIds) {
        return orderIds.isEmpty() ? List.of() : jpaRepository.findByOrderIdIn(orderIds);
    }

    @Override
    public Optional<PaymentAttempt> findByAttemptKey(UUID attemptKey) {
        return jpaRepository.findByAttemptKey(attemptKey);
    }

    @Override
    public Optional<PaymentAttempt> findByProviderOrderId(String account, String providerOrderId) {
        return jpaRepository.findByProviderAccountAndProviderOrderId(account, providerOrderId);
    }

    @Override
    public Optional<PaymentAttempt> findByTransactionKey(String account, String transactionKey) {
        return jpaRepository.findByProviderAccountAndTransactionKey(account, transactionKey);
    }

    @Override
    public Optional<PaymentAttempt> lockByOrderId(long orderId) {
        return jpaRepository.lockByOrderId(orderId);
    }
}
