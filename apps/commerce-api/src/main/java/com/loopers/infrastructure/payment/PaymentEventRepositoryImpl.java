package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentEventRepositoryImpl implements PaymentEventRepository {

    private final PaymentEventJpaRepository paymentEventJpaRepository;

    @Override
    public PaymentEvent save(PaymentEvent event) {
        return paymentEventJpaRepository.save(event);
    }
}
