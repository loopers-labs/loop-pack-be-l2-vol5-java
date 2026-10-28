package com.loopers.purchase.adapter.in.kafka;

import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.config.PurchaseActivityKafkaConfig;
import com.loopers.support.KafkaTestSupport;
import com.loopers.support.StreamerIntegrationTestSupport;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.KafkaTopicReader;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.loopers.support.KafkaTestSupport.orderConfirmedJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실제 Spring listener (W6 D-7, ADR-W6-06). record listener · MANUAL_IMMEDIATE · 재시도 3회 뒤 DLT.
 * 정상 저장·no-op은 ack되어 committed offset이 n+1로 간다. 실패 record는 업무 완료로 건너뛰지 않는다 — DB는 rollback, DLT에 원문이 남는다.
 */
class OrderConfirmedListenerIntegrationTest extends StreamerIntegrationTestSupport {

    private static final String AT = "2026-10-28T14:03:12.123456+09:00";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    @Qualifier(KafkaConfig.STRING_TEMPLATE)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    @Autowired
    @Qualifier(PurchaseActivityKafkaConfig.RECORD_LISTENER)
    private ConcurrentKafkaListenerContainerFactory<String, String> recordListenerFactory;

    private RecordMetadata send(String key, String value) throws Exception {
        return kafkaTemplate.send(new ProducerRecord<>(orderEventsTopic, key, value)).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    private Long committed(RecordMetadata metadata) {
        return KafkaTestSupport.committedOffset(purchaseActivityGroup, new TopicPartition(metadata.topic(), metadata.partition()));
    }

    private void awaitCommitted(RecordMetadata metadata, long expected) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(committed(metadata)).isEqualTo(expected));
    }

    @DisplayName("설정은 Kafka가 실제로 읽는 값으로 enable.auto.commit=false · auto.offset.reset=earliest이고, 컨테이너는 MANUAL_IMMEDIATE · 이 용도의 group이다.")
    @Test
    void containerConfigAsKafkaReadsIt() {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(PurchaseActivityKafkaConfig.LISTENER_ID);
        ConsumerConfig config = new ConsumerConfig(recordListenerFactory.getConsumerFactory().getConfigurationProperties());

        assertThat(container).isNotNull();
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        assertThat(container.getGroupId()).isEqualTo(purchaseActivityGroup);
        assertThat(config.getBoolean(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isFalse();
        assertThat(config.getString(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
        assertThat(config.getList(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG)).hasSize(3);
    }

    @DisplayName("정상 record를 받으면, ")
    @Nested
    class Normal {

        @DisplayName("C-1 api와 함께 쓰는 견본(order-confirmed-v1.json)을 그대로 소비해 활동 2행을 저장하고 ack한다 — committed offset n+1.")
        @Test
        void consumesTheSharedFixture() throws Exception {
            String fixture = KafkaTopicReader.orderConfirmedFixture();

            RecordMetadata sent = send("42", fixture);

            await().atMost(WAIT).untilAsserted(() -> assertThat(activityCount()).isEqualTo(2));
            assertThat(jdbcTemplate.queryForObject("SELECT payload FROM consumed_order_confirmation WHERE order_id = 42", String.class))
                .isEqualTo(fixture);
            awaitCommitted(sent, sent.offset() + 1);
        }

        @DisplayName("C-6 같은 원문이 다시 오면(재발행) no-op으로 ack한다 — 활동은 2행 그대로, committed offset은 두 번째 record 다음으로 간다.")
        @Test
        void redeliveredSameFactIsAckedWithoutEffect() throws Exception {
            String eventId = UUID.randomUUID().toString();
            String json = orderConfirmedJson(eventId, 42L, AT, new long[][]{{7, 2}, {8, 1}});
            send("42", json);
            await().atMost(WAIT).untilAsserted(() -> assertThat(activityCount()).isEqualTo(2));

            RecordMetadata second = send("42", json);

            awaitCommitted(second, second.offset() + 1);
            assertThat(activityCount()).isEqualTo(2);
            assertThat(consumedCount()).isEqualTo(1);
        }
    }

    @DisplayName("처리가 실패하면, ")
    @Nested
    class Failure {

        @DisplayName("C-7 E-3 DB 장애가 계속되면 같은 자리에서 1+3번 시도한 뒤 DLT로 보내고 원래 offset을 n+1로 commit한다(DB는 0행). 오류를 없애고 DLT 원문을 다시 넣으면 1번 저장되고, 한 번 더 넣어도 no-op이다.")
        @Test
        void retriesThenDeadLettersAndReplaysOnce() throws Exception {
            String eventId = UUID.randomUUID().toString();
            String json = orderConfirmedJson(eventId, 42L, AT, new long[][]{{7, 2}, {8, 1}});
            doThrow(new DataAccessResourceFailureException("DB 장애 주입"))
                .when(purchaseActivityPort).saveAll(argThat(message -> message.eventId().equals(eventId)), anyString());

            // act: 실패하는 record
            RecordMetadata sent = send("42", json);

            // assert: 재시도 소진 → DLT, 원래 topic은 n+1 commit, DB는 비어 있다
            List<ConsumerRecord<String, String>> dead = KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(),
                orderEventsTopic + PurchaseActivityKafkaConfig.DLT_SUFFIX, record -> record.value().equals(json), 1, WAIT);
            assertThat(dead).singleElement().satisfies(record -> {
                assertThat(record.key()).isEqualTo("42");
                assertThat(header(record, "kafka_dlt-original-offset")).isEqualTo(sent.offset());
                assertThat(header(record, "kafka_dlt-original-partition")).isEqualTo(sent.partition());
                assertThat(new String(record.headers().lastHeader("kafka_dlt-exception-message").value(), StandardCharsets.UTF_8))
                    .contains("DB 장애 주입");
            });
            awaitCommitted(sent, sent.offset() + 1);
            verify(purchaseActivityPort, times(1 + PurchaseActivityKafkaConfig.MAX_RETRIES))
                .saveAll(argThat(message -> message.eventId().equals(eventId)), anyString());
            assertThat(consumedCount()).isZero();
            assertThat(activityCount()).isZero();

            // act: 오류 제거 → 사람이 DLT 원문을 key·value 그대로 원래 topic에 다시 넣는다 (운영에서는 런북의 도구로)
            reset(purchaseActivityPort);
            ConsumerRecord<String, String> deadRecord = dead.get(0);
            RecordMetadata replayed = send(deadRecord.key(), deadRecord.value());

            // assert: 1번 저장
            awaitCommitted(replayed, replayed.offset() + 1);
            assertThat(consumedCount()).isEqualTo(1);
            assertThat(activityCount()).isEqualTo(2);

            // act + assert: 재생을 한 번 더 해도 no-op
            RecordMetadata replayedAgain = send(deadRecord.key(), deadRecord.value());
            awaitCommitted(replayedAgain, replayedAgain.offset() + 1);
            assertThat(consumedCount()).isEqualTo(1);
            assertThat(activityCount()).isEqualTo(2);
        }

        @DisplayName("C-6 충돌(같은 eventId · 다른 수량)은 재시도해도 같으므로 재시도 없이 DLT로 보낸다 — 활동은 처음 2행 그대로.")
        @Test
        void conflictGoesToDeadLetterWithoutRetry() throws Exception {
            String eventId = UUID.randomUUID().toString();
            send("42", orderConfirmedJson(eventId, 42L, AT, new long[][]{{7, 2}, {8, 1}}));
            await().atMost(WAIT).untilAsserted(() -> assertThat(activityCount()).isEqualTo(2));
            String conflicting = orderConfirmedJson(eventId, 42L, AT, new long[][]{{7, 3}, {8, 1}});

            RecordMetadata sent = send("42", conflicting);

            List<ConsumerRecord<String, String>> dead = KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(),
                orderEventsTopic + PurchaseActivityKafkaConfig.DLT_SUFFIX, record -> record.value().equals(conflicting), 1, Duration.ofSeconds(10));
            assertThat(dead).singleElement().satisfies(record ->
                assertThat(new String(record.headers().lastHeader("kafka_dlt-exception-cause-fqcn").value(), StandardCharsets.UTF_8))
                    .contains("ConflictingOrderConfirmationException"));
            awaitCommitted(sent, sent.offset() + 1);
            assertThat(activityRepository.findByOrderIdOrderByProductId(42L))
                .extracting(row -> row.getQuantity()).containsExactly(2, 1);
        }

        @DisplayName("계약에 맞지 않는 원문(수량 0)은 재시도 없이 DLT로 보내고 저장하지 않는다.")
        @Test
        void invalidPayloadGoesToDeadLetter() throws Exception {
            String invalid = orderConfirmedJson(UUID.randomUUID().toString(), 77L, AT, new long[][]{{7, 0}});

            RecordMetadata sent = send("77", invalid);

            List<ConsumerRecord<String, String>> dead = KafkaTopicReader.read(KafkaTestContainersConfig.bootstrapServers(),
                orderEventsTopic + PurchaseActivityKafkaConfig.DLT_SUFFIX, record -> record.value().equals(invalid), 1, Duration.ofSeconds(10));
            assertThat(dead).hasSize(1);
            awaitCommitted(sent, sent.offset() + 1);
            assertThat(consumedCount()).isZero();
        }
    }

    private static long header(ConsumerRecord<String, String> record, String name) {
        byte[] value = record.headers().lastHeader(name).value();
        return value.length == Integer.BYTES ? ByteBuffer.wrap(value).getInt() : ByteBuffer.wrap(value).getLong();
    }
}
