package com.loopers.domain.purchase.vo;

import com.loopers.support.error.RejectReason;
import com.loopers.support.error.RejectedEventException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderConfirmationTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.parse("2026-09-07T01:00:00.123456Z");

    private static OrderConfirmation confirmation(ZonedDateTime confirmedAt, List<OrderConfirmation.Item> items) {
        return new OrderConfirmation("order-confirmed:v1:42", 42L, confirmedAt, items);
    }

    @DisplayName("규칙을 지키면 만들어지고, 상품별 수량을 순서대로 돌려준다.")
    @Test
    void createsConfirmation() {
        OrderConfirmation result = confirmation(CONFIRMED_AT, List.of(new OrderConfirmation.Item(7L, 2), new OrderConfirmation.Item(8L, 1)));

        assertThat(result.quantityByProduct()).containsExactly(Map.entry(7L, 2), Map.entry(8L, 1));
    }

    @DisplayName("품목이 비어 있으면 EMPTY_ITEMS 로 거절한다.")
    @Test
    void rejectsEmptyItems() {
        RejectedEventException result = assertThrows(RejectedEventException.class, () -> confirmation(CONFIRMED_AT, List.of()));

        assertThat(result.getReason()).isEqualTo(RejectReason.EMPTY_ITEMS);
    }

    @DisplayName("수량이 1 미만이면 INVALID_QUANTITY 로 거절한다.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void rejectsNonPositiveQuantity(int quantity) {
        RejectedEventException result = assertThrows(RejectedEventException.class,
            () -> confirmation(CONFIRMED_AT, List.of(new OrderConfirmation.Item(7L, quantity))));

        assertThat(result.getReason()).isEqualTo(RejectReason.INVALID_QUANTITY);
    }

    @DisplayName("같은 상품이 두 번 있으면 합산하지 않고 DUPLICATE_PRODUCT 로 거절한다. (D-80)")
    @Test
    void rejectsDuplicateProduct() {
        RejectedEventException result = assertThrows(RejectedEventException.class,
            () -> confirmation(CONFIRMED_AT, List.of(new OrderConfirmation.Item(7L, 1), new OrderConfirmation.Item(7L, 1))));

        assertThat(result.getReason()).isEqualTo(RejectReason.DUPLICATE_PRODUCT);
    }

    @DisplayName("확정 시각이 마이크로초보다 정밀하면 자르지 않고 TOO_PRECISE_TIME 으로 거절한다. (D-84)")
    @Test
    void rejectsTooPreciseTime() {
        RejectedEventException result = assertThrows(RejectedEventException.class,
            () -> confirmation(CONFIRMED_AT.plusNanos(1), List.of(new OrderConfirmation.Item(7L, 1))));

        assertThat(result.getReason()).isEqualTo(RejectReason.TOO_PRECISE_TIME);
    }
}
