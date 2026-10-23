package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;

import java.util.Set;

/** Notification only: its status is never used as an authoritative payment result. */
public record PaymentCallbackRequest(String providerOrderId, String transactionKey, String status) {
    public static PaymentCallbackRequest from(JsonNode body) {
        StrictInput.object(body, "providerOrderId", "transactionKey", "status");
        String providerOrderId = StrictInput.text(body, "providerOrderId");
        String transactionKey = StrictInput.text(body, "transactionKey");
        String status = StrictInput.text(body, "status");
        if (!validIdentity(providerOrderId, 6) || !validIdentity(transactionKey, 1)
            || !Set.of("PENDING", "SUCCESS", "FAILED").contains(status)) {
            throw new InvalidRequestException();
        }
        return new PaymentCallbackRequest(providerOrderId, transactionKey, status);
    }

    private static boolean validIdentity(String value, int minimum) {
        int length = value.codePointCount(0, value.length());
        return !value.isBlank() && length >= minimum && length <= 255;
    }

    @Override
    public String toString() {
        return "PaymentCallbackRequest[redacted]";
    }
}
