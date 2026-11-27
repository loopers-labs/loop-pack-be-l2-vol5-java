package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.payment.Payment;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.UUID;

class PaymentLogContextTest {
    @Test
    void 대사_중_예외가_나도_같은_스레드의_다른_로그에_주문과_시도키가_남지_않는다() {
        var payment = Payment.start(65, UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"), 7_000);
        String previousKey = MDC.get("paymentAttemptKey");
        try (var ignored = MDC.putCloseable("orderId", "outer")) {
            assertThatThrownBy(() -> PaymentLogContext.withPayment(payment, () -> {
                assertThat(MDC.get("orderId")).isEqualTo("65");
                assertThat(MDC.get("paymentAttemptKey")).isEqualTo(payment.paymentAttemptKey().toString());
                throw new IllegalStateException("lookup failed");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("orderId")).isEqualTo("outer");
            assertThat(MDC.get("paymentAttemptKey")).isEqualTo(previousKey);
        }
    }
}
