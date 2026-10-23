package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentConflictModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentConflictJpaRepository extends JpaRepository<PaymentConflictModel, Long> {

    Optional<PaymentConflictModel> findByPaymentId(Long paymentId);
}
