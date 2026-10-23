package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.PaymentDiscrepancyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class PaymentDiscrepancyRepositoryImpl implements PaymentDiscrepancyRepository {

    private final PaymentDiscrepancyJpaRepository paymentDiscrepancyJpaRepository;

    @Override
    public void record(Long paymentId, PaymentDiscrepancy discrepancy, ObservationSource source, Instant observedAt) {
        boolean recorded = paymentDiscrepancyJpaRepository
            .existsByPaymentIdAndReasonAndObservedTransactionKeyAndObservedStatus(
                paymentId, discrepancy.reason(),
                PaymentDiscrepancyEntity.keyOf(discrepancy), PaymentDiscrepancyEntity.statusOf(discrepancy));
        if (!recorded) {
            paymentDiscrepancyJpaRepository.saveAndFlush(
                PaymentDiscrepancyEntity.of(paymentId, discrepancy, source, observedAt));
        }
    }
}
