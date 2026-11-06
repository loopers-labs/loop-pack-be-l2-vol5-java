package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentDlqModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentDlqJpaRepository extends JpaRepository<PaymentDlqModel, Long> {
    List<PaymentDlqModel> findAllByResolved(boolean resolved);
}
