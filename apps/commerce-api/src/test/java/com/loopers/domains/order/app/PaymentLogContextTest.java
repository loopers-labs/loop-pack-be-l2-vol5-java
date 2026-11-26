package com.loopers.domains.order.app;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentLogContextTest {
    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("바깥 orderId가 있는 상태에서 연 문맥을 닫으면 안에서 넣은 값은 사라지고 바깥 orderId로 복원된다")
    void restoresOuterValuesOnClose() {
        MDC.put("orderId", "1");
        Map<String, String> inside;

        try (PaymentLogContext.Scope scope = PaymentLogContext.open(2)) {
            scope.attempt("attempt-2", "LP-ORD-0000000002");
            scope.transactionKey("TR-000002");
            inside = snapshot();
        }

        assertThat(inside)
            .containsEntry("orderId", "2")
            .containsEntry("attemptKey", "attempt-2")
            .containsEntry("providerOrderId", "LP-ORD-0000000002")
            .containsEntry("transactionKey", "TR-000002");
        assertThat(snapshot()).containsExactlyEntriesOf(Map.of("orderId", "1"));
    }

    @Test
    @DisplayName("문맥 안에서 예외가 나도 닫힐 때 안에서 넣은 값은 사라지고 바깥 orderId로 복원된다")
    void restoresOuterValuesWhenExceptionThrown() {
        MDC.put("orderId", "1");
        Map<String, String> inside = new HashMap<>();

        assertThatThrownBy(() -> {
            try (PaymentLogContext.Scope scope = PaymentLogContext.open(2)) {
                scope.attempt("attempt-2", "LP-ORD-0000000002");
                inside.putAll(snapshot());
                throw new IllegalStateException("주입된 예외");
            }
        }).isInstanceOf(IllegalStateException.class);

        assertThat(inside)
            .containsEntry("orderId", "2")
            .containsEntry("attemptKey", "attempt-2")
            .containsEntry("providerOrderId", "LP-ORD-0000000002");
        assertThat(snapshot()).containsExactlyEntriesOf(Map.of("orderId", "1"));
    }

    private static Map<String, String> snapshot() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        if (copy == null) {
            return Map.of();
        }
        return copy;
    }
}
