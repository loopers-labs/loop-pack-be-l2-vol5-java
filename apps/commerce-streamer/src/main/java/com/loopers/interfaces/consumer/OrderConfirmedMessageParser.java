package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.dto.SavePurchaseActivityCommand;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.domain.purchase.PurchaseLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.List;

/**
 * {@code order-events} 의 OrderConfirmed(Avro 계약) record 를 검증해 구매 활동 기록 입력으로 바꾼다. 필드 타입·존재는
 * 스키마가 보장하고, 스키마로 표현할 수 없는 업무 규칙만 여기서 확인한다. 하나라도 어긋나면 {@link InvalidOrderEventException} 이다.
 */
@Component
public class OrderConfirmedMessageParser {

    private static final String EVENT_ID_PREFIX = "order-confirmed:v1:";

    public SavePurchaseActivityCommand parse(ConsumerRecord<String, byte[]> record) {
        try {
            return parse(record.key(), record.value());
        } catch (InvalidOrderEventException e) {
            throw new InvalidOrderEventException(position(record) + e.getMessage(), e);
        } catch (IOException | RuntimeException e) {
            throw new InvalidOrderEventException(position(record) + "OrderConfirmed record 를 해석하지 못했습니다: " + e.getMessage(), e);
        }
    }

    // 컨테이너가 멈췄을 때 어느 record 가 원인인지 바로 알 수 있게 위치를 메시지에 남긴다.
    private String position(ConsumerRecord<String, byte[]> record) {
        return "[" + record.topic() + "-" + record.partition() + "@" + record.offset() + "] ";
    }

    private SavePurchaseActivityCommand parse(String key, byte[] value) throws IOException {
        require(value != null, "record 의 값이 없습니다.");
        OrderConfirmed message = OrderConfirmed.getDecoder().decode(value);
        long orderId = message.getOrderId();
        require(String.valueOf(orderId).equals(key), "record key 와 orderId 가 다릅니다.");
        require((EVENT_ID_PREFIX + orderId).equals(message.getEventId()), "eventId 가 orderId 에서 유도한 값과 다릅니다.");
        require(!message.getItems().isEmpty(), "items 가 비어 있습니다.");
        List<PurchaseLine> lines = message.getItems().stream()
            .map(item -> {
                require(item.getQuantity() >= 1, "수량은 1 이상이어야 합니다.");
                return new PurchaseLine(item.getProductId(), item.getQuantity());
            })
            .toList();
        return new SavePurchaseActivityCommand(
            message.getEventId(), orderId, message.getConfirmedAt().atZone(ZoneOffset.UTC), lines);
    }

    private void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidOrderEventException(message);
        }
    }
}
