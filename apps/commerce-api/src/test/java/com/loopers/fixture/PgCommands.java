package com.loopers.fixture;

import com.loopers.application.payment.PaymentConfirmCommand;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PgCommands {
    private PgCommands() {}

    public static PaymentConfirmCommand forOrder(Long orderId) {
        String key = UUID.nameUUIDFromBytes(("test-order-" + orderId).getBytes(StandardCharsets.UTF_8)).toString();
        return new PaymentConfirmCommand(key, "TEST", "0000-0000-0000-0000");
    }
}
