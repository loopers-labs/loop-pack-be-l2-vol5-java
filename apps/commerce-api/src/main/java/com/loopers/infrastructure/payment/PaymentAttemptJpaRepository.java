package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttemptModel;
import com.loopers.domain.payment.PaymentAttemptStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentAttemptJpaRepository extends JpaRepository<PaymentAttemptModel, Long> {
    Optional<PaymentAttemptModel> findByOrderId(Long orderId);

    Optional<PaymentAttemptModel> findByPaymentAttemptKey(String paymentAttemptKey);

    List<PaymentAttemptModel> findAllByStatusAndSentAtBefore(PaymentAttemptStatus status, ZonedDateTime sentBefore);
}
