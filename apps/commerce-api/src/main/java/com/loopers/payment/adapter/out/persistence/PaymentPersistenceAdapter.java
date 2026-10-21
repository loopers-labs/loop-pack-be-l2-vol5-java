package com.loopers.payment.adapter.out.persistence;

import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentPersistenceAdapter implements PaymentPort {

    /** reconcile이 확인할 상태 — 결과를 모르는 결제만 (plan.md 5-1) */
    private static final List<PaymentStatus> UNRESOLVED = List.of(PaymentStatus.PENDING, PaymentStatus.UNKNOWN);

    private final PaymentJpaRepository paymentJpaRepository;

    /**
     * 즉시 flush해 unique 위반(order_id·attempt_key·provider_order_id)이 저장한 자리에서 드러나게 한다 (plan.md 2).
     */
    @Override
    public Payment save(Payment payment) {
        return paymentJpaRepository.saveAndFlush(payment);
    }

    @Override
    public Optional<Payment> findById(Long paymentId) {
        return paymentJpaRepository.findById(paymentId);
    }

    @Override
    public Optional<Payment> findByIdForUpdate(Long paymentId) {
        return paymentJpaRepository.findByIdForUpdate(paymentId);
    }

    @Override
    public Optional<Payment> findByOrderId(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<Payment> findByAttemptKey(String attemptKey) {
        return paymentJpaRepository.findByAttemptKey(attemptKey);
    }

    @Override
    public Optional<Payment> findByProviderOrderId(String providerOrderId) {
        return paymentJpaRepository.findByProviderOrderId(providerOrderId);
    }

    @Override
    public List<Payment> findDueForCheck(ZonedDateTime now, int limit) {
        return List.of();
    }
}
