package com.loopers.infrastructure.event;

import com.loopers.domain.event.DomainEvent;
import com.loopers.domain.order.OrderConfirmedEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class KafkaDomainEventPublisherTest {
    private static final String TOPIC = "order-events-test";
    private static final String PRIVATE_DETAIL = "0000-0000-0000-0000 secret-payload";
    private final OrderConfirmedEvent event = new OrderConfirmedEvent("OrderConfirmed", 1, "order-confirmed:v1:42",
        42L, Instant.parse("2026-10-29T00:00:00.123456Z"), List.of(new OrderConfirmedEvent.Item(7L, 2)));
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, DomainEvent> template = mock(KafkaTemplate.class);

    @Test
    void observesTheAcknowledgedRecordLocationAndSendsTheOrderKey(CapturedOutput output) {
        var metadata = new RecordMetadata(new TopicPartition(TOPIC, 1), 24L, 0, 0L, 2, 100);
        var result = new SendResult<>(new ProducerRecord<String, DomainEvent>(TOPIC, event.key(), event), metadata);
        when(template.send(TOPIC, event.key(), event)).thenReturn(CompletableFuture.completedFuture(result));

        publisher().publish(event);

        assertThat(output.getAll()).contains("domain_event_published", "eventId=order-confirmed:v1:42",
            "eventType=OrderConfirmed", "key=42", "topic=order-events-test partition=1 offset=24");
        verify(template).send(TOPIC, "42", event);
        verifyNoMoreInteractions(template);
    }

    @Test
    void reportsFutureFailureWithoutLeakingTheExceptionBodyOrRetryingApplicationSend(CapturedOutput output) {
        when(template.send(TOPIC, event.key(), event)).thenReturn(
            CompletableFuture.failedFuture(new IllegalStateException(PRIVATE_DETAIL)));

        assertThatCode(() -> publisher().publish(event)).doesNotThrowAnyException();

        assertFailure(output);
        verify(template).send(TOPIC, event.key(), event);
        verifyNoMoreInteractions(template);
    }

    @Test
    void reportsImmediateSendFailureWithoutThrowingIntoTheCommittedOrder(CapturedOutput output) {
        when(template.send(TOPIC, event.key(), event)).thenThrow(new IllegalStateException(PRIVATE_DETAIL));

        assertThatCode(() -> publisher().publish(event)).doesNotThrowAnyException();

        assertFailure(output);
        verify(template).send(TOPIC, event.key(), event);
        verifyNoMoreInteractions(template);
    }

    @Test
    @Timeout(2)
    void returnsBeforeAcknowledgementAndObservesTheLaterSuccess(CapturedOutput output) {
        var future = new CompletableFuture<SendResult<String, DomainEvent>>();
        when(template.send(TOPIC, event.key(), event)).thenReturn(future);

        publisher().publish(event);

        assertThat(future.isDone()).isFalse();
        assertThat(output.getAll()).doesNotContain("domain_event_published", "domain_event_publication_failed");
        future.complete(new SendResult<>(new ProducerRecord<String, DomainEvent>(TOPIC, event.key(), event),
            new RecordMetadata(new TopicPartition(TOPIC, 0), 17L, 0, 0L, 2, 100)));
        assertThat(output.getAll()).contains("domain_event_published", "topic=order-events-test partition=0 offset=17");
        verify(template).send(TOPIC, event.key(), event);
        verifyNoMoreInteractions(template);
    }

    @Test
    @Timeout(2)
    void observesFailureEvenWhenItArrivesAfterReturning(CapturedOutput output) {
        var future = new CompletableFuture<SendResult<String, DomainEvent>>();
        when(template.send(TOPIC, event.key(), event)).thenReturn(future);

        publisher().publish(event);

        assertThat(future.isDone()).isFalse();
        future.completeExceptionally(new IllegalStateException(PRIVATE_DETAIL));
        assertFailure(output);
        verify(template).send(TOPIC, event.key(), event);
        verifyNoMoreInteractions(template);
    }

    @Test
    void routesDifferentEventClassesWithoutDuplicatingTheSender(CapturedOutput output) {
        var probe = new ProbeEvent("probe:1", "probe-key");
        when(template.send(TOPIC, event.key(), event)).thenReturn(new CompletableFuture<>());
        when(template.send("probe-events", probe.key(), probe)).thenReturn(new CompletableFuture<>());
        var publisher = new KafkaDomainEventPublisher(template, List.of(
            new KafkaEventRoute(OrderConfirmedEvent.class, TOPIC, true),
            new KafkaEventRoute(ProbeEvent.class, "probe-events", true)));

        publisher.publish(event);
        publisher.publish(probe);

        verify(template).send(TOPIC, "42", event);
        verify(template).send("probe-events", "probe-key", probe);
        verifyNoMoreInteractions(template);
        assertThat(output.getAll()).doesNotContain("domain_event_publication_failed");
    }

    @Test
    void anUnregisteredEventIsReportedAndNeverSentToAFallbackTopic(CapturedOutput output) {
        assertThatCode(() -> publisher().publish(new ProbeEvent("probe:1", "probe-key")))
            .doesNotThrowAnyException();

        verifyNoInteractions(template);
        assertThat(output.getAll()).contains("domain_event_publication_failed", "eventType=TestProbe",
            "eventId=probe:1", "errorType=IllegalArgumentException").doesNotContain("domain_event_published");
    }

    @Test
    void aDisabledRouteIsNotSentOrRecordedAsPublished(CapturedOutput output) {
        var publisher = new KafkaDomainEventPublisher(template, List.of(
            new KafkaEventRoute(OrderConfirmedEvent.class, TOPIC, false)));

        publisher.publish(event);

        verifyNoInteractions(template);
        assertThat(output.getAll()).contains("domain_event_publication_disabled", "eventId=order-confirmed:v1:42")
            .doesNotContain("domain_event_published");
    }

    @Test
    void duplicateRoutesAreRejectedBeforeAnySend() {
        assertThatThrownBy(() -> new KafkaDomainEventPublisher(template, List.of(
            new KafkaEventRoute(OrderConfirmedEvent.class, TOPIC, true),
            new KafkaEventRoute(OrderConfirmedEvent.class, "another-topic", true))))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(template);
    }

    @Test
    void blankTopicsAreRejectedBeforeAnySend() {
        assertThatThrownBy(() -> new KafkaEventRoute(OrderConfirmedEvent.class, " ", true))
            .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(template);
    }

    record ProbeEvent(String eventId, String key) implements DomainEvent {
        @Override
        public String eventType() {
            return "TestProbe";
        }
    }

    private KafkaDomainEventPublisher publisher() {
        return new KafkaDomainEventPublisher(template, List.of(new KafkaEventRoute(OrderConfirmedEvent.class, TOPIC, true)));
    }

    private void assertFailure(CapturedOutput output) {
        assertThat(output.getAll()).contains("domain_event_publication_failed", "eventId=order-confirmed:v1:42",
            "eventType=OrderConfirmed", "key=42", "topic=order-events-test", "errorType=IllegalStateException")
            .doesNotContain(PRIVATE_DETAIL, "domain_event_published");
    }
}
