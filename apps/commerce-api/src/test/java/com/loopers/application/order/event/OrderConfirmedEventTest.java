package com.loopers.application.order.event;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.PaymentResult;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedEventTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.parse("2026-09-07T10:00:00.123456+09:00");
    private static final List<OrderItem> ITEMS = List.of(
        OrderItem.create(8L, "Pegasus", 200L, 1),
        OrderItem.create(7L, "Air Max", 100L, 2)
    );

    @DisplayName("확정된 주문에서 주문 ID로 유도한 eventId, 저장된 확정 시각과 상품 ID 오름차순 품목으로 이벤트를 만든다.")
    @Test
    void createsEventFromConfirmedOrder() {
        Order order = order(OrderStatus.CONFIRMED, PaymentMethod.PG, CONFIRMED_AT);

        OrderConfirmedEvent event = OrderConfirmedEvent.from(order);

        assertThat(event.getEventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(event.getOrderId()).isEqualTo(42L);
        assertThat(event.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(event.getItems()).containsExactly(
            new OrderConfirmedEvent.Item(7L, 2),
            new OrderConfirmedEvent.Item(8L, 1)
        );
    }

    @DisplayName("이벤트의 품목 목록은 바깥에서 바꿀 수 없다.")
    @Test
    void exposesUnmodifiableItems() {
        OrderConfirmedEvent event = OrderConfirmedEvent.from(order(OrderStatus.CONFIRMED, PaymentMethod.PG, CONFIRMED_AT));

        assertThatThrownBy(() -> event.getItems().add(new OrderConfirmedEvent.Item(9L, 1)))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @DisplayName("확정되지 않은 주문으로는 확정 이벤트를 만들 수 없다.")
    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DRAFT", "PAYMENT_PENDING", "PAYMENT_FAILED"})
    void rejectsUnconfirmedOrder(OrderStatus status) {
        PaymentMethod paymentMethod = status == OrderStatus.DRAFT ? null : PaymentMethod.PG;

        assertIntegrityError(order(status, paymentMethod, null));
    }

    @DisplayName("확정 시각이 없는 이전 확정 주문으로는 확정 이벤트를 만들 수 없어 소급 발행하지 않는다.")
    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void rejectsConfirmedOrderWithoutConfirmedAt(PaymentMethod paymentMethod) {
        assertIntegrityError(order(OrderStatus.CONFIRMED, paymentMethod, null));
    }

    private Order order(OrderStatus status, PaymentMethod paymentMethod, ZonedDateTime confirmedAt) {
        boolean confirmed = status == OrderStatus.CONFIRMED;
        return Order.reconstitute(42L, 1L, status, 400L, confirmed ? 400L : null,
            confirmed ? PaymentResult.SUCCESS : null, paymentMethod, confirmedAt, ITEMS);
    }

    private void assertIntegrityError(Order order) {
        assertThatThrownBy(() -> OrderConfirmedEvent.from(order))
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorType()).isEqualTo(ErrorType.INTERNAL_ERROR));
    }
}
