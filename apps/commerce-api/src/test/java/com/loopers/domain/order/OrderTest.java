package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void createsDraftAndCalculatesTotal() {
        Order order = Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, 2)
        ));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(200L);
        assertThat(order.getItems()).hasSize(1);
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> OrderItem.create(10L, "Air Max", 100L, 0))
            .hasMessageContaining("수량");
    }

    @Test
    void mergesDuplicateProductItems() {
        Order order = Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, 2),
            OrderItem.create(10L, "Air Max", 100L, 3)
        ));

        assertThat(order.getItems()).singleElement()
            .extracting(OrderItem::getQuantity).isEqualTo(5);
        assertThat(order.getTotalAmount()).isEqualTo(500L);
    }

    @Test
    void mergesDuplicateItemsIntoANewValueWithoutChangingInputItems() {
        OrderItem firstItem = OrderItem.create(10L, "Air Max", 100L, 2);
        OrderItem secondItem = OrderItem.create(10L, "Air Max", 100L, 3);

        Order order = Order.create(1L, List.of(firstItem, secondItem));

        assertThat(firstItem.getQuantity()).isEqualTo(2);
        assertThat(secondItem.getQuantity()).isEqualTo(3);
        assertThat(order.getItems()).singleElement().satisfies(merged -> {
            assertThat(merged).isNotSameAs(firstItem).isNotSameAs(secondItem);
            assertThat(merged.getProductId()).isEqualTo(10L);
            assertThat(merged.getProductName()).isEqualTo("Air Max");
            assertThat(merged.getUnitPrice()).isEqualTo(100L);
            assertThat(merged.getQuantity()).isEqualTo(5);
            assertThat(merged.getAmount()).isEqualTo(500L);
        });
        assertThat(order.getTotalAmount()).isEqualTo(500L);
    }

    @Test
    void preservesAnEarlierOrderWhenItsItemIsReusedForAnotherOrder() {
        OrderItem sharedItem = OrderItem.create(10L, "Air Max", 100L, 2);
        Order earlierOrder = Order.create(1L, List.of(sharedItem));

        Order laterOrder = Order.create(2L, List.of(
            sharedItem, OrderItem.create(10L, "Air Max", 100L, 3)
        ));

        assertThat(earlierOrder.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getQuantity()).isEqualTo(2);
            assertThat(item.getAmount()).isEqualTo(200L);
        });
        assertThat(earlierOrder.getTotalAmount()).isEqualTo(200L);
        assertThat(laterOrder.getItems()).singleElement()
            .extracting(OrderItem::getQuantity).isEqualTo(5);
        assertThat(laterOrder.getTotalAmount()).isEqualTo(500L);
    }

    @Test
    void acceptsMergedQuantityAtTheIntegerLimit() {
        Order order = Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, Integer.MAX_VALUE - 1),
            OrderItem.create(10L, "Air Max", 100L, 1)
        ));

        assertThat(order.getItems()).singleElement()
            .extracting(OrderItem::getQuantity).isEqualTo(Integer.MAX_VALUE);
        assertThat(order.getTotalAmount()).isEqualTo(100L * Integer.MAX_VALUE);
    }

    @Test
    void rejectsMergedQuantityBeyondTheIntegerLimit() {
        OrderItem firstItem = OrderItem.create(10L, "Air Max", 100L, Integer.MAX_VALUE);
        OrderItem secondItem = OrderItem.create(10L, "Air Max", 100L, 1);

        assertThatThrownBy(() -> Order.create(1L, List.of(firstItem, secondItem)))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getCustomMessage()).contains("합산 주문 수량");
            });
        assertThat(firstItem.getQuantity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(secondItem.getQuantity()).isEqualTo(1);
    }

    @Test
    void rejectsOverflowEvenWhenFurtherAdditionWouldWrapBackToPositive() {
        assertThatThrownBy(() -> Order.create(1L, List.of(
            OrderItem.create(10L, "Air Max", 100L, Integer.MAX_VALUE),
            OrderItem.create(10L, "Air Max", 100L, Integer.MAX_VALUE),
            OrderItem.create(10L, "Air Max", 100L, 3)
        ))).isInstanceOfSatisfying(CoreException.class, exception ->
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST)
        );
    }

    @Test
    void comparesOrderItemsByTheirSnapshotValues() {
        OrderItem item = OrderItem.create(10L, "Air Max", 100L, 2);
        OrderItem sameValue = OrderItem.create(10L, "Air Max", 100L, 2);

        assertThat(item).isEqualTo(sameValue).hasSameHashCodeAs(sameValue);
        assertThat(item).isNotEqualTo(OrderItem.create(11L, "Air Max", 100L, 2));
        assertThat(item).isNotEqualTo(OrderItem.create(10L, "Pegasus", 100L, 2));
        assertThat(item).isNotEqualTo(OrderItem.create(10L, "Air Max", 101L, 2));
        assertThat(item).isNotEqualTo(OrderItem.create(10L, "Air Max", 100L, 3));
    }

    @Test
    void savesPaymentAmountAndResultWhenConfirmed() {
        Order order = Order.create(1L, List.of(OrderItem.create(10L, "Air Max", 100L, 2)));

        order.confirm(200L);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentAmount()).isEqualTo(200L);
        assertThat(order.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
    }
}
