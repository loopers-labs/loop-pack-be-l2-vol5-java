package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.OutboxDeliveryAttempt;
import com.loopers.domain.outbox.OutboxReceipt;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class KafkaOutboxSenderTest {
    private static final String TOPIC = "saved-order-topic";
    private static final String PAYLOAD = "{ \"eventType\":\"OrderConfirmed\", \"orderId\":42 }";
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final KafkaOutboxSender sender = new KafkaOutboxSender(kafka);
    private final OutboxDeliveryAttempt attempt = new OutboxDeliveryAttempt(1, "order-confirmed:v1:42", TOPIC, "42", PAYLOAD, 1);

    @Test
    void sendReturnsAnUnfinishedReceiptUntilTheBrokerFutureActuallySucceeds() throws Exception {
        var broker = new CompletableFuture<SendResult<String, String>>();
        when(kafka.send(TOPIC, "42", PAYLOAD)).thenReturn(broker);

        var delivery = sender.send(attempt);

        assertThat(delivery).isNotDone();
        verify(kafka).send(TOPIC, "42", PAYLOAD);
        verifyNoMoreInteractions(kafka);
        broker.complete(result(1, 24));
        assertThat(delivery.get(1, TimeUnit.SECONDS)).isEqualTo(new OutboxReceipt(TOPIC, 1, 24));
    }

    @Test
    void synchronousSubmissionFailureEscapesToTheRelay() {
        var failure = new IllegalStateException("test-only unavailable producer");
        when(kafka.send(TOPIC, "42", PAYLOAD)).thenThrow(failure);

        assertThatThrownBy(() -> sender.send(attempt)).isSameAs(failure);
    }

    @Test
    void asynchronousBrokerFailureIsNotConvertedToASuccessfulReceipt() {
        var failure = new IllegalStateException("test-only failed delivery");
        var broker = new CompletableFuture<SendResult<String, String>>();
        when(kafka.send(TOPIC, "42", PAYLOAD)).thenReturn(broker);
        var delivery = sender.send(attempt);

        broker.completeExceptionally(failure);

        assertThatThrownBy(() -> delivery.get(1, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class).hasCause(failure);
    }

    @Test
    void missingAcknowledgementMetadataIsAFailureRatherThanSyntheticSuccess() {
        when(kafka.send(TOPIC, "42", PAYLOAD)).thenReturn(CompletableFuture.completedFuture(null));

        assertThatThrownBy(() -> sender.send(attempt).get(1, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class);
    }

    @Test
    void receiptsRequireActualTopicPartitionAndOffset() {
        assertThatThrownBy(() -> new OutboxReceipt(null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxReceipt(" ", 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxReceipt(TOPIC, -1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxReceipt(TOPIC, 0, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new OutboxReceipt(TOPIC, 0, 0).offset()).isZero();
    }

    private SendResult<String, String> result(int partition, long offset) {
        return new SendResult<>(new ProducerRecord<>(TOPIC, "42", PAYLOAD),
            new RecordMetadata(new TopicPartition(TOPIC, partition), offset, 0, 0, 2, PAYLOAD.length()));
    }
}
