package com.loopers.support.kafka;

import com.loopers.contract.order.OrderConfirmed;
import com.loopers.contract.order.OrderConfirmedItem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;

/** 테스트가 broker 에 싣는 OrderConfirmed 계약 메시지를 한 곳에서 만든다. */
public final class OrderConfirmedMessages {

    public static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00Z");

    private OrderConfirmedMessages() {
    }

    /** 상품 7×2, 상품 8×1 을 담은 정상 메시지. */
    public static OrderConfirmed valid(long orderId) {
        return of(orderId, "order-confirmed:v1:" + orderId, new OrderConfirmedItem(7L, 2), new OrderConfirmedItem(8L, 1));
    }

    public static OrderConfirmed of(long orderId, String eventId, OrderConfirmedItem... items) {
        return OrderConfirmed.newBuilder()
            .setEventId(eventId)
            .setOrderId(orderId)
            .setConfirmedAt(CONFIRMED_AT)
            .setItems(List.of(items))
            .build();
    }

    public static byte[] bytes(OrderConfirmed message) {
        try {
            ByteBuffer buffer = message.toByteBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            return bytes;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] validBytes(long orderId) {
        return bytes(valid(orderId));
    }
}
