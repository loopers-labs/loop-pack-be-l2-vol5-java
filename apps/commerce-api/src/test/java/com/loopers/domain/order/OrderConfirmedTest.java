package com.loopers.domain.order;

import com.loopers.domain.product.Price;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderConfirmedTest {

    private static final Long USER = 1L;
    private static final Instant PLACED_AT = Instant.parse("2026-10-30T00:00:00Z");
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-30T01:02:03.120000Z");

    private static OrderItem item(Long productId, int quantity) {
        return OrderItem.of(productId, "상품" + productId, Price.of(1_000), OrderQuantity.of(quantity));
    }

    private static Order pending(Long id, OrderItem... items) {
        Order draft = Order.draft(USER, List.of(items), PLACED_AT);
        return Order.restore(id, USER, draft.getItems(), draft.getTotalAmount(), PLACED_AT, OrderStatus.PENDING,
            null, null);
    }

    private static OrderConfirmed confirm(Order order) {
        order.completePayment(CONFIRMED_AT);
        List<Object> events = order.pullEvents();
        assertThat(events).hasSize(1);
        return (OrderConfirmed) events.get(0);
    }

    @DisplayName("EVT-004: eventId 는 주문 id 에서 유도한다")
    @Test
    void derivesEventIdFromOrderId() {
        OrderConfirmed event = confirm(pending(42L, item(7L, 2)));

        assertThat(event.eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(event.orderId()).isEqualTo(42L);
    }

    @DisplayName("EVT-006: 이벤트의 확정 시각은 주문에 남은 확정 시각이다")
    @Test
    void carriesStoredConfirmedAt() {
        Order order = pending(42L, item(7L, 2));

        OrderConfirmed event = confirm(order);

        assertThat(event.confirmedAt()).isEqualTo(order.getConfirmedAt());
    }

    @DisplayName("EVT-007: 품목은 상품마다 하나로 합쳐져 productId 순서로 담긴다")
    @Test
    void carriesOneItemPerProductInProductOrder() {
        OrderConfirmed event = confirm(pending(42L, item(8L, 1), item(7L, 2), item(8L, 2)));

        assertThat(event.items()).containsExactly(
            new OrderConfirmed.Item(7L, 2),
            new OrderConfirmed.Item(8L, 3)
        );
    }

    @DisplayName("확정해야만 이벤트가 생긴다. 결제 시작 전이나 결제 실패에는 없다")
    @Test
    void raisesOnlyOnConfirmation() {
        Order failed = pending(42L, item(7L, 2));
        failed.failPayment();

        assertThat(pending(43L, item(7L, 2)).pullEvents()).isEmpty();
        assertThat(failed.pullEvents()).isEmpty();
    }

    @DisplayName("꺼낸 이벤트는 비워져, 같은 주문을 다시 저장해도 두 번 나가지 않는다")
    @Test
    void pullingEmptiesTheEvents() {
        Order order = pending(42L, item(7L, 2));
        order.completePayment(CONFIRMED_AT);

        List<Object> first = order.pullEvents();
        List<Object> second = order.pullEvents();

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
    }
}
