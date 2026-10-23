package com.loopers.application.payment;

import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.PaymentAttemptKey;

public record PaymentConfirmCommand(
    Long userId,
    Long orderId,
    PaymentAttemptKey attemptKey,
    CardType cardType,
    CardNumber cardNumber
) {
}
