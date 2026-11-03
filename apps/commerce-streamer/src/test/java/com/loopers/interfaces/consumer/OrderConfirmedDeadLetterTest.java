package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.DeadLetterPublisher;
import com.loopers.domain.purchase.PermanentEventException;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.infrastructure.purchase.KafkaDeadLetterPublisher;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 영구 오류의 DLT 전환 (설계 D12 적용). 충돌·깨진 JSON record 는 원본 key·value bytes 그대로 {topic}.DLT 로 가고 ack 되며,
 * DLT 발행이 실패하면 일시 오류처럼 재시도한 뒤 ack 없이 컨테이너가 멈춘다. DLT topic 은 앱이 아니라 테스트가 만든다.
 */
@SpringBootTest(properties = {
    "purchase-activity.topic=" + OrderConfirmedDeadLetterTest.TOPIC,
    "purchase-activity.group-id=" + OrderConfirmedDeadLetterTest.GROUP
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderConfirmedDeadLetterTest {

    static final String TOPIC = "order-events-OrderConfirmedDeadLetterTest";
    static final String GROUP = "purchase-activity-OrderConfirmedDeadLetterTest";
    private static final String DLT = TOPIC + KafkaDeadLetterPublisher.DLT_SUFFIX;
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final AdminClient ADMIN;

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
        KafkaTestContainersConfig.createTopic(DLT, 1);
        ADMIN = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()));
    }

    @MockitoSpyBean
    private DeadLetterPublisher deadLetterPublisherSpy;

    @Autowired
    private PurchaseActivityRepository purchaseActivityRepository;
    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeAdmin() {
        ADMIN.close();
    }

    private static String orderJson(String eventId, long orderId, int quantity) {
        return """
            {"eventType": "OrderConfirmed", "schemaVersion": 1, "eventId": "%s", "orderId": %d,
             "confirmedAt": "2026-09-07T01:00:00Z", "items": [{"productId": 7, "quantity": %d}]}
            """.formatted(eventId, orderId, quantity);
    }

    @Order(1)
    @DisplayName("같은 eventId 의 다른 수량(충돌)은 원본 bytes·key 그대로 DLT 1건으로 가고, 헤더에 원인·기존/새 fingerprint·원본 위치가 있으며, 활동 추가 없이 ack 된다.")
    @Test
    void sendsConflictToDeadLetterTopic_andAcks() throws Exception {
        // arrange - 주문 48 = 상품 7 × 3 정상 저장
        RecordMetadata original = send("48", orderJson("order-confirmed:v1:48", 48L, 3));
        awaitCommittedPast(original);

        // act - 같은 eventId, 수량 4
        byte[] conflictBytes = orderJson("order-confirmed:v1:48", 48L, 4).getBytes(StandardCharsets.UTF_8);
        RecordMetadata conflict = send("48", conflictBytes);
        awaitCommittedPast(conflict);

        // assert - DLT 1건: 원본 그대로 + 헤더
        List<ConsumerRecord<String, byte[]>> deadLetters = readDeadLetters();
        assertThat(deadLetters).hasSize(1);
        ConsumerRecord<String, byte[]> deadLetter = deadLetters.get(0);
        assertThat(deadLetter.key()).isEqualTo("48");
        assertThat(deadLetter.value()).isEqualTo(conflictBytes);
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_EXCEPTION_CLASS)).isEqualTo(PermanentEventException.class.getName());
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_EXCEPTION_MESSAGE)).contains("같은 eventId 에 다른 사실");
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_ORIGINAL_PARTITION)).isEqualTo(String.valueOf(conflict.partition()));
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_ORIGINAL_OFFSET)).isEqualTo(String.valueOf(conflict.offset()));
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_EXISTING_FINGERPRINT)).isEqualTo("48|2026-09-07T01:00:00Z|7:3");
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_NEW_FINGERPRINT)).isEqualTo("48|2026-09-07T01:00:00Z|7:4");

        // 구매 활동은 처음 저장한 사실 그대로
        List<ProductPurchaseActivity> activities = purchaseActivityRepository.findActivitiesByOrderId(48L);
        assertThat(activities).hasSize(1);
        assertThat(activities.get(0).getQuantity()).isEqualTo(3);
        assertThat(container().isRunning()).isTrue();
    }

    @Order(2)
    @DisplayName("깨진 JSON 도 DLT 로 가며 fingerprint 헤더는 없다.")
    @Test
    void sendsBrokenJsonToDeadLetterTopic() throws Exception {
        byte[] broken = "{\"eventType\": ".getBytes(StandardCharsets.UTF_8);
        RecordMetadata record = send("49", broken);
        awaitCommittedPast(record);

        ConsumerRecord<String, byte[]> deadLetter = readDeadLetters().stream()
            .filter(r -> "49".equals(r.key()))
            .findFirst()
            .orElseThrow();
        assertThat(deadLetter.value()).isEqualTo(broken);
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_EXCEPTION_CLASS)).isEqualTo(PermanentEventException.class.getName());
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_EXCEPTION_MESSAGE)).contains("JSON");
        assertThat(header(deadLetter, KafkaDeadLetterPublisher.HEADER_ORIGINAL_OFFSET)).isEqualTo(String.valueOf(record.offset()));
        assertThat(deadLetter.headers().lastHeader(KafkaDeadLetterPublisher.HEADER_EXISTING_FINGERPRINT)).isNull();
        assertThat(deadLetter.headers().lastHeader(KafkaDeadLetterPublisher.HEADER_NEW_FINGERPRINT)).isNull();
        assertThat(purchaseActivityRepository.findProcessedByOrderId(49L)).isEmpty();
    }

    @Order(3)
    @DisplayName("DLT 발행이 계속 실패하면 재시도 소진 뒤 ack 없이 컨테이너가 멈추고 offset 은 그 record 앞에 머문다 (DLT 에 추가 없음).")
    @Test
    void stopsWithoutAck_whenDeadLetterPublishKeepsFailing() throws Exception {
        // arrange - 주문 50 정상 저장 뒤 DLT 발행 실패 주입
        RecordMetadata original = send("50", orderJson("order-confirmed:v1:50", 50L, 1));
        awaitCommittedPast(original);
        int deadLettersBefore = readDeadLetters().size();
        doThrow(new RuntimeException("실험: DLT 발행 실패")).when(deadLetterPublisherSpy).publish(any(), any());

        // act - 충돌 record → DLT 발행 실패 → 재시도 소진 → 중단
        RecordMetadata conflict = send("50", orderJson("order-confirmed:v1:50", 50L, 2));
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200)).until(() -> !container().isRunning());

        // assert
        verify(deadLetterPublisherSpy, times(3)).publish(any(), any());
        assertThat(committedOffset()).isEqualTo(conflict.offset());
        assertThat(readDeadLetters()).hasSize(deadLettersBefore);
        assertThat(purchaseActivityRepository.findActivitiesByOrderId(50L)).singleElement()
            .extracting(ProductPurchaseActivity::getQuantity).isEqualTo(1);
    }

    private MessageListenerContainer container() {
        return registry.getListenerContainers().stream()
            .filter(c -> GROUP.equals(c.getGroupId()))
            .findFirst()
            .orElseThrow();
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static RecordMetadata send(String key, String json) throws Exception {
        return send(key, json.getBytes(StandardCharsets.UTF_8));
    }

    private static RecordMetadata send(String key, byte[] value) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            return producer.send(new ProducerRecord<>(TOPIC, key, value)).get(10, TimeUnit.SECONDS);
        }
    }

    /** DLT partition 0 을 처음부터 끝까지 읽는다. */
    private static List<ConsumerRecord<String, byte[]>> readDeadLetters() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(DLT, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            long deadline = System.currentTimeMillis() + 10_000;
            while (consumer.position(partition) < end && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private static long committedOffset() throws Exception {
        OffsetAndMetadata committed = ADMIN.listConsumerGroupOffsets(GROUP)
            .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(PARTITION);
        return committed == null ? -1 : committed.offset();
    }

    private static void awaitCommittedPast(RecordMetadata record) {
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(committedOffset()).isEqualTo(record.offset() + 1));
    }
}
