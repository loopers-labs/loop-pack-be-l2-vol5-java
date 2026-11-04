package com.loopers.kafka;

import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ConsumerAwareRecordRecoverer;
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

class KafkaDlqDeliveryExperimentTest extends KafkaPurchaseExperimentSupport {
    @ParameterizedTest(name = "DLQ broker rejects first send: {0}")
    @ValueSource(booleans = {false, true})
    @Timeout(100)
    void sourceOffsetAdvancesOnlyAfterConfirmedDlqDelivery(boolean rejectFirstSend) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String topic = "dlq-source-" + suffix;
        String dlq = "dlq-destination-" + suffix;
        String group = "dlq-source-group-" + suffix;
        TopicPartition source = new TopicPartition(topic, 0);
        TopicPartition destination = new TopicPartition(dlq, 0);
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, dlq);
        byte[] invalid = ("{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,\"padding\":\""
            + "x".repeat(6_000) + "\"}").getBytes(StandardCharsets.UTF_8);
        List<String> deliveries = new CopyOnWriteArrayList<>();
        var stopped = new CountDownLatch(1);
        var dlqConfirmed = new CountDownLatch(1);
        var inspection = new CountDownLatch(1);
        var publishFailures = new AtomicInteger();
        var producerFactory = new DefaultKafkaProducerFactory<String, byte[]>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
            ProducerConfig.ACKS_CONFIG, "all",
            ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000,
            ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1_000,
            ProducerConfig.MAX_BLOCK_MS_CONFIG, 2_000));
        var template = new KafkaTemplate<>(producerFactory);

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1),
                new NewTopic(dlq, 1, (short) 1).configs(Map.of(
                    "max.message.bytes", rejectFirstSend ? "1024" : "1048576"))))
                .all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, group, source);
            assertThat(producer.send(new ProducerRecord<>(topic, "42", invalid))
                .get(10, TimeUnit.SECONDS).offset()).isZero();
            assertThat(sendOrder(producer, topic, 43L).offset()).isEqualTo(1);

            var container = container(topic, group, record -> {
                deliveries.add(record.key() + ":" + record.offset());
                adapter.handle(record);
            }, stopped);
            var publishing = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> destination);
            publishing.setFailIfSendResultIsError(true);
            publishing.setWaitForSendResultTimeout(Duration.ofSeconds(3));
            var stopping = new CommonContainerStoppingErrorHandler();
            ConsumerAwareRecordRecoverer recoverer = (record, consumer, exception) -> {
                if (!hasInvalidMessageCause(exception)) {
                    stopping.handleRemaining(exception, List.of(record), consumer, container);
                    return;
                }
                try {
                    publishing.accept(record, consumer, exception);
                } catch (RuntimeException sendFailure) {
                    publishFailures.incrementAndGet();
                    // 미정인 업무용 DLQ 장애 정책을 채택하지 않고, 실험에서는 중단해 offset을 관찰한다.
                    stopping.handleRemaining(sendFailure, List.of(record), consumer, container);
                }
            };
            var errors = new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
            errors.addNotRetryableExceptions(IllegalArgumentException.class);
            errors.setRetryListeners(new RetryListener() {
                @Override
                public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) {}

                @Override
                public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
                    dlqConfirmed.countDown();
                    releaseAfterInspection(inspection);
                }
            });
            container.setCommonErrorHandler(errors);

            try {
                container.start();
                if (rejectFirstSend) {
                    assertThat(stopped.await(25, TimeUnit.SECONDS)).isTrue();
                    assertThat(container.isRunning()).isFalse();
                    assertThat(publishFailures.get()).isEqualTo(1);
                    assertThat(committed(admin, group, source)).isZero();
                    assertThat(admin.listOffsets(Map.of(destination, OffsetSpec.latest()))
                        .all().get(10, TimeUnit.SECONDS).get(destination).offset()).isZero();
                    assertThat(activities.count()).isZero();
                    assertThat(confirmations.count()).isZero();
                    admin.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(
                        new ConfigEntry("max.message.bytes", "1048576"), AlterConfigOp.OpType.SET))))
                        .all().get(10, TimeUnit.SECONDS);
                    await(() -> admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS)
                        .get(resource).get("max.message.bytes").value().equals("1048576"));
                    container.start();
                }

                assertThat(dlqConfirmed.await(25, TimeUnit.SECONDS)).isTrue();
                // recoverer의 broker 성공 확인 뒤, 원본 offset을 commit하기 전 상태다.
                assertThat(committed(admin, group, source)).isZero();
                assertThat(activities.count()).isZero();
                assertThat(confirmations.count()).isZero();
                ConsumerRecord<String, byte[]> quarantined = readDlq(dlq);
                assertThat(quarantined.key()).isEqualTo("42");
                assertThat(quarantined.value()).isEqualTo(invalid);
                assertThat(new String(quarantined.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                    StandardCharsets.UTF_8)).isEqualTo(topic);
                assertThat(ByteBuffer.wrap(quarantined.headers()
                    .lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt()).isZero();
                assertThat(ByteBuffer.wrap(quarantined.headers()
                    .lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong()).isZero();
                assertThat(new String(quarantined.headers()
                    .lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP).value(), StandardCharsets.UTF_8))
                    .isEqualTo(group);

                inspection.countDown();
                await(() -> committed(admin, group, source) == 2L && confirmations.count() == 1);
                assertOrders(43L);
                assertThat(deliveries).containsExactlyElementsOf(rejectFirstSend
                    ? List.of("42:0", "42:0", "43:1") : List.of("42:0", "43:1"));
                assertThat(admin.listOffsets(Map.of(destination, OffsetSpec.latest()))
                    .all().get(10, TimeUnit.SECONDS).get(destination).offset()).isEqualTo(1);
                report("DLQ_DELIVERY_EXPERIMENT", Map.of("firstPublishRejected", rejectFirstSend,
                    "publishFailures", publishFailures.get(), "deliveries", deliveries,
                    "sourceOffsetBeforeDlqConfirmationRelease", 0,
                    "finalSourceOffset", committed(admin, group, source),
                    "dlqRecords", 1, "activityRows", activities.count(),
                    "confirmationRows", confirmations.count(), "originalMetadataPreserved", true));
            } finally {
                inspection.countDown();
                container.stop();
            }
        } finally {
            producerFactory.destroy();
        }
    }

    private ConsumerRecord<String, byte[]> readDlq(String topic) throws Exception {
        Map<String, Object> settings = new HashMap<>(consumerSettings());
        settings.put("group.id", "dlq-inspection-" + UUID.randomUUID());
        try (var consumer = new KafkaConsumer<String, byte[]>(settings)) {
            // DLQ의 관찰용 reader다. 원본 group의 복구 위치는 변경하지 않는다.
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

    private boolean hasInvalidMessageCause(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof IllegalArgumentException) {
                return true;
            }
        }
        return false;
    }
}
