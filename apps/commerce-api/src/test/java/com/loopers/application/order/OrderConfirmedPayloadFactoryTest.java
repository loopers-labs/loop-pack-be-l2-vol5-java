package com.loopers.application.order;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.product.ProductId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedPayloadFactoryTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-29T01:00:00Z");

    @Test
    @DisplayName("확정 주문 42(상품 8×1, 7×2)로 key 42·eventId order-confirmed:v1:42·확정 시각·productId 오름차순 품목을 만든다")
    void createsPayloadFromConfirmedOrder() {
        Order order = Order.restore(42, 1, List.of(item(8, 1, 3000), item(7, 2, 2000)), Order.Status.CONFIRMED, CONFIRMED_AT);

        OrderConfirmedPayload payload = OrderConfirmedPayloadFactory.create(order);

        assertThat(payload).isEqualTo(new OrderConfirmedPayload("42", "order-confirmed:v1:42", 42, CONFIRMED_AT,
            List.of(new OrderConfirmedPayload.Item(7, 2), new OrderConfirmedPayload.Item(8, 1))));
    }

    @Test
    @DisplayName("같은 주문으로 다시 만들어도 eventId·확정 시각·품목이 같은 payload다")
    void sameOrderMakesSamePayload() {
        Order order = Order.restore(42, 1, List.of(item(7, 2, 2000)), Order.Status.CONFIRMED, CONFIRMED_AT);
        Order reloaded = Order.restore(42, 1, List.of(item(7, 2, 2500)), Order.Status.CONFIRMED, CONFIRMED_AT);

        assertThat(OrderConfirmedPayloadFactory.create(reloaded)).isEqualTo(OrderConfirmedPayloadFactory.create(order));
    }

    @ParameterizedTest(name = "{0} 주문")
    @EnumSource(value = Order.Status.class, names = "CONFIRMED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("CONFIRMED가 아닌 주문으로는 payload를 만들지 않는다")
    void rejectsUnconfirmedOrder(Order.Status status) {
        Order order = Order.restore(42, 1, List.of(item(7, 2, 2000)), status, null);

        assertThatThrownBy(() -> OrderConfirmedPayloadFactory.create(order)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("확정 시각이 없는 옛 CONFIRMED 주문으로는 payload를 만들지 않는다(소급 발행 없음)")
    void rejectsLegacyConfirmedOrderWithoutConfirmedAt() {
        Order order = Order.restore(42, 1, List.of(item(7, 2, 2000)), Order.Status.CONFIRMED, null);

        assertThatThrownBy(() -> OrderConfirmedPayloadFactory.create(order)).isInstanceOf(IllegalStateException.class);
    }

    private static OrderItem item(long productId, int quantity, long price) {
        return new OrderItem(new ProductId(productId), new Quantity(quantity), new Money(price));
    }
}
