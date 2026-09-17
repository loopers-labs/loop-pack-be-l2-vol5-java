package com.loopers.domain.order;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void createsDraftAndCalculatesTotal() {
        Order order = Order.create(1L, List.of(
            new OrderItem(10L, "Air Max", 100L, 2)
        ));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(200L);
        assertThat(order.getItems()).hasSize(1);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> new OrderItem(10L, "Air Max", 100L, 0))
            .hasMessageContaining("수량");
    }

    @Test
    void mergesDuplicateProductItems() {
        Order order = Order.create(1L, List.of(
            new OrderItem(10L, "Air Max", 100L, 2),
            new OrderItem(10L, "Air Max", 100L, 3)
        ));

        assertThat(order.getItems()).singleElement()
            .extracting(OrderItem::getQuantity).isEqualTo(5);
        assertThat(order.getTotalAmount()).isEqualTo(500L);
    }
}
