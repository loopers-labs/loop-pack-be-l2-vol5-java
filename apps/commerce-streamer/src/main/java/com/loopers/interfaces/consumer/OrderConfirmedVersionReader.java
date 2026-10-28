package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.purchase.OrderConfirmation;

public interface OrderConfirmedVersionReader {
    int schemaVersion();

    OrderConfirmation read(JsonNode json);
}
