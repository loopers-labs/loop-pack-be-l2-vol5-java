package com.loopers.infrastructure.order;

import com.loopers.domain.order.OrderEventDelivery;
import com.loopers.domain.order.OrderEventSender;
import com.loopers.domain.order.PendingOrderEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
class OrderEventKafkaSender implements OrderEventSender {

    private static final String METRIC = "order.event.publish";
    private static final String TYPE = "OrderConfirmed";
    private static final Set<Class<? extends Throwable>> REJECTIONS =
        Set.of(RecordTooLargeException.class, InvalidTopicException.class);
    private static final Duration DEFAULT_DELIVERY_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration WAIT_MARGIN = Duration.ofSeconds(5);

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final MeterRegistry meterRegistry;
    private final Duration waitLimit;

    OrderEventKafkaSender(KafkaTemplate<String, byte[]> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.meterRegistry = meterRegistry;
        this.waitLimit = deliveryTimeoutOf(kafkaTemplate).plus(WAIT_MARGIN);
    }

    Duration waitLimit() {
        return waitLimit;
    }

    private static Duration deliveryTimeoutOf(KafkaTemplate<String, byte[]> kafkaTemplate) {
        Object configured = kafkaTemplate.getProducerFactory().getConfigurationProperties()
            .get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);
        return configured == null
            ? DEFAULT_DELIVERY_TIMEOUT
            : Duration.ofMillis(Long.parseLong(String.valueOf(configured)));
    }

    @Override
    public List<OrderEventDelivery> send(List<PendingOrderEvent> events) {
        List<Sending> sendings = events.stream().map(this::start).toList();
        long deadline = System.nanoTime() + waitLimit.toNanos();
        return sendings.stream().map(sending -> await(sending, deadline)).toList();
    }

    private Sending start(PendingOrderEvent event) {
        try {
            return new Sending(event, kafkaTemplate.send(event.topic(), event.key(), event.payload()), null);
        } catch (RuntimeException e) {
            return new Sending(event, null, e);
        }
    }

    private OrderEventDelivery await(Sending sending, long deadline) {
        if (sending.failedToStart() != null) {
            return failed(sending.event(), sending.failedToStart());
        }
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            RecordMetadata metadata = sending.future().get(remaining, TimeUnit.NANOSECONDS).getRecordMetadata();
            log.info("주문 이벤트를 보냈다: eventId={}, topic={}, partition={}, offset={}",
                sending.event().eventId(), metadata.topic(), metadata.partition(), metadata.offset());
            count("success", "none");
            return new OrderEventDelivery.Published(sending.event());
        } catch (ExecutionException e) {
            return failed(sending.event(), e.getCause());
        } catch (TimeoutException e) {
            return failed(sending.event(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failed(sending.event(), e);
        }
    }

    private OrderEventDelivery failed(PendingOrderEvent event, Throwable ex) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
        String error = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        boolean rejected = REJECTIONS.contains(cause.getClass());
        log.error("주문 이벤트를 보내지 못했다: eventId={}, topic={}, attempt={}, blocked={}",
            event.eventId(), event.topic(), event.attemptCount() + 1, rejected, ex);
        count("failure", cause.getClass().getSimpleName());
        return rejected
            ? new OrderEventDelivery.Rejected(event, error)
            : new OrderEventDelivery.Unconfirmed(event, error);
    }

    private void count(String result, String cause) {
        meterRegistry.counter(METRIC, "type", TYPE, "result", result, "cause", cause).increment();
    }

    private record Sending(PendingOrderEvent event, CompletableFuture<SendResult<String, byte[]>> future,
                           Throwable failedToStart) {
    }
}
