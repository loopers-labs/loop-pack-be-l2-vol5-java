package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentDiscrepancy;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentDiscrepancyJpaRepository extends JpaRepository<PaymentDiscrepancyEntity, Long> {

    boolean existsByPaymentIdAndReasonAndObservedTransactionKeyAndObservedStatus(
        Long paymentId, PaymentDiscrepancy.Reason reason, String observedTransactionKey, String observedStatus);
}
