package com.loopers.domains.payment.app.port;

import com.loopers.common.domain.Money;

public record PaymentCancelCommand(String providerOrderId, String transactionKey, Money amount) {
}
