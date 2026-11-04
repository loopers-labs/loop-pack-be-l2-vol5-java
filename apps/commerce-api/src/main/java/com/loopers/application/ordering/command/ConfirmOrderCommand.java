package com.loopers.application.ordering.command;

import com.loopers.application.pay.gateway.PaymentProviderGateway.CardInput;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.PaymentScenario;
import java.util.Objects;

public record ConfirmOrderCommand(long userId, long orderId, String paymentAttemptKey, CardInput card,
                                  PaymentScenario scenario) {
    public ConfirmOrderCommand {
        if (userId <= 0 || orderId <= 0) {
            throw new IllegalArgumentException("사용자와 주문 ID는 양수여야 합니다.");
        }
        paymentAttemptKey = PaymentAttempt.normalizeKey(paymentAttemptKey);
        Objects.requireNonNull(card);
        Objects.requireNonNull(scenario);
    }

    public PrepareOrderPaymentCommand preparation() {
        return new PrepareOrderPaymentCommand(userId, orderId, paymentAttemptKey, scenario);
    }

    @Override
    public String toString() { return "ConfirmOrderCommand[redacted]"; }
}
