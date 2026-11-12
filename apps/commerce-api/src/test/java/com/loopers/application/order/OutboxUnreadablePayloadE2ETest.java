package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.order.SpringAppProcess;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * relay가 payload를 검사하지 않을 때 읽을 수 없는 payload가 어디에 남는지 두 앱을 이어 확인한다.
 * <p>
 * 정상 주문을 API·PG로 확정한 뒤, 테스트가 Outbox payload를 SQL로 깨뜨린다. 저장 payload를 사람이 직접 잘못 고친 경우를
 * 흉내 낸 테스트 전용 입력이며 정상 업무 데이터가 아니다. 뒤이어 다른 정상 주문을 보내 consumer가 계속 진행하는지도 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "commerce.order-events.enabled=true")
class OutboxUnreadablePayloadE2ETest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final FakePaymentServer PG = startPg();
    private static final String TOPIC = "order-events-bad-payload-" + UUID.randomUUID();
    private static final String DEAD_LETTER_TOPIC = TOPIC + "-dlt";
    private static final String GROUP = "purchase-activity";
    private static final Duration CONSUMER_START_DEADLINE = Duration.ofSeconds(120);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(60);
    private static final String UNREADABLE = "{\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,";
    private static SpringAppProcess streamer;

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", BROKER::bootstrapServers);
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private UserRegistrationService registration;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanUp;

    private int nextTransaction = 1;

    @BeforeAll
    static void createTopics() throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DEAD_LETTER_TOPIC, 1, (short) 1)))
                .all().get(10, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM product_purchase_activity");
        jdbc.update("DELETE FROM processed_order_confirmation");
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeResources() throws Exception {
        try {
            if (streamer != null) {
                streamer.close();
            }
        } finally {
            try {
                PG.close();
            } finally {
                BROKER.close();
            }
        }
    }

    @Test
    void unreadablePayloadIsPublishedThenIsolatedInDeadLetterWhileNextOrderIsProcessed() throws Exception {
        Long userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("DLT E2E 브랜드", null)).getId();
        Long socks = products.save(new ProductModel(brandId, "양말", 1_000L, 10)).getId();
        streamer = SpringAppProcess.streamer(SpringAppProcess.Database.fromSystemProperties(), BROKER.bootstrapServers(), TOPIC);
        await("실제 consumer의 partition 배정", CONSUMER_START_DEADLINE, this::consumerAssigned);

        // 1. 정상 주문을 확정하고, 저장된 payload를 테스트가 깨뜨린다.
        Long broken = confirmedOrder(userId, socks, "bad-payload");
        OrderOutbox stored = outboxOf(broken);
        jdbc.update("UPDATE order_outbox SET payload = ? WHERE id = ?", UNREADABLE, stored.getId());

        // 2. relay는 내용을 검사하지 않으므로 그대로 보내고 PUBLISHED로 남긴다.
        assertThat(relay.runOnce()).isEqualTo(1);
        assertThat(outboxOf(broken).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);

        // 3. consumer가 형식 오류로 DLT에 원본 그대로 격리하고 위치를 넘긴다. 구매 활동은 없다.
        TopicPartition partition = new TopicPartition(TOPIC, 0);
        await("원본 record 다음으로 consumer offset 진행", () -> committedOffset(partition) == 1L);
        List<ConsumerRecord<String, String>> deadLetters = readAll(DEAD_LETTER_TOPIC, 1);
        assertThat(deadLetters).hasSize(1);
        assertThat(deadLetters.getFirst().key()).isEqualTo(broken.toString());
        assertThat(deadLetters.getFirst().value()).isEqualTo(UNREADABLE);
        assertThat(rows("processed_order_confirmation", broken)).isZero();
        assertThat(rows("product_purchase_activity", broken)).isZero();

        // 4. 다음 정상 주문은 막히지 않고 반영된다.
        Long next = confirmedOrder(userId, socks, "after-bad-payload");
        assertThat(relay.runOnce()).isEqualTo(1);
        await("다음 주문의 구매 활동 저장", () -> rows("product_purchase_activity", next) == 1);
        // DB 저장과 Kafka offset commit은 별개 시점이라 offset도 따로 기다린다.
        await("다음 정상 record까지 offset 진행", () -> committedOffset(partition) == 2L);
        assertThat(readAll(DEAD_LETTER_TOPIC, 1)).hasSize(1);

        System.out.println("W7_UNREADABLE_PAYLOAD_E2E " + Map.of(
            "brokenOrder", Map.of("outbox", outboxOf(broken).getStatus(), "deadLetters", deadLetters.size(),
                "completions", rows("processed_order_confirmation", broken),
                "activities", rows("product_purchase_activity", broken)),
            "nextOrder", Map.of("outbox", outboxOf(next).getStatus(),
                "activities", rows("product_purchase_activity", next)),
            "committedOffset", committedOffset(partition),
            "deadLetterHeaders", deadLetters.getFirst().headers().toArray().length));
    }

    private Long confirmedOrder(Long userId, Long productId, String attemptKey) {
        JsonNode created = post("/api/v1/orders", userId, Map.of("items", List.of(
            Map.of("productId", productId, "quantity", 1))), HttpStatus.CREATED);
        Long orderId = created.path("data").path("id").asLong();
        post("/api/v1/orders/" + orderId + "/confirm", userId, Map.of(
            "paymentAttemptKey", attemptKey, "cardType", "TEST", "cardNo", "0000-0000-0000-0000"), HttpStatus.ACCEPTED);
        PG.changeStatus("TEST-TX-" + nextTransaction++, "SUCCESS");
        post("/api/v1/orders/" + orderId + "/payment/reconcile", userId, Map.of(), HttpStatus.OK);
        assertThat(outboxOf(orderId).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        return orderId;
    }

    private JsonNode post(String path, Long userId, Object body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", userId.toString());
        ResponseEntity<JsonNode> response = rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).as("%s 응답: %s", path, response.getBody()).isEqualTo(expected);
        return response.getBody();
    }

    private OrderOutbox outboxOf(Long orderId) {
        return outbox.findAll().stream().filter(row -> row.getOrderId().equals(orderId)).findFirst().orElseThrow();
    }

    private int rows(String table, Long orderId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE order_id = ?", Integer.class, orderId);
    }

    private boolean consumerAssigned() throws Exception {
        try (AdminClient admin = admin()) {
            var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP);
            int assigned = group.members().stream().mapToInt(member -> member.assignment().topicPartitions().size()).sum();
            return group.state() == ConsumerGroupState.STABLE && assigned == 1;
        }
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        try (AdminClient admin = admin()) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
            return offset == null ? -1L : offset.offset();
        }
    }

    private List<ConsumerRecord<String, String>> readAll(String topic, int expected) {
        var partition = new TopicPartition(topic, 0);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private void await(String step, Duration limit, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + limit.toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadline) {
            assertThat(streamer.isAlive()).as("streamer 프로세스 종료:\n%s", streamer.logTail()).isTrue();
            try {
                if (condition.call()) {
                    return;
                }
                lastFailure = null;
            } catch (ExecutionException exception) {
                lastFailure = exception;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("기한 초과: %s (마지막 오류: %s)\n%s".formatted(step, lastFailure, streamer.logTail()));
    }

    private void await(String step, Callable<Boolean> condition) throws Exception {
        await(step, RESULT_DEADLINE, condition);
    }

    private static AdminClient admin() {
        return AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers()));
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
