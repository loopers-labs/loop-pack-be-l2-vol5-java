package com.loopers.kafka.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderConfirmedTest {
    @Test
    void 같은_상품을_합산하고_상품번호_순서로_정규화한다() {
        // Arrange
        var items = List.of(new OrderConfirmed.Item(8, 1),
                new OrderConfirmed.Item(7, 2), new OrderConfirmed.Item(7, 3));

        // Act
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-10-29T01:00:00Z"), items);

        // Assert
        assertThat(event.items()).containsExactly(
                new OrderConfirmed.Item(7, 5), new OrderConfirmed.Item(8, 1));
        assertThat(event.eventId()).isEqualTo("order-confirmed:v1:42");
    }

    @Test
    void JSON_속성과_품목_순서가_달라도_같은_확정_사실이다() throws Exception {
        // Arrange
        var mapper = new ObjectMapper().findAndRegisterModules();
        var expected = OrderConfirmed.confirmed(42, Instant.parse("2026-10-29T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2), new OrderConfirmed.Item(8, 1)));
        String json = """
                {"items":[{"quantity":1,"productId":8},{"quantity":1,"productId":7},
                          {"quantity":1,"productId":7}],
                 "confirmedAt":"2026-10-29T01:00:00Z","orderId":42,
                 "eventId":"order-confirmed:v1:42","schemaVersion":1,"eventType":"OrderConfirmed"}
                """;

        // Act
        var decoded = mapper.readValue(json, OrderConfirmed.class);

        // Assert
        assertThat(decoded).isEqualTo(expected);
    }

    @Test
    void 생성한_이벤트의_품목은_외부에서_바꿀_수_없다() {
        // Arrange
        var items = new ArrayList<>(List.of(new OrderConfirmed.Item(7, 2)));
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-10-29T01:00:00Z"), items);

        // Act
        items.clear();

        // Assert
        assertThat(event.items()).containsExactly(new OrderConfirmed.Item(7, 2));
        assertThatThrownBy(() -> event.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 다른_주문번호를_메시지_key로_사용하면_거절한다() {
        // Arrange
        var event = OrderConfirmed.confirmed(42, Instant.parse("2026-10-29T01:00:00Z"),
                List.of(new OrderConfirmed.Item(7, 2)));

        // Act & Assert
        event.validateKey("42");
        assertThatThrownBy(() -> event.validateKey("43")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event.validateKey(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 지원하지_않는_버전이나_유효하지_않은_품목은_거절한다() {
        // Arrange
        var time = Instant.parse("2026-10-29T01:00:00Z");
        var item = new OrderConfirmed.Item(7, 2);

        // Act & Assert
        assertThatThrownBy(() -> new OrderConfirmed("OrderConfirmed", 2, "event", 42, time, List.of(item)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderConfirmed.confirmed(42, time, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmed.Item(7, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderConfirmed.Item(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderConfirmed.confirmed(42, time,
                List.of(new OrderConfirmed.Item(7, Integer.MAX_VALUE), item)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderConfirmed.confirmed(42, time.plusNanos(1), List.of(item)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
