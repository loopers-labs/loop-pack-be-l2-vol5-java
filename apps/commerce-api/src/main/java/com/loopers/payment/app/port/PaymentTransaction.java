package com.loopers.payment.app.port;

import com.loopers.common.domain.Money;

public record PaymentTransaction(String transactionKey, String providerOrderId, Money amount, PaymentTransactionStatus status) {
}
