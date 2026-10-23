package com.loopers.application.ordering.command;

import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

// status는 알림의 형식만 검사한다. 결제 판단은 PG 권위 조회 결과로 한다.
public record HandlePaymentCallbackCommand(String providerOrderId, String transactionKey, ProviderPaymentStatus status) {
    public HandlePaymentCallbackCommand {
        if (providerOrderId == null || providerOrderId.isBlank() || providerOrderId.length() < 6
                || providerOrderId.length() > 100 || transactionKey == null || transactionKey.isBlank()
                || transactionKey.length() > 64 || status == null) {
            throw new DomainException(DomainErrorCode.INVALID_PAYMENT_CALLBACK);
        }
    }
}
