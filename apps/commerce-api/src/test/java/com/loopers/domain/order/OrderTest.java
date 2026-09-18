package com.loopers.domain.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

class OrderTest {
    @Test
    void capturesDraftSnapshotsAndCalculatesTotalWithoutChangingProducts() {
        Product first = new Product(new Brand("브랜드"), "첫 상품", 100, 3);
        Product second = new Product(new Brand("브랜드"), "다른 상품", 200, 4);

        Order order = Order.create(new User(1), List.of(new OrderItem(first, 2), new OrderItem(second, 3)));

        assertThat(order.getTotalAmount()).isEqualTo(800);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(order.getPaidAmount()).isNull();
        assertThat(order.getConfirmedAt()).isNull();
        first.update("변경됨", 999);
        assertThat(order.getItems().getFirst().getProductName()).isEqualTo("첫 상품");
        assertThat(order.getItems().getFirst().getUnitPrice()).isEqualTo(100);
        assertThat(first.getStockQuantity()).isEqualTo(3);
    }

    @Test
    void rejectsTotalOverflow() {
        Product product = new Product(new Brand("브랜드"), "최대", Long.MAX_VALUE, 2);
        Product other = new Product(new Brand("브랜드"), "추가", 1, 1);
        assertOrderReason(() -> Order.create(new User(1),
            List.of(new OrderItem(product, 1), new OrderItem(other, 1))),
            OrderException.Reason.AMOUNT_LIMIT_EXCEEDED);
    }

    @Test
    void rejectsSubtotalOverflow() {
        Product product = new Product(new Brand("브랜드"), "최대", Long.MAX_VALUE, 2);
        assertOrderReason(() -> Order.create(new User(1), List.of(new OrderItem(product, 2))),
            OrderException.Reason.AMOUNT_LIMIT_EXCEEDED);
    }

    @Test
    void acceptsExactlyMaximumTotal() {
        Product product = new Product(new Brand("브랜드"), "최대", Long.MAX_VALUE, 1);
        assertThat(Order.create(new User(1), List.of(new OrderItem(product, 1))).getTotalAmount())
            .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsMissingOrEmptyItems() {
        assertAll(
            () -> assertOrderReason(() -> Order.create(new User(1), List.of()), OrderException.Reason.INVALID_ITEMS),
            () -> assertOrderReason(() -> Order.create(new User(1), null), OrderException.Reason.INVALID_ITEMS)
        );
    }

    @Test
    void rejectsOtherOwnerWithSameNotFoundReason() {
        Order order = order();
        order.requireOwner(1);
        assertOrderReason(() -> order.requireOwner(2), OrderException.Reason.ORDER_NOT_FOUND);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void confirmsOnceAndPreservesFirstPaymentAndTime() {
        Order order = order();
        ZonedDateTime first = ZonedDateTime.parse("2026-09-18T00:00:00Z");
        order.confirm(first);
        order.confirm(first.plusHours(1));
        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
            () -> assertThat(order.getPaidAmount()).isEqualTo(200),
            () -> assertThat(order.getConfirmedAt()).isEqualTo(first)
        );
    }

    @Test
    void doesNotExposeMutableItemCollection() {
        Order order = order();
        assertThatThrownBy(() -> order.getItems().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(order.getItems()).hasSize(1);
    }

    private Order order() {
        return Order.create(new User(1),
            List.of(new OrderItem(new Product(new Brand("브랜드"), "상품", 100, 3), 2)));
    }

    private void assertOrderReason(Runnable action, OrderException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(OrderException.class,
            error -> assertThat(error.getReason()).isEqualTo(reason));
    }
}
