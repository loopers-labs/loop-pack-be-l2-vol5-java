package com.loopers.interfaces.api.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.interfaces.api.commerce.InvalidRequestException;
import com.loopers.interfaces.api.commerce.StrictInput;

import java.util.UUID;

/** Transient input only. Do not persist or log the body containing the synthetic card. */
public record PaymentConfirmRequest(UUID paymentAttemptKey, String cardType, String cardNo) {
    public PaymentConfirmRequest {
        if (paymentAttemptKey == null || cardType == null || cardType.isBlank() || cardNo == null || cardNo.isBlank()) {
            throw new InvalidRequestException();
        }
    }

    public static PaymentConfirmRequest from(JsonNode body) {
        StrictInput.object(body, "paymentAttemptKey", "cardType", "cardNo");
        String attemptKey = StrictInput.text(body, "paymentAttemptKey");
        if (!attemptKey.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new InvalidRequestException();
        }
        return new PaymentConfirmRequest(UUID.fromString(attemptKey), StrictInput.text(body, "cardType"),
            StrictInput.text(body, "cardNo"));
    }

    @Override
    public String toString() {
        return "PaymentConfirmRequest[card=REDACTED]";
    }
}
