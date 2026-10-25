package com.loopers.interfaces.api.payment;

import com.loopers.domain.payment.ProviderOrderId;

public class PaymentCallbackV1Dto {

    public record CallbackRequest(String orderId) {
        public CallbackRequest {
            new ProviderOrderId(orderId);
        }

        public ProviderOrderId providerOrderId() {
            return new ProviderOrderId(orderId);
        }
    }
}
