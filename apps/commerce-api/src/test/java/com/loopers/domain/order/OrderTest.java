package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

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
    void acceptsOrderTotalAtTheLongLimit() {
        Order order = Order.create(1L, itemsAtTheLongTotalLimit());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getTotalAmount()).isEqualTo(Long.MAX_VALUE);
        assertThat(order.getItems()).allSatisfy(item -> {
            assertThat(item.getUnitPrice()).isBetween(1L, 100_000_000L);
            assertThat(item.getQuantity()).isPositive();
        });
    }

    @Test
    void rejectsOrderTotalOneUnitBeyondTheLongLimit() {
        List<OrderItem> items = itemsAtTheLongTotalLimit();
        items.add(OrderItem.create((long) items.size() + 1L, "Extra product", 1L, 1));

        assertThatThrownBy(() -> Order.create(1L, items))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getCustomMessage()).contains("주문 총액");
            });
    }

    @Test
    void rejectsTotalOverflowAcrossProductsWithValidPriceAndQuantity() {
        List<OrderItem> items = IntStream.rangeClosed(1, 43)
            .mapToObj(productId -> OrderItem.create(
                (long) productId, "Product " + productId, 100_000_000L, Integer.MAX_VALUE
            ))
            .toList();

        assertThatThrownBy(() -> Order.create(1L, items))
            .isInstanceOfSatisfying(CoreException.class, exception -> {
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                assertThat(exception.getCustomMessage()).contains("주문 총액");
            });
    }

    @Test
    void savesPaymentAmountAndResultWhenConfirmed() {
        Order order = Order.create(1L, List.of(OrderItem.create(10L, "Air Max", 100L, 2)));

        order.confirm();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getPaymentAmount()).isEqualTo(200L);
        assertThat(order.getPaymentResult()).isEqualTo(PaymentResult.SUCCESS);
    }

    private List<OrderItem> itemsAtTheLongTotalLimit() {
        long unitPrice = 100_000_000L;
        long fullItemAmount = unitPrice * Integer.MAX_VALUE;
        int fullItemCount = Math.toIntExact(Long.MAX_VALUE / fullItemAmount);
        List<OrderItem> items = new ArrayList<>();
        for (long productId = 1L; productId <= fullItemCount; productId++) {
            items.add(OrderItem.create(productId, "Product " + productId, unitPrice, Integer.MAX_VALUE));
        }
        long remainingAmount = Long.MAX_VALUE % fullItemAmount;
        items.add(OrderItem.create(
            (long) fullItemCount + 1L, "Remaining quantity", unitPrice,
            Math.toIntExact(remainingAmount / unitPrice)
        ));
        items.add(OrderItem.create(
            (long) fullItemCount + 2L, "Remaining amount", remainingAmount % unitPrice, 1
        ));
        return items;
    }
}
