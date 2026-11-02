package com.loopers.application.payment.port;

import com.loopers.common.domain.Money;

public record PaymentTransaction(String transactionKey, String providerOrderId, Money amount, PaymentTransactionStatus status) {
}
