package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.contract.OrderConfirmedContract;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Price;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

@SpringBootTest
class OrderConfirmedMessageTest {

    private static final Instant PLACED_AT = Instant.parse("2026-10-30T00:00:00Z");

    private final ObjectMapper objectMapper;

    @Autowired
    OrderConfirmedMessageTest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private static OrderItem item(Long productId, int quantity) {
        return OrderItem.of(productId, "상품" + productId, Price.of(1_000), OrderQuantity.of(quantity));
    }

    private static OrderConfirmed confirmed() {
        Order draft = Order.draft(1L, List.of(item(8L, 1), item(7L, 2)), PLACED_AT);
        Order pending = Order.restore(42L, 1L, draft.getItems(), draft.getTotalAmount(), PLACED_AT,
            OrderStatus.PENDING, null, null);
        pending.completePayment(Instant.parse("2026-10-30T01:02:03.12Z"));
        return (OrderConfirmed) pending.pullEvents().get(0);
    }

    @DisplayName("EVT-003, EVT-008: 앱의 ObjectMapper 로 만든 바이트가 공유 계약 예시와 필드, 값, 시각 표기까지 같다")
    @Test
    void matchesContractExample() throws Exception {
        byte[] bytes = OrderConfirmedMessage.from(confirmed()).toBytes(objectMapper);

        JSONAssert.assertEquals(OrderConfirmedContract.example(), new String(bytes, StandardCharsets.UTF_8),
            JSONCompareMode.STRICT);
    }
}
