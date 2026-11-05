package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderOutboxDelivery;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaOrderConfirmedPublisherTest {
    private static final OrderOutboxDelivery DELIVERY =
        new OrderOutboxDelivery("order-confirmed:v1:42", "42", "{\"orderId\":42}");
    private KafkaTemplate<Object, Object> kafka;
    private KafkaOrderConfirmedPublisher publisher;
    private final String payload = DELIVERY.payload();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        kafka = mock(KafkaTemplate.class);
        publisher = new KafkaOrderConfirmedPublisher(kafka, "order-events", Duration.ofMillis(50));
    }

    @Test
    void completesOnlyAfterBrokerConfirmsStoredKeyAndPayload() {
        var metadata = new RecordMetadata(new TopicPartition("order-events", 1), 7L, 0, 0L, 0, 0);
        var record = new ProducerRecord<Object, Object>("order-events", "42", payload);
        when(kafka.send("order-events", "42", payload))
            .thenReturn(CompletableFuture.completedFuture(new SendResult<>(record, metadata)));

        assertThatCode(() -> publisher.publish(DELIVERY)).doesNotThrowAnyException();

        verify(kafka).send("order-events", "42", payload);
    }

    @Test
    void sendFailureIsReportedWithEventIdAndCause() {
        when(kafka.send("order-events", "42", payload))
            .thenReturn(CompletableFuture.failedFuture(new IllegalArgumentException("broker rejected")));

        assertThatThrownBy(() -> publisher.publish(DELIVERY)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("order-confirmed:v1:42").hasMessageContaining("broker rejected")
            .hasMessageNotContaining("orderId");
    }

    /** relay는 내용을 해석하지 않고 저장된 문자열을 그대로 보낸다. 읽을 수 없는 payload는 consumer가 DLT로 격리한다. */
    @Test
    void storedPayloadIsSentAsIsWithoutParsing() {
        var unreadable = new OrderOutboxDelivery("order-confirmed:v1:42", "42", "not-json");
        var metadata = new RecordMetadata(new TopicPartition("order-events", 1), 8L, 0, 0L, 0, 0);
        var record = new ProducerRecord<Object, Object>("order-events", "42", "not-json");
        when(kafka.send("order-events", "42", "not-json"))
            .thenReturn(CompletableFuture.completedFuture(new SendResult<>(record, metadata)));

        assertThatCode(() -> publisher.publish(unreadable)).doesNotThrowAnyException();

        verify(kafka).send("order-events", "42", "not-json");
    }

    /** send() 호출에서 바로 던진 실패(크기 초과·metadata 대기 초과 등)도 eventId와 가장 안쪽 원인을 남긴다. */
    @Test
    void immediateSendFailureIsReportedWithEventIdAndRootCause() {
        var record = new ProducerRecord<Object, Object>("order-events", "42", payload);
        when(kafka.send("order-events", "42", payload)).thenThrow(
            new KafkaProducerException(record, "Send failed", new RecordTooLargeException("record is too large")));

        assertThatThrownBy(() -> publisher.publish(DELIVERY)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("order-confirmed:v1:42")
            .hasMessageContaining("RecordTooLargeException")
            .hasMessageContaining("record is too large");
    }

    /** 대기가 끝나도 수신 여부는 모른다. 호출자가 PENDING으로 유지하도록 실패로 알린다. */
    @Test
    void unfinishedFutureAfterWaitIsReportedAsFailure() {
        CompletableFuture<SendResult<Object, Object>> pending = new CompletableFuture<>();
        when(kafka.send("order-events", "42", payload)).thenReturn(pending);

        assertThatThrownBy(() -> publisher.publish(DELIVERY)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TimeoutException");

        assertThat(pending).isNotDone();
    }
}
