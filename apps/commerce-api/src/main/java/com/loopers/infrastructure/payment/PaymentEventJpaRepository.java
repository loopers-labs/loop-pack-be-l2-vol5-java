package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventJpaRepository extends JpaRepository<PaymentEvent, Long> {
}
