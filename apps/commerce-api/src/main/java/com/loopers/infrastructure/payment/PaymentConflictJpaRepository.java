package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentConflict;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentConflictJpaRepository extends JpaRepository<PaymentConflict, Long> {

    List<PaymentConflict> findAllByAttemptIdOrderByIdAsc(Long attemptId);
}
