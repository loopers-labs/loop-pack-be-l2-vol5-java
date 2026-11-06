package com.loopers.interfaces.consumer.ordering;

import com.loopers.application.ordering.service.RecordOrderEventFailureService;
import com.loopers.application.ordering.usecase.ConsumeOrderConfirmationUseCase;
import com.loopers.domain.ordering.model.OrderConfirmation;
import com.loopers.domain.ordering.model.OrderConfirmationConflictException;
import com.loopers.domain.ordering.repository.OrderEventFailureRepository.Failure;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConfirmedKafkaConsumer {
    public static final String LISTENER_ID = "order-confirmed";
    private static final int MAX_ATTEMPTS = 3;

    private final OrderConfirmedMessageParser parser = new OrderConfirmedMessageParser();
    private final ConsumeOrderConfirmationUseCase consumer;
    private final RecordOrderEventFailureService failures;

    @KafkaListener(id = LISTENER_ID, topics = "${order-events.topic:order-events}",
        groupId = "${order-events.group:purchase-activity}", containerFactory = "orderConfirmedListenerFactory")
    public void consume(ConsumerRecord<String, byte[]> record, Acknowledgment acknowledgment,
                        Consumer<?, ?> kafkaConsumer) {
        String group = kafkaConsumer.groupMetadata().groupId();
        OrderConfirmation confirmation;
        try {
            var message = parser.parse(record.key(), record.value());
            confirmation = new OrderConfirmation(message.eventId(), message.orderId(), message.confirmedAt(),
                message.items().stream().map(item -> new OrderConfirmation.Item(item.productId(), item.quantity())).toList());
        } catch (IllegalArgumentException exception) {
            archive(record, group, null, "INVALID_CONTRACT");
            acknowledgment.acknowledge();
            return;
        }

        process(record, group, confirmation);
        acknowledgment.acknowledge();
    }

    private void process(ConsumerRecord<String, byte[]> record, String group, OrderConfirmation confirmation) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                var result = consumer.execute(confirmation);
                log.info("OrderConfirmed handled eventId={} orderId={} topic={} partition={} offset={} result={}",
                    confirmation.eventId(), confirmation.orderId(), record.topic(), record.partition(), record.offset(), result);
                return;
            } catch (OrderConfirmationConflictException exception) {
                archive(record, group, confirmation.eventId(), "FACT_CONFLICT");
                return;
            } catch (RuntimeException exception) {
                if (exception instanceof TransactionSystemException) {
                    throw stopped(record, "TRANSACTION_OUTCOME_UNKNOWN");
                }
                if (attempt == MAX_ATTEMPTS) {
                    if (isDatabaseUnavailable(exception)) {
                        throw stopped(record, "DATABASE_UNAVAILABLE");
                    }
                    archive(record, group, confirmation.eventId(), "PROCESSING_FAILED");
                    return;
                }
                log.warn("OrderConfirmed retry topic={} partition={} offset={} attempt={}",
                    record.topic(), record.partition(), record.offset(), attempt);
                waitBeforeRetry(record);
            }
        }
    }

    private void archive(ConsumerRecord<String, byte[]> record, String group, UUID eventId, String reason) {
        try {
            failures.execute(new Failure(group, record.topic(), record.partition(), record.offset(),
                record.key(), record.value(), eventId, reason));
        } catch (RuntimeException exception) {
            throw stopped(record, "FAILURE_ARCHIVE_UNAVAILABLE");
        }
        log.warn("OrderConfirmed archived topic={} partition={} offset={} reason={}",
            record.topic(), record.partition(), record.offset(), reason);
    }

    private void waitBeforeRetry(ConsumerRecord<String, byte[]> record) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(800, 1_201));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw stopped(record, "RETRY_INTERRUPTED");
        }
    }

    private boolean isDatabaseUnavailable(RuntimeException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof CannotCreateTransactionException
                || (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))) {
                return true;
            }
        }
        return false;
    }

    private IllegalStateException stopped(ConsumerRecord<String, byte[]> record, String reason) {
        return new IllegalStateException("OrderConfirmed stopped topic=%s partition=%d offset=%d reason=%s"
            .formatted(record.topic(), record.partition(), record.offset(), reason));
    }
}
