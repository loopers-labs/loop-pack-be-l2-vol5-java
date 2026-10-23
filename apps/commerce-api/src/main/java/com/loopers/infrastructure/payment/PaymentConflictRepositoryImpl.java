package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentConflict;
import com.loopers.domain.payment.PaymentConflictRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class PaymentConflictRepositoryImpl implements PaymentConflictRepository {

    private final PaymentConflictJpaRepository paymentConflictJpaRepository;

    @Override
    public PaymentConflict save(PaymentConflict conflict) {
        return paymentConflictJpaRepository.save(conflict);
    }

    @Override
    public List<PaymentConflict> findAllByAttemptId(Long attemptId) {
        return paymentConflictJpaRepository.findAllByAttemptIdOrderByIdAsc(attemptId);
    }
}
