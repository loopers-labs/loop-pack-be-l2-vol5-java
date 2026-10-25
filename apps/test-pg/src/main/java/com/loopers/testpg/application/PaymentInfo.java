package com.loopers.testpg.application;

import com.loopers.testpg.domain.Payment;
import com.loopers.testpg.domain.PaymentStatus;

public record PaymentInfo(String transactionKey, String orderId, long amount, PaymentStatus status) {
    public static PaymentInfo from(Payment payment) {
        return new PaymentInfo(payment.getTransactionKey(), payment.getOrderId(), payment.getAmount(), payment.getStatus());
    }
}
