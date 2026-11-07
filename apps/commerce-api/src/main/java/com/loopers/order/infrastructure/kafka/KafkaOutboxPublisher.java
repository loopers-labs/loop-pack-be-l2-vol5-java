package com.loopers.order.infrastructure.kafka;

import com.loopers.order.application.outbox.OutboxPublisher;
import com.loopers.order.application.outbox.PublishResult;
import org.apache.kafka.common.errors.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@Component
public class KafkaOutboxPublisher implements OutboxPublisher {

    private final KafkaTemplate<Object, Object> kafkaTemplate;
    private final String topic;

    public KafkaOutboxPublisher(
        KafkaTemplate<Object, Object> kafkaTemplate,
        @Value("${order-confirmed.kafka.topic:order-events}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    @Override
    public CompletableFuture<PublishResult> publish(String messageKey, String payload) {
        try {
            return kafkaTemplate.send(topic, messageKey, payload)
                .handle((result, failure) -> failure == null
                    ? PublishResult.SUCCESS
                    : toPublishResult(unwrap(failure)));
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(toPublishResult(unwrap(failure)));
        }
    }

    private PublishResult toPublishResult(Throwable failure) {
        String errorType = failure.getClass().getSimpleName();
        if (failure instanceof TimeoutException || failure instanceof java.util.concurrent.TimeoutException) {
            return PublishResult.pending(errorType);
        }
        return PublishResult.failed(errorType);
    }

    private Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof CompletionException || result instanceof java.util.concurrent.ExecutionException)
            && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
