package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.OrderConfirmationHandler;
import com.loopers.application.purchase.OrderConfirmationCommand;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderConfirmedRecordAdapterTest {
    private final OrderConfirmationHandler handler = mock(OrderConfirmationHandler.class);
    private final OrderConfirmedRecordAdapter adapter = new OrderConfirmedRecordAdapter(
        new OrderConfirmedMessageParser(new ObjectMapper()), handler);

    @Test
    void delegatesValidatedFactsToTheTransactionHandler() {
        adapter.handle(record("42"));

        ArgumentCaptor<OrderConfirmationCommand> purchase = ArgumentCaptor.forClass(OrderConfirmationCommand.class);
        verify(handler).handle(purchase.capture());
        assertThat(purchase.getValue().eventId()).isEqualTo("order-confirmed:v1:42");
        assertThat(purchase.getValue().items()).containsExactly(
            new OrderConfirmationCommand.Item(7L, 5), new OrderConfirmationCommand.Item(8L, 3));
    }

    @Test
    void invalidRecordDoesNotCallBusinessHandler() {
        assertThatThrownBy(() -> adapter.handle(record("43")))
            .isInstanceOf(InvalidOrderConfirmedMessageException.class);

        verifyNoInteractions(handler);
    }

    @Test
    void businessFailurePropagatesToTheFutureListener() {
        var failure = new IllegalStateException("test: DB failed");
        doThrow(failure).when(handler).handle(any(OrderConfirmationCommand.class));

        assertThatThrownBy(() -> adapter.handle(record("42"))).isSameAs(failure);
    }

    @Test
    void acknowledgesOnlyAfterTheBusinessHandlerReturns() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        adapter.handle(record("42"), acknowledgment);

        var order = inOrder(handler, acknowledgment);
        order.verify(handler).handle(any(OrderConfirmationCommand.class));
        order.verify(acknowledgment).acknowledge();
        order.verifyNoMoreInteractions();
    }

    @Test
    void invalidRecordNeverAcknowledges() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);

        assertThatThrownBy(() -> adapter.handle(record("43"), acknowledgment))
            .isInstanceOf(InvalidOrderConfirmedMessageException.class);

        verifyNoInteractions(handler, acknowledgment);
    }

    @Test
    void businessFailureNeverAcknowledges() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var failure = new IllegalStateException("test: DB failed");
        doThrow(failure).when(handler).handle(any(OrderConfirmationCommand.class));

        assertThatThrownBy(() -> adapter.handle(record("42"), acknowledgment)).isSameAs(failure);

        verifyNoInteractions(acknowledgment);
    }

    @Test
    void acknowledgmentFailurePropagatesAfterBusinessSuccess() {
        Acknowledgment acknowledgment = mock(Acknowledgment.class);
        var failure = new IllegalStateException("test: Kafka offset commit failed");
        doThrow(failure).when(acknowledgment).acknowledge();

        assertThatThrownBy(() -> adapter.handle(record("42"), acknowledgment)).isSameAs(failure);

        verify(handler).handle(any(OrderConfirmationCommand.class));
        verify(acknowledgment).acknowledge();
    }

    @Test
    void missingAcknowledgmentFailsBeforeAnyBusinessWork() {
        assertThatThrownBy(() -> adapter.handle(record("42"), null))
            .isInstanceOf(NullPointerException.class).hasMessage("acknowledgment must not be null");

        verifyNoInteractions(handler);
    }

    private ConsumerRecord<String, byte[]> record(String key) {
        return new ConsumerRecord<>("order-events-test", 0, 12L, key,
            OrderConfirmedMessageParserTest.JSON.getBytes(StandardCharsets.UTF_8));
    }
}
