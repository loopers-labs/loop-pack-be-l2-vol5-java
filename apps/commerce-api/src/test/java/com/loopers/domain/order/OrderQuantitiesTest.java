package com.loopers.domain.order;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderQuantitiesTest {

    @DisplayName("ORDER-QUANTITY-01: 서로 다른 상품의 수량을 각각 유지한다.")
    @Test
    void keepsQuantitiesOfDifferentProducts() {
        OrderQuantities quantities = OrderQuantities.combine(List.of(
            new OrderQuantities.Item(101, 2),
            new OrderQuantities.Item(103, 1)
        ));

        assertThat(quantities.byProductId()).isEqualTo(Map.of(101L, 2, 103L, 1));
    }

    @DisplayName("ORDER-QUANTITY-02: 떨어져 있는 같은 상품의 수량 2와 3도 하나의 수량 5로 합산한다.")
    @Test
    void combinesRepeatedProductQuantities() {
        OrderQuantities quantities = OrderQuantities.combine(List.of(
            new OrderQuantities.Item(101, 2),
            new OrderQuantities.Item(103, 1),
            new OrderQuantities.Item(101, 3)
        ));

        assertThat(quantities.byProductId()).isEqualTo(Map.of(101L, 5, 103L, 1));
    }

    @DisplayName("ORDER-QUANTITY-03: 같은 상품의 다른 수량이 양수여도 0·음수 입력을 합산하지 않는다.")
    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsNonPositiveIndividualQuantity(int invalidQuantity) {
        assertThatThrownBy(() -> OrderQuantities.combine(List.of(
            new OrderQuantities.Item(101, 2),
            new OrderQuantities.Item(101, invalidQuantity)
        )))
            .isInstanceOfSatisfying(OrderQuantityException.class,
                error -> assertThat(error.getReason()).isEqualTo(OrderQuantityException.Reason.INVALID_QUANTITY));
    }

    @DisplayName("ORDER-QUANTITY-04: 개별 수량이 유효해도 같은 상품의 합계가 Integer 범위를 넘으면 거절한다.")
    @ParameterizedTest
    @CsvSource({"2147483647, 1", "2147483646, 2"})
    void rejectsQuantitySumOverflow(int first, int second) {
        List<OrderQuantities.Item> items = List.of(
            new OrderQuantities.Item(101, first),
            new OrderQuantities.Item(103, 1),
            new OrderQuantities.Item(101, second)
        );

        assertThatThrownBy(() -> OrderQuantities.combine(items))
            .isInstanceOfSatisfying(OrderQuantityException.class,
                error -> assertThat(error.getReason()).isEqualTo(OrderQuantityException.Reason.QUANTITY_LIMIT_EXCEEDED));
        assertThat(items).containsExactly(
            new OrderQuantities.Item(101, first),
            new OrderQuantities.Item(103, 1),
            new OrderQuantities.Item(101, second)
        );
    }

    @DisplayName("ORDER-QUANTITY-05: 주문할 상품 항목이 없거나 null 항목이면 거절한다.")
    @ParameterizedTest
    @MethodSource("invalidItems")
    void rejectsInvalidItems(List<OrderQuantities.Item> items) {
        assertThatThrownBy(() -> OrderQuantities.combine(items))
            .isInstanceOfSatisfying(OrderQuantityException.class,
                error -> assertThat(error.getReason()).isEqualTo(OrderQuantityException.Reason.INVALID_ITEMS));
    }

    @DisplayName("ORDER-QUANTITY-06: 양수가 아닌 상품 ID를 거절한다.")
    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveProductId(long productId) {
        assertThatThrownBy(() -> OrderQuantities.combine(List.of(new OrderQuantities.Item(productId, 1))))
            .isInstanceOfSatisfying(OrderQuantityException.class,
                error -> assertThat(error.getReason()).isEqualTo(OrderQuantityException.Reason.INVALID_PRODUCT_ID));
    }

    @DisplayName("ORDER-QUANTITY-07: Long 최댓값 ID와 Integer 최댓값 수량까지 정확하게 보존한다.")
    @Test
    void acceptsMaximumIndividualValues() {
        OrderQuantities quantities = OrderQuantities.combine(List.of(
            new OrderQuantities.Item(Long.MAX_VALUE, Integer.MAX_VALUE)
        ));

        assertThat(quantities.byProductId()).isEqualTo(Map.of(Long.MAX_VALUE, Integer.MAX_VALUE));
    }

    @DisplayName("ORDER-QUANTITY-07: 합계가 정확히 Integer 최댓값이면 허용한다.")
    @Test
    void acceptsMaximumSum() {
        OrderQuantities quantities = OrderQuantities.combine(List.of(
            new OrderQuantities.Item(101, Integer.MAX_VALUE - 1),
            new OrderQuantities.Item(101, 1)
        ));

        assertThat(quantities.byProductId()).isEqualTo(Map.of(101L, Integer.MAX_VALUE));
    }

    @DisplayName("ORDER-QUANTITY-08: 입력 목록과 반환 결과를 통한 우회 변경을 허용하지 않는다.")
    @Test
    void keepsCombinedQuantitiesImmutable() {
        List<OrderQuantities.Item> items = new ArrayList<>(List.of(
            new OrderQuantities.Item(101, 2), new OrderQuantities.Item(101, 3)
        ));
        List<OrderQuantities.Item> before = List.copyOf(items);

        OrderQuantities quantities = OrderQuantities.combine(items);

        assertThat(items).isEqualTo(before);
        items.clear();
        assertThatThrownBy(() -> quantities.byProductId().put(101L, 9))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(quantities.byProductId()).isEqualTo(Map.of(101L, 5));
    }

    private static Stream<Arguments> invalidItems() {
        return Stream.of(
            Arguments.of((Object) null),
            Arguments.of(List.of()),
            Arguments.of(Arrays.asList(new OrderQuantities.Item(101, 1), null))
        );
    }
}
