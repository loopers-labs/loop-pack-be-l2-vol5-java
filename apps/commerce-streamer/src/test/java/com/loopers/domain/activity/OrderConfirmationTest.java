package com.loopers.domain.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class OrderConfirmationTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");

    private static OrderConfirmation confirmation(Long orderId, OrderConfirmation.Line... lines) {
        return new OrderConfirmation(OrderConfirmation.eventIdOf(orderId), orderId, CONFIRMED_AT, Arrays.asList(lines));
    }

    private static OrderConfirmation.Line line(Long productId, Integer quantity) {
        return new OrderConfirmation.Line(productId, quantity);
    }

    @DisplayName("품목은 productId 오름차순으로 정규화하고, 배열 순서가 달라도 지문이 같다.")
    @Test
    void normalizesItems_andFingerprintIgnoresOrder() {
        OrderConfirmation a = confirmation(42L, line(8L, 1), line(7L, 2));
        OrderConfirmation b = confirmation(42L, line(7L, 2), line(8L, 1));

        assertThat(a.items()).extracting(OrderConfirmation.Line::productId, OrderConfirmation.Line::quantity)
            .containsExactly(tuple(7L, 2), tuple(8L, 1));
        assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).hasSize(64);
    }

    @DisplayName("수량·확정 시각이 다르면 지문이 다르다. 같은 시각의 다른 표기(나노초 0 채움)는 같다.")
    @Test
    void fingerprintReflectsFixedPayload() {
        OrderConfirmation base = confirmation(42L, line(7L, 2));

        assertThat(confirmation(42L, line(7L, 3)).fingerprint()).isNotEqualTo(base.fingerprint());
        assertThat(new OrderConfirmation("order-confirmed:v1:42", 42L, CONFIRMED_AT.plusMillis(1), List.of(line(7L, 2))).fingerprint())
            .isNotEqualTo(base.fingerprint());
        assertThat(new OrderConfirmation("order-confirmed:v1:42", 42L, Instant.parse("2026-09-07T01:00:00.123456000Z"), List.of(line(7L, 2))).fingerprint())
            .isEqualTo(base.fingerprint());
    }

    @DisplayName("계약 위반: eventId·orderId 불일치, 확정 시각 없음, 품목 없음, 수량 0 이하, 같은 상품 중복.")
    @Test
    void rejectsContractViolations() {
        assertThatThrownBy(() -> new OrderConfirmation("order-confirmed:v1:43", 42L, CONFIRMED_AT, List.of(line(7L, 1))))
            .isInstanceOf(OrderConfirmationContractException.class);
        assertThatThrownBy(() -> new OrderConfirmation("order-confirmed:v1:42", 42L, null, List.of(line(7L, 1))))
            .isInstanceOf(OrderConfirmationContractException.class);
        assertThatThrownBy(() -> confirmation(42L))
            .isInstanceOf(OrderConfirmationContractException.class);
        assertThatThrownBy(() -> confirmation(42L, line(7L, 0)))
            .isInstanceOf(OrderConfirmationContractException.class);
        assertThatThrownBy(() -> confirmation(42L, line(7L, 1), line(7L, 2)))
            .isInstanceOf(OrderConfirmationContractException.class);
        assertThatThrownBy(() -> confirmation(42L, line(null, 1)))
            .isInstanceOf(OrderConfirmationContractException.class);
    }
}
