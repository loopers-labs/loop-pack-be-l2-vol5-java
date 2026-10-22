package com.loopers.domain.payment;

import com.loopers.domain.common.Money;

public record PaymentCreation(
    ProviderOrderId providerOrderId,
    CardType cardType,
    CardNumber cardNumber,
    Money amount
) {
}
