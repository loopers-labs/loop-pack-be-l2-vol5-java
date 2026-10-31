package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.OrderConfirmationListener;
import com.loopers.domain.order.OrderConfirmed;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class KafkaOrderConfirmedPublisherTest {
    private static final OrderConfirmed EVENT = new OrderConfirmed(42L, Instant.parse("2026-10-25T01:00:00.123456Z"),
        List.of(new OrderConfirmed.Item(7L, 2), new OrderConfirmed.Item(8L, 1)));
    private KafkaTemplate<Object, Object> kafka;
    private KafkaOrderConfirmedPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        publisher = new KafkaOrderConfirmedPublisher(kafka, "order-events");
    }

    @Test
    void serializesExactVersionOneContractAndUsesOrderIdKey() throws Exception {
        CompletableFuture<SendResult<Object, Object>> future = new CompletableFuture<>();
        when(kafka.send(eq("order-events"), eq("42"), any(OrderConfirmedMessage.class))).thenReturn(future);

        publisher.publish(EVENT);

        var message = ArgumentCaptor.forClass(OrderConfirmedMessage.class);
        verify(kafka).send(eq("order-events"), eq("42"), message.capture());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode actual = mapper.readTree(mapper.writeValueAsString(message.getValue()));
        assertThat(actual).isEqualTo(mapper.readTree("""
            {
              "eventType":"OrderConfirmed", "schemaVersion":1, "eventId":"order-confirmed:v1:42",
              "orderId":42, "confirmedAt":"2026-10-25T01:00:00.123456Z",
              "items":[{"productId":7,"quantity":2},{"productId":8,"quantity":1}]
            }
            """));
        assertThat(future).isNotDone();
    }

    @Test
    void recordsSuccessOnlyWhenSendFutureCompletesWithBrokerMetadata(CapturedOutput output) {
        CompletableFuture<SendResult<Object, Object>> future = new CompletableFuture<>();
        when(kafka.send(eq("order-events"), eq("42"), any(OrderConfirmedMessage.class))).thenReturn(future);

        publisher.publish(EVENT);
        assertThat(output.getOut()).doesNotContain("Order event published:");
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("order-events", 1), 17, 0, 0L, 2, 100);
        future.complete(new SendResult<>(new ProducerRecord<>("order-events", "42", "unused"), metadata));

        assertThat(output.getAll()).contains("Order event published:", "eventId=order-confirmed:v1:42",
            "topic=order-events", "partition=1", "offset=17");
    }

    @Test
    void recordsAsynchronousFailureWithEventIdentity(CapturedOutput output) {
        CompletableFuture<SendResult<Object, Object>> future = new CompletableFuture<>();
        when(kafka.send(eq("order-events"), eq("42"), any(OrderConfirmedMessage.class))).thenReturn(future);

        publisher.publish(EVENT);
        future.completeExceptionally(new IllegalStateException("test: broker unavailable"));

        assertThat(output.getAll()).contains("Order event publication failed:",
            "eventId=order-confirmed:v1:42", "orderId=42", "test: broker unavailable");
        assertThat(output.getAll()).doesNotContain("Order event published:");
    }

    @Test
    void synchronousSendFailureIsObservedByPostCommitListenerWithoutEscaping(CapturedOutput output) {
        when(kafka.send(eq("order-events"), eq("42"), any(OrderConfirmedMessage.class)))
            .thenThrow(new IllegalStateException("test: metadata unavailable"));

        new OrderConfirmationListener(publisher).onConfirmed(EVENT);

        assertThat(output.getAll()).contains("Order event publication failed after commit:",
            "eventId=order-confirmed:v1:42", "test: metadata unavailable");
    }
}
