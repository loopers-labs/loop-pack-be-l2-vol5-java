package com.loopers.payment.adapter.out.persistence;

import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.domain.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentPersistenceAdapter implements PaymentPort {

    @Override
    public Payment save(Payment payment) {
        return payment;
    }

    @Override
    public Optional<Payment> findById(Long paymentId) {
        return Optional.empty();
    }

    @Override
    public Optional<Payment> findByIdForUpdate(Long paymentId) {
        return Optional.empty();
    }

    @Override
    public Optional<Payment> findByOrderId(Long orderId) {
        return Optional.empty();
    }

    @Override
    public Optional<Payment> findByAttemptKey(String attemptKey) {
        return Optional.empty();
    }

    @Override
    public Optional<Payment> findByProviderOrderId(String providerOrderId) {
        return Optional.empty();
    }

    @Override
    public List<Payment> findUnresolved(ZonedDateTime checkedBefore, int limit) {
        return List.of();
    }
}
