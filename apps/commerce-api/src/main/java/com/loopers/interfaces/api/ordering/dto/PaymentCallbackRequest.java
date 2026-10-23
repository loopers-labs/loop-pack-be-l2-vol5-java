package com.loopers.interfaces.api.ordering.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.ordering.command.HandlePaymentCallbackCommand;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.util.Set;

public record PaymentCallbackRequest(String providerOrderId, String transactionKey, String status) {
    private static final Set<String> FIELDS = Set.of("providerOrderId", "transactionKey", "status");

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static PaymentCallbackRequest parse(JsonNode body) {
        if (body == null || !body.isObject()) { throw invalid(); }
        body.fieldNames().forEachRemaining(name -> { if (!FIELDS.contains(name)) { throw invalid(); } });
        return new PaymentCallbackRequest(text(body, "providerOrderId"), text(body, "transactionKey"), text(body, "status"));
    }

    public HandlePaymentCallbackCommand toCommand() {
        try {
            return new HandlePaymentCallbackCommand(providerOrderId, transactionKey, ProviderPaymentStatus.valueOf(status));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalid();
        }
    }

    private static String text(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual()) { throw invalid(); }
        return value.textValue();
    }

    private static DomainException invalid() { return new DomainException(DomainErrorCode.INVALID_PAYMENT_CALLBACK); }
}
