package com.loopers.application.ordering.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.infrastructure.messaging.ordering.OrderConfirmedEventJson;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class OrderConfirmedContractTest {
    private static final Instant CONFIRMED_AT = Instant.parse("2026-11-04T01:00:00.123456Z");
    private static final UUID EVENT_ID = UUID.fromString("e321d56a-59b3-4cf1-a7d0-6238451af280");

    @Test
    @DisplayName("저장된 PG 확정 기록과 정규화한 품목을 공통 JSON 계약으로 직렬화한다")
    void encodesPersistedConfirmation() throws Exception {
        var event = OrderConfirmedEvent.from(confirmed(PaymentMethod.PG, EVENT_ID));
        var mapper = new ObjectMapper();

        try (var fixture = new ClassPathResource("order-confirmed-v1.json").getInputStream()) {
            assertThat(mapper.readTree(OrderConfirmedEventJson.encode(event))).isEqualTo(mapper.readTree(fixture));
        }
        assertThatThrownBy(() -> event.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("POINT 또는 UUID가 없는 과거 PG 확정에서 이벤트를 만들지 않는다")
    void rejectsRecordsWithoutEventIdentity() {
        assertThatThrownBy(() -> OrderConfirmedEvent.from(confirmed(PaymentMethod.PG, null)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderConfirmedEvent.from(confirmed(PaymentMethod.POINT, null)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private Order confirmed(PaymentMethod method, UUID eventId) {
        var record = OrderRecord.restore(1L, 1L, 3000L, OrderRecordStatus.PAID, CONFIRMED_AT, method, eventId);
        var items = List.of(OrderItem.restore(8L, "B", 1000L, 1, 1000L),
            OrderItem.restore(7L, "A", 1000L, 1, 1000L), OrderItem.restore(7L, "A", 1000L, 1, 1000L));
        return Order.restore(42L, 1L, OrderStatus.CONFIRMED, items, 3000L, CONFIRMED_AT, record);
    }
}
