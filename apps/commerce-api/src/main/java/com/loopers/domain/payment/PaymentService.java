package com.loopers.domain.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentDiscrepancyRepository paymentDiscrepancyRepository;

    public Optional<Payment> findByAttemptKey(PaymentAttemptKey attemptKey) {
        return paymentRepository.findByAttemptKey(attemptKey);
    }

    public Optional<Payment> findByProviderOrderId(ProviderOrderId providerOrderId) {
        return paymentRepository.findByProviderOrderId(providerOrderId);
    }

    public Long orderIdOf(Long paymentId) {
        return paymentRepository.findOrderIdById(paymentId)
            .orElseThrow(() -> new IllegalStateException("결제가 없습니다: id=" + paymentId));
    }

    public Payment getForUpdate(Long paymentId) {
        return paymentRepository.findByIdForUpdate(paymentId)
            .orElseThrow(() -> new IllegalStateException("결제가 없습니다: id=" + paymentId));
    }

    public Payment save(Payment payment) {
        return paymentRepository.save(payment);
    }

    public void recordDiscrepancy(Long paymentId, PaymentDiscrepancy discrepancy, PaymentDiscrepancy.Source source,
                                  Instant observedAt) {
        paymentDiscrepancyRepository.record(paymentId, discrepancy, source, observedAt);
    }
}
