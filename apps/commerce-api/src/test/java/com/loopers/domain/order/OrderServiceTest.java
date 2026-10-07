package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderServiceTest {

    private final OrderService orderService = new OrderService();

    @DisplayName("같은 상품의 수량을 요청 순서대로 합친다.")
    @Test
    void mergesQuantitiesInRequestOrder() {
        Map<Long, Long> quantities = orderService.mergeQuantities(List.of(
            new OrderItemCommand(2L, 1L),
            new OrderItemCommand(1L, 3L),
            new OrderItemCommand(2L, 4L)
        ));

        assertAll(
            () -> assertThat(quantities.keySet()).containsExactly(2L, 1L),
            () -> assertThat(quantities).containsEntry(2L, 5L).containsEntry(1L, 3L)
        );
    }

    @DisplayName("합산 수량이 long 범위를 넘으면 NUMERIC_OVERFLOW로 거절한다.")
    @Test
    void rejectsQuantityOverflow() {
        assertThatThrownBy(() -> orderService.mergeQuantities(List.of(
            new OrderItemCommand(1L, Long.MAX_VALUE),
            new OrderItemCommand(1L, 1L)
        )))
            .isInstanceOf(CoreException.class)
            .hasFieldOrPropertyWithValue("errorType", ErrorType.NUMERIC_OVERFLOW);
    }

    @DisplayName("조회된 상품의 현재 가격으로 초안 주문을 만든다.")
    @Test
    void createsDraftWithCurrentProductPrice() {
        ProductModel product = mock(ProductModel.class);
        when(product.getId()).thenReturn(10L);
        when(product.getPrice()).thenReturn(Money.of(2_000L));

        OrderModel order = orderService.createDraft(1L, Map.of(10L, 3L), Map.of(10L, product));

        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(order.getOrderTotal()).isEqualTo(Money.of(6_000L)),
            () -> assertThat(order.getItems()).singleElement().satisfies(item -> {
                assertThat(item.getProductId()).isEqualTo(10L);
                assertThat(item.getQuantity()).isEqualTo(3L);
                assertThat(item.getUnitPrice()).isEqualTo(Money.of(2_000L));
            })
        );
    }
}
