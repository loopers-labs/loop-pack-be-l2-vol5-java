package com.loopers.payment.adapter.out.persistence;

import com.loopers.payment.domain.PaymentOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentOutboxJpaRepository extends JpaRepository<PaymentOutbox, Long> {
}
