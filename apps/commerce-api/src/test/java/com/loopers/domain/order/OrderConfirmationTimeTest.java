package com.loopers.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.loopers.support.error.CoreException;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class OrderConfirmationTimeTest {
    @Test
    void PG_주문을_확정하면_최초_확정_시각을_기록한다() {
        // Arrange
        Order order = Order.create(1, List.of(new Order.RequestedItem(7, 2, 3_500)));
        order.awaitPayment();
        Instant confirmedAt = Instant.parse("2026-10-29T01:00:00.123456Z");
        assertThat(order.getConfirmedAt()).isNull();

        // Act
        order.completePayment(confirmedAt);

        // Assert
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt()).isEqualTo(confirmedAt);
    }

    @Test
    void 이미_확정된_주문을_다시_확정하려고_해도_최초_시각은_바뀌지_않는다() {
        // Arrange
        Order order = Order.create(1, List.of(new Order.RequestedItem(7, 2, 3_500)));
        order.awaitPayment();
        Instant firstConfirmation = Instant.parse("2026-10-29T01:00:00Z");
        order.completePayment(firstConfirmation);

        // Act
        assertThrows(CoreException.class, () -> order.completePayment(firstConfirmation.plusSeconds(60)));

        // Assert
        assertThat(order.getConfirmedAt()).isEqualTo(firstConfirmation);
    }
}
