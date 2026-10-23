package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentConflictModel;
import com.loopers.domain.payment.PaymentConflictRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentConflictRepositoryImpl implements PaymentConflictRepository {

    private final PaymentConflictJpaRepository paymentConflictJpaRepository;

    @Override
    public Optional<PaymentConflictModel> findByPaymentId(Long paymentId) {
        return paymentConflictJpaRepository.findByPaymentId(paymentId);
    }

    @Override
    public PaymentConflictModel save(PaymentConflictModel conflict) {
        return paymentConflictJpaRepository.save(conflict);
    }
}
