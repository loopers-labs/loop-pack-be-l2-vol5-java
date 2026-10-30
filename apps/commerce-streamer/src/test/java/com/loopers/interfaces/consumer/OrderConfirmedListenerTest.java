package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.application.purchase.PurchaseActivityRecorder;
import com.loopers.domain.purchase.InvalidOrderEventException;
import com.loopers.domain.purchase.OrderEventConflictException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * listener가 "DB 반영이 끝난 뒤에만 ack하고, 실패는 삼키지 않는다"는 순서를 지키는지 확인한다.
 * 실제 broker 위에서의 offset 결과는 KafkaDeliveryTest들이 확인하고, 여기서는 그 전제인 호출 순서만 본다.
 */
class OrderConfirmedListenerTest {

    private static final String VALID = """
        {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42","orderId":42,
         "confirmedAt":"2026-09-07T01:00:00Z","items":[{"productId":7,"quantity":2}]}
        """;

    private final PurchaseActivityHandler handler = mock(PurchaseActivityHandler.class);
    private final Acknowledgment acknowledgment = mock(Acknowledgment.class);
    private final OrderConfirmedListener listener = new OrderConfirmedListener(new OrderConfirmedParser(new ObjectMapper()), handler);

    private static ConsumerRecord<String, byte[]> record(String key, String body) {
        return new ConsumerRecord<>("order-events", 0, 5L, key, body.getBytes(StandardCharsets.UTF_8));
    }

    @DisplayName("handler가 끝난 뒤에 한 번 ack한다.")
    @Test
    void acksAfterHandlerCompletes() {
        // arrange
        when(handler.consume(any())).thenReturn(PurchaseActivityRecorder.Result.RECORDED);

        // act
        listener.listen(record("42", VALID), acknowledgment);

        // assert
        InOrder order = inOrder(handler, acknowledgment);
        order.verify(handler).consume(any());
        order.verify(acknowledgment).acknowledge();
    }

    @DisplayName("handler가 실패하거나 충돌을 알리면 예외를 그대로 올리고 ack하지 않는다.")
    @Test
    void doesNotAck_whenHandlerFails() {
        // arrange
        doThrow(new IllegalStateException("DB 오류")).when(handler).consume(any());

        // act & assert
        assertThatThrownBy(() -> listener.listen(record("42", VALID), acknowledgment)).isInstanceOf(IllegalStateException.class);
        verify(acknowledgment, never()).acknowledge();

        // arrange
        doThrow(new OrderEventConflictException("충돌")).when(handler).consume(any());

        // act & assert
        assertThatThrownBy(() -> listener.listen(record("42", VALID), acknowledgment)).isInstanceOf(OrderEventConflictException.class);
        verify(acknowledgment, never()).acknowledge();
    }

    @DisplayName("계약을 지키지 않은 record는 handler를 부르지도 ack하지도 않고 오류를 올린다.")
    @Test
    void doesNotAck_whenPayloadIsInvalid() {
        // act & assert
        assertThatThrownBy(() -> listener.listen(record("43", VALID), acknowledgment)).isInstanceOf(InvalidOrderEventException.class);
        verify(handler, never()).consume(any());
        verify(acknowledgment, never()).acknowledge();
    }
}
