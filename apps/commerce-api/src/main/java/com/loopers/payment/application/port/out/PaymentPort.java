package com.loopers.payment.application.port.out;

import com.loopers.payment.domain.Payment;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentPort {

    Payment save(Payment payment);

    Optional<Payment> findById(Long paymentId);

    /**
     * 결제 행을 쓰기 잠금으로 찾는다. callback·reconcile·생성 응답이 동시에 반영하지 않게 한다 (ADR-W5-04 ①).
     */
    Optional<Payment> findByIdForUpdate(Long paymentId);

    Optional<Payment> findByOrderId(Long orderId);

    Optional<Payment> findByAttemptKey(String attemptKey);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    /**
     * reconcile 대상 (plan.md 5-1): PENDING·UNKNOWN 중 아직 조회하지 않았거나 마지막 조회가 checkedBefore보다 이른 결제, id 오름차순.
     */
    List<Payment> findUnresolved(ZonedDateTime checkedBefore, int limit);
}
