package com.loopers.payment.application;

import com.loopers.payment.application.port.in.PaymentInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PaymentQueryService {

    public PaymentInfo getMyPayment(Long userId, Long orderId) {
        return null;
    }
}
