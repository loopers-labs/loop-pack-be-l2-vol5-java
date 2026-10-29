package com.loopers.application.payment.port;

import com.loopers.domain.common.Money;

public record PaymentTransaction(String transactionKey, String providerOrderId, Money amount, PaymentTransactionStatus status) {
}
