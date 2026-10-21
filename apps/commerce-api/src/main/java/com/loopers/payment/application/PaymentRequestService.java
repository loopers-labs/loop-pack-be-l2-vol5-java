package com.loopers.payment.application;

import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentRequestService implements PaymentRequestUseCase {

    @Override
    public PaymentInfo request(PaymentRequestCommand command) {
        return new PaymentInfo(null, null, null, null, null, null);
    }
}
