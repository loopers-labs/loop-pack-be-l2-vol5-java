package com.loopers.batch.payment;

public interface PaymentLookupClient {
    LookupResult findByOrderId(String orderId);

    record LookupResult(int httpStatus, String paymentStatus, String rawResponse) {
        public boolean isFound() {
            return httpStatus == 200 && paymentStatus != null;
        }
    }
}
