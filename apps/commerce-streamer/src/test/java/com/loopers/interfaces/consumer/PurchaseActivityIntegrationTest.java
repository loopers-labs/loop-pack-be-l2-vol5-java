package com.loopers.interfaces.consumer;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.infrastructure.purchase.KafkaDeadLetterPublisher;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 실제 broker → 실제 listener → 실제 DB 로 구매 활동 소비를 검증한다 (과제 3·4절, 설계 D11~D14).
 * 이 클래스만의 topic·group suffix 로 다른 테스트 클래스와 격리하고(📄 p.1·p.8), 검증은 이 클래스의 orderId 로 한정한다.
 * "처리 완료 후 ack" 는 group 의 committed offset 이 record 다음 위치로 간 것으로 판단한다.
 */
@SpringBootTest(properties = {
    "purchase-activity.topic=" + PurchaseActivityIntegrationTest.TOPIC,
    "purchase-activity.group-id=" + PurchaseActivityIntegrationTest.GROUP
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PurchaseActivityIntegrationTest {

    static final String TOPIC = "order-events-PurchaseActivityIntegrationTest";
    static final String GROUP = "purchase-activity-PurchaseActivityIntegrationTest";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.parse("2026-09-07T01:00:00Z");

    private static final AdminClient ADMIN;

    static {
        // 컨텍스트(리스너)가 뜨기 전에 격리 topic 을 만든다. 앱은 topic 을 만들지 않는다 (설계 7절 결정).
        ADMIN = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()));
        try {
            ADMIN.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("격리 topic 생성 실패: " + TOPIC, e);
        }
        // 충돌 record 가 가는 DLT 도 앱이 아니라 테스트가 만든다 (설계 D12)
        KafkaTestContainersConfig.createTopic(TOPIC + KafkaDeadLetterPublisher.DLT_SUFFIX, 1);
    }

    @Autowired
    private PurchaseActivityRepository purchaseActivityRepository;
    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean
    private PurchaseActivityRepository repositorySpy;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeAdmin() {
        ADMIN.close();
    }

    private static String orderJson(String eventId, long orderId, String items) {
        return """
            {"eventType": "OrderConfirmed", "schemaVersion": 1, "eventId": "%s", "orderId": %d,
             "confirmedAt": "2026-09-07T01:00:00Z", "items": [%s]}
            """.formatted(eventId, orderId, items);
    }

    private static RecordMetadata send(long orderId, String json) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            return producer.send(new ProducerRecord<>(TOPIC, String.valueOf(orderId), json.getBytes(StandardCharsets.UTF_8)))
                .get(10, TimeUnit.SECONDS);
        }
    }

    /** group 이 commit 한 "다음 읽을 위치". 아직 commit 이 없으면 -1. */
    private static long committedOffset() throws Exception {
        OffsetAndMetadata committed = ADMIN.listConsumerGroupOffsets(GROUP)
            .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(PARTITION);
        return committed == null ? -1 : committed.offset();
    }

    private static void awaitCommittedPast(RecordMetadata record) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(committedOffset()).isEqualTo(record.offset() + 1));
    }

    private MessageListenerContainer container() {
        return registry.getListenerContainers().stream()
            .filter(c -> GROUP.equals(c.getGroupId()))
            .findFirst()
            .orElseThrow();
    }

    @Order(1)
    @DisplayName("주문 42 이벤트를 두 번 받아도 구매 활동은 상품 7·8 의 2행이고 두 record 모두 ack 된다.")
    @Test
    void keepsTwoRows_whenSameEventDeliveredTwice() throws Exception {
        // act: 같은 bytes 를 두 번 발행 (재전달 상황)
        RecordMetadata first = send(42L, OrderConfirmedEventParserTest.ORDER_42_JSON);
        RecordMetadata second = send(42L, OrderConfirmedEventParserTest.ORDER_42_JSON);
        awaitCommittedPast(second);

        // assert: 저장된 품목·수량·확정 시각 그대로, 2행 유지
        List<ProductPurchaseActivity> activities = purchaseActivityRepository.findActivitiesByOrderId(42L);
        assertThat(activities).hasSize(2);
        assertThat(activities).extracting(ProductPurchaseActivity::getProductId).containsExactly(7L, 8L);
        assertThat(activities).extracting(ProductPurchaseActivity::getQuantity).containsExactly(2, 1);
        assertThat(activities).allSatisfy(a -> assertThat(a.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT.toInstant()));

        ProcessedOrderConfirmation processed = purchaseActivityRepository.findProcessedByEventId("order-confirmed:v1:42").orElseThrow();
        assertThat(processed.getOrderId()).isEqualTo(42L);
        assertThat(processed.getFingerprint()).isEqualTo("42|2026-09-07T01:00:00Z|7:2,8:1");
        assertThat(second.offset()).isEqualTo(first.offset() + 1);
        assertThat(container().isRunning()).isTrue();
    }

    @Order(2)
    @DisplayName("같은 eventId 의 다른 수량, 같은 orderId 의 다른 eventId 는 충돌로 DLT 에 원본 그대로 보내고 ack 만 하며 추가 저장이 없다.")
    @Test
    void addsNothing_whenPayloadConflicts() throws Exception {
        // arrange: 주문 43 = 상품 7 × 3 정상 저장
        RecordMetadata original = send(43L, orderJson("order-confirmed:v1:43", 43L, "{\"productId\": 7, \"quantity\": 3}"));
        awaitCommittedPast(original);

        // act: ① 같은 eventId, 수량 4  ② 같은 orderId, 다른 eventId
        send(43L, orderJson("order-confirmed:v1:43", 43L, "{\"productId\": 7, \"quantity\": 4}"));
        RecordMetadata otherEvent = send(43L, orderJson("other-event:43", 43L, "{\"productId\": 7, \"quantity\": 3}"));
        awaitCommittedPast(otherEvent);

        // assert: 처음 저장한 사실만 남는다
        List<ProductPurchaseActivity> activities = purchaseActivityRepository.findActivitiesByOrderId(43L);
        assertThat(activities).hasSize(1);
        assertThat(activities.get(0).getQuantity()).isEqualTo(3);
        ProcessedOrderConfirmation processed = purchaseActivityRepository.findProcessedByOrderId(43L).orElseThrow();
        assertThat(processed.getEventId()).isEqualTo("order-confirmed:v1:43");
        assertThat(processed.getFingerprint()).isEqualTo("43|2026-09-07T01:00:00Z|7:3");
        assertThat(purchaseActivityRepository.findProcessedByEventId("other-event:43")).isEmpty();
        assertThat(container().isRunning()).isTrue();

        // 두 충돌 record 는 원본 key 그대로 DLT 에 1건씩 (설계 D12)
        List<ConsumerRecord<String, byte[]>> deadLetters = readDeadLetters();
        assertThat(deadLetters).hasSize(2);
        assertThat(deadLetters).allSatisfy(deadLetter -> assertThat(deadLetter.key()).isEqualTo("43"));
        assertThat(new String(deadLetters.get(0).value(), StandardCharsets.UTF_8)).contains("\"quantity\": 4");
        assertThat(new String(deadLetters.get(1).value(), StandardCharsets.UTF_8)).contains("other-event:43");
    }

    /** 이 클래스 topic 의 DLT partition 0 을 처음부터 끝까지 읽는다. */
    private static List<ConsumerRecord<String, byte[]>> readDeadLetters() {
        Map<String, Object> props = Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "dlt-reader-" + UUID.randomUUID(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props)) {
            TopicPartition partition = new TopicPartition(TOPIC + KafkaDeadLetterPublisher.DLT_SUFFIX, 0);
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

    @Order(3)
    @DisplayName("둘째 품목 저장이 계속 실패하면 재시도마다 새 TX 로 시작하고, 장부·첫 품목까지 rollback 되며, ack 없이 컨테이너가 중단된다.")
    @Test
    void rollsBackEverythingAndStops_whenItemSaveKeepsFailing() throws Exception {
        // arrange: 상품 99 저장 시점에 실패 주입. 그 순간의 TX 상태를 기록해 재시도마다 새 TX 인지 본다
        List<Boolean> txActive = new CopyOnWriteArrayList<>();
        List<Boolean> txNew = new CopyOnWriteArrayList<>();
        List<Integer> entityManagerIds = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            ProductPurchaseActivity activity = invocation.getArgument(0);
            if (activity.getProductId() == 99L) {
                txActive.add(TransactionSynchronizationManager.isActualTransactionActive());
                txNew.add(TransactionAspectSupport.currentTransactionStatus().isNewTransaction());
                EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
                entityManagerIds.add(System.identityHashCode(holder.getEntityManager()));
                throw new RuntimeException("주입한 품목 저장 실패");
            }
            return invocation.callRealMethod();
        }).when(repositorySpy).saveActivity(any());
        long committedBefore = committedOffset();

        // act
        RecordMetadata failing = send(44L, orderJson("order-confirmed:v1:44", 44L,
            "{\"productId\": 7, \"quantity\": 1}, {\"productId\": 99, \"quantity\": 1}"));
        await().atMost(WAIT).pollInterval(Duration.ofMillis(200)).until(() -> !container().isRunning());

        // assert: 첫 시도 + 재시도 2회(test profile) = 3번, 매번 활성·새 TX, 매번 다른 EntityManager
        assertThat(txActive).hasSize(3).allMatch(Boolean::booleanValue);
        assertThat(txNew).hasSize(3).allMatch(Boolean::booleanValue);
        assertThat(entityManagerIds).doesNotHaveDuplicates();
        verify(repositorySpy, times(3)).saveProcessed(any());

        // 장부·첫 품목(상품 7)까지 전부 rollback
        assertThat(purchaseActivityRepository.findProcessedByOrderId(44L)).isEmpty();
        assertThat(purchaseActivityRepository.findActivitiesByOrderId(44L)).isEmpty();

        // ack 하지 않았으므로 committed offset 은 실패 record 앞에 머문다
        assertThat(committedOffset()).isEqualTo(committedBefore).isEqualTo(failing.offset());
    }
}
