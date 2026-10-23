package com.loopers.interfaces.api.payment;

import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;

public class PaymentCallbackV1Dto {

    public record CallbackRequest(String orderId, String transactionKey) {
        public CallbackRequest {
            new ProviderOrderId(orderId);
            new TransactionKey(transactionKey);
        }

        public ProviderOrderId providerOrderId() {
            return new ProviderOrderId(orderId);
        }

        public TransactionKey key() {
            return new TransactionKey(transactionKey);
        }
    }
}
