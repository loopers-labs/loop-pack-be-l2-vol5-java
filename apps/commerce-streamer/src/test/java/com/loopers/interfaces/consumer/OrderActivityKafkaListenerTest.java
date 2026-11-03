package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.PurchaseActivityHandler;
import com.loopers.domain.purchase.OrderConfirmation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OrderActivityKafkaListenerTest {
    private final PurchaseActivityHandler handler = mock(PurchaseActivityHandler.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final OrderConfirmedEventParser parser = new OrderConfirmedEventParser(new ObjectMapper());
    private final OrderActivityKafkaListener listener = new OrderActivityKafkaListener(parser, handler);

    @Test
    void acknowledgesOnlyAfterTheHandlerSuccessfullyReturns() {
        when(handler.consume(any())).thenReturn(PurchaseActivityHandler.Result.APPLIED);

        listener.listen(record("42"), ack);

        var order = inOrder(handler, ack);
        order.verify(handler).consume(any(OrderConfirmation.class));
        order.verify(ack).acknowledge();
        order.verifyNoMoreInteractions();
    }

    @Test
    void aMatchingDuplicateIsAlsoAcknowledgedAfterVerification() {
        when(handler.consume(any())).thenReturn(PurchaseActivityHandler.Result.DUPLICATE);

        listener.listen(record("42"), ack);

        var order = inOrder(handler, ack);
        order.verify(handler).consume(any(OrderConfirmation.class));
        order.verify(ack).acknowledge();
        order.verifyNoMoreInteractions();
    }

    @Test
    void handlerFailureEscapesWithoutAcknowledging() {
        IllegalStateException failure = new IllegalStateException("test-only persistence failure");
        when(handler.consume(any())).thenThrow(failure);

        assertThatThrownBy(() -> listener.listen(record("42"), ack)).isSameAs(failure);

        verifyNoInteractions(ack);
    }

    @Test
    void invalidWireInputNeverReachesTheHandlerOrAcknowledgement() {
        assertThatThrownBy(() -> listener.listen(record("43"), ack)).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(handler, ack);
    }

    private ConsumerRecord<String, byte[]> record(String key) {
        String payload = """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:42",
             "orderId":42,"confirmedAt":"2026-10-29T00:00:00Z","items":[{"productId":7,"quantity":2}]}
            """;
        return new ConsumerRecord<>("order-events", 0, 0, key, payload.getBytes(StandardCharsets.UTF_8));
    }
}
