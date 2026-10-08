package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderItemTest {

    @DisplayName("create는 1원 이상인 단가를 허용하고 해당 단가로 품목 금액을 계산한다.")
    @ParameterizedTest(name = "단가 {0}원")
    @ValueSource(longs = {1L, 100_000_000L, 100_000_001L})
    void createsItem_whenUnitPriceIsPositive(long unitPrice) {
        OrderItem item = OrderItem.create(10L, "Air Max", unitPrice, 2);

        assertThat(item.getUnitPrice()).isEqualTo(unitPrice);
        assertThat(item.getAmount()).isEqualTo(unitPrice * 2);
    }

    @DisplayName("record 생성자도 1원 이상인 단가를 허용한다.")
    @ParameterizedTest(name = "단가 {0}원")
    @ValueSource(longs = {1L, 100_000_000L, 100_000_001L})
    void constructsItem_whenUnitPriceIsPositive(long unitPrice) {
        OrderItem item = new OrderItem(10L, "Air Max", unitPrice, 2);

        assertThat(item.getUnitPrice()).isEqualTo(unitPrice);
        assertThat(item.getAmount()).isEqualTo(unitPrice * 2);
    }

    @DisplayName("create는 0과 음수 단가를 BAD_REQUEST로 거절한다.")
    @ParameterizedTest(name = "단가 {0}원")
    @ValueSource(longs = {0L, -1L})
    void rejectsInvalidUnitPrice_whenUsingFactory(long unitPrice) {
        assertThatThrownBy(() -> OrderItem.create(10L, "Air Max", unitPrice, 2))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getMessage()).isEqualTo("주문 단가는 1원 이상이어야 합니다.");
            });
    }

    @DisplayName("record 생성자 직접 호출도 0과 음수 단가를 BAD_REQUEST로 거절한다.")
    @ParameterizedTest(name = "단가 {0}원")
    @ValueSource(longs = {0L, -1L})
    void rejectsInvalidUnitPrice_whenUsingConstructor(long unitPrice) {
        assertThatThrownBy(() -> new OrderItem(10L, "Air Max", unitPrice, 2))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getMessage()).isEqualTo("주문 단가는 1원 이상이어야 합니다.");
            });
    }

    @DisplayName("수량이 1이면 Long.MAX_VALUE 단가도 허용한다.")
    @Test
    void acceptsMaximumLongUnitPrice_whenQuantityIsOne() {
        OrderItem item = OrderItem.create(10L, "Air Max", Long.MAX_VALUE, 1);
        OrderItem directlyCreated = new OrderItem(10L, "Air Max", Long.MAX_VALUE, 1);

        assertThat(item.getUnitPrice()).isEqualTo(Long.MAX_VALUE);
        assertThat(item.getAmount()).isEqualTo(Long.MAX_VALUE);
        assertThat(directlyCreated).isEqualTo(item);
    }

    @DisplayName("create는 단가와 수량의 곱이 long 범위를 넘으면 BAD_REQUEST로 거절한다.")
    @Test
    void rejectsItemAmountOverflow_whenUsingFactory() {
        assertThatThrownBy(() -> OrderItem.create(10L, "Air Max", Long.MAX_VALUE, 2))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getMessage()).isEqualTo("주문 품목 금액이 저장 가능한 범위를 초과했습니다.");
            });
    }

    @DisplayName("record 생성자도 단가와 수량의 곱이 long 범위를 넘으면 BAD_REQUEST로 거절한다.")
    @Test
    void rejectsItemAmountOverflow_whenUsingConstructor() {
        assertThatThrownBy(() -> new OrderItem(10L, "Air Max", Long.MAX_VALUE, 2))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getMessage()).isEqualTo("주문 품목 금액이 저장 가능한 범위를 초과했습니다.");
            });
    }

    @DisplayName("수량 합산으로 품목 금액이 long 범위를 넘으면 거절하고 기존 품목 값을 유지한다.")
    @Test
    void rejectsItemAmountOverflow_whenAddingQuantity() {
        long unitPrice = Long.MAX_VALUE / 2 + 1;
        OrderItem item = OrderItem.create(10L, "Air Max", unitPrice, 1);

        assertThatThrownBy(() -> item.addQuantity(1))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getMessage()).isEqualTo("주문 품목 금액이 저장 가능한 범위를 초과했습니다.");
            });
        assertThat(item.getQuantity()).isEqualTo(1);
        assertThat(item.getAmount()).isEqualTo(unitPrice);
    }
}
