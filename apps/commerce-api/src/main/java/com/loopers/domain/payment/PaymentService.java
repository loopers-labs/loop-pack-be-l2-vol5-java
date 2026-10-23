package com.loopers.domain.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;

    public Optional<Payment> findByAttemptKey(PaymentAttemptKey attemptKey) {
        return paymentRepository.findByAttemptKey(attemptKey);
    }

    public Payment save(Payment payment) {
        return paymentRepository.save(payment);
    }
}
