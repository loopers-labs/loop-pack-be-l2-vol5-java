package com.loopers.infrastructure.order.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaProducerException;

import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;

class OrderConfirmedOutboxFailureClassifierTest {

    private final OrderConfirmedOutboxFailureClassifier classifier = new OrderConfirmedOutboxFailureClassifier();

    @Test
    void describesInnermostCauseWithoutProducerRecordPayload() {
        Throwable failure = new CompletionException(new KafkaProducerException(
            new ProducerRecord<>("orders", "1", "payload-that-must-not-be-stored"), "Failed to send",
            new RecordTooLargeException("broker record limit\nexceeded")));

        assertThat(classifier.classify(failure)).isEqualTo(OrderConfirmedOutboxFailureClassifier.FailureKind.BLOCKED);
        assertThat(classifier.safeMessage(failure)).isEqualTo("RecordTooLargeException: broker record limit exceeded");
    }

    @Test
    void describesWrappedRetryableCause() {
        Throwable failure = new KafkaProducerException(new ProducerRecord<>("orders", "1", "{}"),
            "Failed to send", new TimeoutException("broker acknowledgement timed out"));

        assertThat(classifier.classify(failure)).isEqualTo(OrderConfirmedOutboxFailureClassifier.FailureKind.RETRYABLE);
        assertThat(classifier.safeMessage(failure)).isEqualTo("TimeoutException: broker acknowledgement timed out");
    }

    @Test
    void limitsErrorLengthAndKeepsTypeWhenMessageIsAbsent() {
        assertThat(classifier.safeMessage(new IllegalStateException())).isEqualTo("IllegalStateException");
        assertThat(classifier.safeMessage(new IllegalStateException("x".repeat(600)))).hasSize(500);
    }

    @Test
    void keepsWrappedInterruptionSeparateFromFailures() {
        Throwable failure = new KafkaProducerException(new ProducerRecord<>("orders", "1", "{}"),
            "Failed to send", new InterruptedException("application stopped"));

        assertThat(classifier.classify(failure)).isEqualTo(OrderConfirmedOutboxFailureClassifier.FailureKind.INTERRUPTED);
    }
}
