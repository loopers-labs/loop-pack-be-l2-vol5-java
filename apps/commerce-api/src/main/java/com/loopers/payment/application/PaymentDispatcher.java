package com.loopers.payment.application;

import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.domain.PaymentRequestedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentDispatcher implements PaymentDispatchUseCase {

    @Override
    public void dispatch(PaymentRequestedEvent event) {
    }
}
