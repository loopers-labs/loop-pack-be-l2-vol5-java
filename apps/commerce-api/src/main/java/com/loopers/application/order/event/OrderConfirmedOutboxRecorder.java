package com.loopers.application.order.event;

import com.loopers.application.order.dto.OrderConfirmedInfo;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.contract.order.OrderConfirmedItem;
import com.loopers.domain.order.Order;
import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.ZonedDateTime;

/** 확정된 주문의 사실을 PENDING Outbox 로 남긴다. 트랜잭션을 열지 않고 호출한 쪽의 확정 트랜잭션에 참여한다. */
@RequiredArgsConstructor
@Component
public class OrderConfirmedOutboxRecorder {

    private static final String EVENT_TYPE = "OrderConfirmed";
    private static final String SCHEMA_VERSION = "v1";

    private final OutboxService outboxService;

    public void record(Order order, ZonedDateTime recordedAt) {
        OrderConfirmedInfo confirmed = OrderConfirmedInfo.from(order);
        outboxService.save(Outbox.record(
            confirmed.eventId(),
            confirmed.orderId(),
            EVENT_TYPE,
            SCHEMA_VERSION,
            String.valueOf(confirmed.orderId()),
            toAvroBytes(confirmed),
            recordedAt
        ));
    }

    private static byte[] toAvroBytes(OrderConfirmedInfo confirmed) {
        OrderConfirmed message = OrderConfirmed.newBuilder()
            .setEventId(confirmed.eventId())
            .setOrderId(confirmed.orderId())
            .setConfirmedAt(confirmed.confirmedAt().toInstant())
            .setItems(confirmed.items().stream()
                .map(item -> new OrderConfirmedItem(item.productId(), item.quantity()))
                .toList())
            .build();
        try {
            ByteBuffer buffer = message.toByteBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("주문 확정 payload 직렬화에 실패했습니다.", e);
        }
    }
}
