package com.loopers.order.infrastructure.outbox;

import com.loopers.order.application.outbox.OutboxPublisher;
import com.loopers.order.application.outbox.PublishResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class OrderConfirmedOutboxRelay {

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderConfirmedOutboxRelay.class);

    private final OrderConfirmedOutboxRepository repository;
    private final OutboxPublisher publisher;
    private final Clock clock;
    private final Duration retryInterval;
    private final Duration publishTimeout;
    private final int batchSize;
    private final int maxAttempts;

    @Autowired
    public OrderConfirmedOutboxRelay(
        OrderConfirmedOutboxRepository repository,
        OutboxPublisher publisher,
        Clock clock,
        OutboxRelayProperties properties
    ) {
        this(repository, publisher, clock, properties.getPollInterval(), properties.getPublishTimeout(),
            properties.getBatchSize(), properties.getMaxAttempts());
    }

    public OrderConfirmedOutboxRelay(
        OrderConfirmedOutboxRepository repository,
        OutboxPublisher publisher,
        Clock clock,
        Duration retryInterval,
        int batchSize,
        int maxAttempts
    ) {
        this(repository, publisher, clock, retryInterval, Duration.ofSeconds(20), batchSize, maxAttempts);
    }

    public OrderConfirmedOutboxRelay(
        OrderConfirmedOutboxRepository repository,
        OutboxPublisher publisher,
        Clock clock,
        Duration retryInterval,
        Duration publishTimeout,
        int batchSize,
        int maxAttempts
    ) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
        this.retryInterval = retryInterval;
        this.publishTimeout = publishTimeout;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
    }

    public synchronized void pollOnce() {
        Instant now = clock.instant();
        List<OrderConfirmedOutbox> outboxes = repository.findReadyForRelay(now, batchSize);
        for (OrderConfirmedOutbox outbox : outboxes) {
            publish(outbox);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
        }
    }

    private void publish(OrderConfirmedOutbox outbox) {
        outbox.attemptStarted();
        repository.save(outbox);

        PublishResult result = awaitPublishResult(outbox);
        Instant completedAt = clock.instant();
        outbox.recordResult(result, completedAt, retryInterval, maxAttempts);
        repository.saveAndFlush(outbox);

        if (outbox.getStatus() == OrderConfirmedOutboxStatus.PUBLISHED) {
            LOGGER.info("Outbox published: eventId={}", outbox.getEventId());
        } else {
            LOGGER.warn("Outbox publish attempt finished: eventId={}, status={}, errorType={}",
                outbox.getEventId(), outbox.getStatus(), outbox.getLastError());
        }
    }

    private PublishResult awaitPublishResult(OrderConfirmedOutbox outbox) {
        try {
            return publisher.publish(outbox.getMessageKey(), outbox.getPayload())
                .get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return PublishResult.pending(interrupted.getClass().getSimpleName());
        } catch (TimeoutException timeout) {
            return PublishResult.pending(timeout.getClass().getSimpleName());
        } catch (ExecutionException | CompletionException failure) {
            Throwable cause = unwrap(failure);
            return PublishResult.pending(cause.getClass().getSimpleName());
        } catch (RuntimeException failure) {
            return PublishResult.failed(failure.getClass().getSimpleName());
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof ExecutionException || result instanceof CompletionException)
            && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
