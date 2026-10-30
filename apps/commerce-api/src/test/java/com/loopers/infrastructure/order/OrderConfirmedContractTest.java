package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.application.order.OrderConfirmedPayloadFactory;
import com.loopers.domain.common.Money;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.product.ProductId;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

// commerce-streamer의 같은 이름 테스트와 같은 fixture(modules/kafka testFixtures)를 쓴다. 한쪽이 형식을 바꾸면 다른 쪽이 깨진다.
class OrderConfirmedContractTest {
    static final String FIXTURE = "contracts/order-confirmed-v1.json";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @DisplayName("주문 42(상품 7×2·8×1, 2026-10-29T01:00:00Z 확정)로 만든 메시지는 공유 계약 fixture와 정규화해서 같고 key는 42다")
    void messageMatchesSharedFixture() throws IOException {
        OrderConfirmedPayload payload = OrderConfirmedPayloadFactory.create(order42());

        String message = OrderConfirmedMessageSerializer.serialize(payload);

        assertThat(JSON.readTree(message)).isEqualTo(fixture());
        assertThat(payload.key()).isEqualTo("42");
    }

    @Test
    @DisplayName("메시지에는 계약 필드만 있고 사용자·금액·카드 정보가 없다")
    void messageHasOnlyContractFields() throws IOException {
        JsonNode message = JSON.readTree(OrderConfirmedMessageSerializer.serialize(OrderConfirmedPayloadFactory.create(order42())));

        assertThat(fieldNames(message)).containsExactlyInAnyOrder("eventType", "schemaVersion", "eventId", "orderId",
            "confirmedAt", "items");
        for (JsonNode item : message.get("items")) {
            assertThat(fieldNames(item)).containsExactlyInAnyOrder("productId", "quantity");
        }
    }

    private static Order order42() {
        return Order.restore(42, 1, List.of(item(7, 2, 2000), item(8, 1, 3000)), Order.Status.CONFIRMED,
            Instant.parse("2026-10-29T01:00:00Z"));
    }

    private static OrderItem item(long productId, int quantity, long price) {
        return new OrderItem(new ProductId(productId), new Quantity(quantity), new Money(price));
    }

    private static JsonNode fixture() throws IOException {
        try (InputStream input = new ClassPathResource(FIXTURE).getInputStream()) {
            return JSON.readTree(input);
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<String>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
