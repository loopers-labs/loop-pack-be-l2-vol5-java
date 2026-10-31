package com.loopers.kafka;

import com.loopers.application.purchase.ConflictingOrderConfirmationException;
import com.loopers.domain.purchase.OrderConfirmationConflictException;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.FixedBackOff;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 eventId의 사실 충돌에서 DLQ 후 계속 처리와 중단을 비교한다. 업무용 선택은 미정이다. */
class KafkaConflictingFactExperimentTest extends KafkaPurchaseExperimentSupport {
    @ParameterizedTest(name = "conflicting fact handling: {0}")
    @EnumSource(Handling.class)
    @Timeout(100)
    void conflictNeverRewritesOriginalPurchaseButHandlingChangesSubsequentProgress(Handling handling)
        throws Exception {
        String suffix = UUID.randomUUID().toString();
        String topic = "conflicting-fact-source-" + suffix;
        String dlq = "conflicting-fact-dlq-" + suffix;
        String group = "conflicting-fact-" + suffix;
        TopicPartition source = new TopicPartition(topic, 0);
        TopicPartition destination = new TopicPartition(dlq, 0);
        byte[] conflicting = new String(payload(42L), StandardCharsets.UTF_8)
            .replace("\"productId\":7,\"quantity\":5", "\"productId\":7,\"quantity\":6")
            .getBytes(StandardCharsets.UTF_8);
        assertThat(conflicting).isNotEqualTo(payload(42L));
        List<String> deliveries = new CopyOnWriteArrayList<>();
        var conflictCaught = new CountDownLatch(1);
        var permitConflictHandling = new CountDownLatch(1);
        var dlqConfirmed = new CountDownLatch(1);
        var permitRecoveryCommit = new CountDownLatch(1);
        var nextOrderReached = new CountDownLatch(1);
        var permitNextOrder = new CountDownLatch(1);
        var conflicts = new AtomicInteger();
        var stopped = new AtomicInteger();
        var ackCalls = new AtomicInteger();
        AcknowledgingMessageListener<String, byte[]> listener = (record, acknowledgment) -> {
            deliveries.add(record.key() + ":" + record.offset());
            if (record.key().equals("43")) {
                nextOrderReached.countDown();
                releaseAfterInspection(permitNextOrder);
            }
            try {
                adapter.handle(record, () -> {
                    acknowledgment.acknowledge();
                    ackCalls.incrementAndGet();
                });
            } catch (ConflictingOrderConfirmationException conflict) {
                conflicts.incrementAndGet();
                conflictCaught.countDown();
                releaseAfterInspection(permitConflictHandling);
                throw conflict;
            }
        };
        var producerFactory = new DefaultKafkaProducerFactory<String, byte[]>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
            ProducerConfig.ACKS_CONFIG, "all", ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000,
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000, ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000));

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1), new NewTopic(dlq, 1, (short) 1)))
                .all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, source);
            assertThat(sendOrder(producer, topic, 42L).offset()).isZero();
            assertThat(producer.send(new ProducerRecord<>(topic, "42", conflicting))
                .get(10, TimeUnit.SECONDS).offset()).isEqualTo(1);
            assertThat(sendOrder(producer, topic, 43L).offset()).isEqualTo(2);

            var container = container(topic, group, listener, new CountDownLatch(1));
            container.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
            container.setApplicationEventPublisher(event -> {
                if (event instanceof ConsumerStoppedEvent) {
                    stopped.incrementAndGet();
                }
            });
            if (handling == Handling.DLQ_AND_CONTINUE) {
                var publishing = new DeadLetterPublishingRecoverer(new KafkaTemplate<>(producerFactory),
                    (record, exception) -> destination);
                publishing.setFailIfSendResultIsError(true);
                publishing.setWaitForSendResultTimeout(Duration.ofSeconds(3));
                var stopping = new CommonContainerStoppingErrorHandler();
                ConsumerAwareRecordRecoverer recoverer = (record, consumer, exception) -> {
                    if (!hasConflictCause(exception)) {
                        stopping.handleRemaining(exception, List.of(record), consumer, container);
                        return;
                    }
                    try {
                        publishing.accept(record, consumer, exception);
                    } catch (RuntimeException publishFailure) {
                        // DLQ 장애의 업무용 정책은 미정. 이 실험의 예상 밖 실패는 중단해 드러낸다.
                        stopping.handleRemaining(publishFailure, List.of(record), consumer, container);
                    }
                };
                var errors = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
                errors.addNotRetryableExceptions(OrderConfirmationConflictException.class);
                errors.setCommitRecovered(true);
                errors.setRetryListeners(new RetryListener() {
                    @Override
                    public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) {}

                    @Override
                    public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
                        dlqConfirmed.countDown();
                        releaseAfterInspection(permitRecoveryCommit);
                    }
                });
                container.setCommonErrorHandler(errors);
            }

            try {
                container.start();
                assertThat(conflictCaught.await(20, TimeUnit.SECONDS)).isTrue();
                assertOrders(42L);
                assertThat(committed(admin, group, source)).isEqualTo(1);
                assertThat(ackCalls.get()).isEqualTo(1);
                List<Long> originalIds = originalRowIds();
                Long confirmationId = confirmations.findByEventId("order-confirmed:v1:42")
                    .orElseThrow().getId();
                permitConflictHandling.countDown();

                if (handling == Handling.DLQ_AND_CONTINUE) {
                    assertThat(dlqConfirmed.await(20, TimeUnit.SECONDS)).isTrue();
                    assertThat(committed(admin, group, source)).isEqualTo(1);
                    assertOrders(42L);
                    var quarantined = readDlq(dlq);
                    assertThat(quarantined.key()).isEqualTo("42");
                    assertThat(quarantined.value()).isEqualTo(conflicting);
                    assertThat(new String(quarantined.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC)
                        .value(), StandardCharsets.UTF_8)).isEqualTo(topic);
                    assertThat(ByteBuffer.wrap(quarantined.headers()
                        .lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt()).isZero();
                    assertThat(ByteBuffer.wrap(quarantined.headers()
                        .lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong()).isEqualTo(1);
                    assertThat(new String(quarantined.headers()
                        .lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).value(), StandardCharsets.UTF_8))
                        .isEqualTo(group);
                    permitRecoveryCommit.countDown();
                    assertThat(nextOrderReached.await(20, TimeUnit.SECONDS)).isTrue();
                    assertThat(committed(admin, group, source)).isEqualTo(2);
                    assertOrders(42L);
                    permitNextOrder.countDown();
                    await(() -> committed(admin, group, source) == 3L && ackCalls.get() == 2);
                    assertOrders(42L, 43L);
                    assertThat(deliveries).containsExactly("42:0", "42:1", "43:2");
                    assertThat(conflicts.get()).isEqualTo(1);
                } else {
                    await(() -> stopped.get() == 1);
                    assertThat(container.isRunning()).isFalse();
                    assertThat(committed(admin, group, source)).isEqualTo(1);
                    assertOrders(42L);
                    assertThat(ackCalls.get()).isEqualTo(1);
                    // 메시지를 수정하지 않은 재시작은 같은 충돌로 다시 중단된다.
                    container.start();
                    await(() -> stopped.get() == 2);
                    assertThat(container.isRunning()).isFalse();
                    assertThat(committed(admin, group, source)).isEqualTo(1);
                    assertThat(deliveries).containsExactly("42:0", "42:1", "42:1");
                    assertThat(conflicts.get()).isEqualTo(2);
                    assertThat(nextOrderReached.getCount()).isEqualTo(1);
                    assertOrders(42L);
                }
                assertThat(originalRowIds()).isEqualTo(originalIds);
                assertThat(confirmations.findByEventId("order-confirmed:v1:42").orElseThrow().getId())
                    .isEqualTo(confirmationId);
                long dlqRecords = admin.listOffsets(Map.of(destination, OffsetSpec.latest()))
                    .all().get(10, TimeUnit.SECONDS).get(destination).offset();
                assertThat(dlqRecords).isEqualTo(handling == Handling.DLQ_AND_CONTINUE ? 1L : 0L);
                report("CONFLICTING_FACT_EXPERIMENT", Map.of("handling", handling.name(),
                    "deliveries", deliveries, "conflicts", conflicts.get(), "ackCalls", ackCalls.get(),
                    "finalCommittedOffset", committed(admin, group, source), "dlqRecords", dlqRecords,
                    "confirmationRows", confirmations.count(), "activityRows", activities.count(),
                    "originalOrder42RowsUnchanged", true, "stops", stopped.get()));
            } finally {
                permitConflictHandling.countDown();
                permitRecoveryCommit.countDown();
                permitNextOrder.countDown();
                container.stop();
            }
        } finally {
            producerFactory.destroy();
        }
    }

    private List<Long> originalRowIds() {
        return activities.findAll().stream().filter(row -> row.getOrderId().equals(42L))
            .map(ProductPurchaseActivity::getId).sorted().toList();
    }

    private ConsumerRecord<String, byte[]> readDlq(String topic) throws Exception {
        Map<String, Object> settings = new HashMap<>(consumerSettings());
        settings.put("group.id", "conflicting-fact-inspection-" + UUID.randomUUID());
        try (var consumer = new KafkaConsumer<String, byte[]>(settings)) {
            consumer.assign(List.of(new TopicPartition(topic, 0)));
            List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
            await(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
                return !records.isEmpty();
            });
            assertThat(records).hasSize(1);
            return records.getFirst();
        }
    }

    private boolean hasConflictCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof OrderConfirmationConflictException) {
                return true;
            }
        }
        return false;
    }

    enum Handling { DLQ_AND_CONTINUE, STOP_AND_INSPECT }
}
