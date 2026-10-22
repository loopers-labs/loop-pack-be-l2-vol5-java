package com.loopers.domain.payment;

import com.loopers.domain.common.Money;

public record PgTransaction(
    TransactionKey transactionKey,
    ProviderOrderId providerOrderId,
    Money amount,
    TransactionStatus status
) {
}
