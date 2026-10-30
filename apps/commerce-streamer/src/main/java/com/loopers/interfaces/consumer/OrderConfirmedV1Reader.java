package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.domain.purchase.OrderConfirmation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.loopers.interfaces.consumer.OrderConfirmedJsonFields.integer;
import static com.loopers.interfaces.consumer.OrderConfirmedJsonFields.text;

@Component
public class OrderConfirmedV1Reader implements OrderConfirmedVersionReader {
    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public OrderConfirmation read(JsonNode json) {
        JsonNode items = json.path("items");
        if (!items.isArray()) {
            throw new IllegalArgumentException("확정 이벤트 품목은 배열이어야 합니다.");
        }
        List<OrderConfirmedMessageV1.Item> parsedItems = new ArrayList<>();
        for (JsonNode item : items) {
            long quantity = integer(item, "quantity");
            if (quantity > Integer.MAX_VALUE || quantity < Integer.MIN_VALUE) {
                throw new IllegalArgumentException("구매 수량이 허용 범위를 넘었습니다.");
            }
            parsedItems.add(new OrderConfirmedMessageV1.Item(integer(item, "productId"), (int) quantity));
        }
        var message = new OrderConfirmedMessageV1(text(json, "eventType"), schemaVersion(),
            text(json, "eventId"), integer(json, "orderId"), text(json, "confirmedAt"), parsedItems);
        return message.toOrderConfirmation();
    }
}
