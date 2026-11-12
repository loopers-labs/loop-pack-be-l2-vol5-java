package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * 한 실행에서 주문 API → PG → Outbox → relay 중단·재시작 → Kafka → 실제 commerce-streamer → 구매 활동을 잇는다.
 * 중단은 PUBLISHED 저장 hook이고 프로세스 강제 종료가 아니다. 재시작은 같은 DB·broker를 쓰는 새 relay 객체다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "commerce.order-events.enabled=true")
class OutboxDeliveryE2ETest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final FakePaymentServer PG = startPg();
    private static final String TOPIC = "order-events-e2e-" + UUID.randomUUID();
    private static final String DEAD_LETTER_TOPIC = TOPIC + "-dlt";
    private static final String GROUP = "purchase-activity";
    private static final int PARTITIONS = 3;
    private static final Duration CONSUMER_START_DEADLINE = Duration.ofSeconds(120);
    private static final Duration RESULT_DEADLINE = Duration.ofSeconds(30);
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
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private OrderConfirmationPublisher publisher;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private OrderOutboxTransactions transactions;

    private Long orderId;

    @BeforeAll
    static void createTopic() throws Exception {
        try (AdminClient admin = admin()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() {
        if (orderId != null) {
            jdbc.update("DELETE FROM product_purchase_activity WHERE order_id = ?", orderId);
            jdbc.update("DELETE FROM processed_order_confirmation WHERE order_id = ?", orderId);
        }
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
    void republishedEventLeavesOneCompletionAndOriginalPurchaseActivities() throws Exception {
        Long userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("E2E 브랜드", null)).getId();
        Long socks = products.save(new ProductModel(brandId, "양말", 1_000L, 10)).getId();
        Long cap = products.save(new ProductModel(brandId, "모자", 2_000L, 10)).getId();
        startConsumer();

        // 1. 기존 API로 합성 주문을 만들고 PG SUCCESS를 내부 확정 경로에 적용한다.
        orderId = createOrder(userId, socks, cap);
        confirmThroughPg(userId);
        long pointsBefore = points(userId);

        // 2. 확정 주문·예약 소비·같은 eventId의 Outbox 한 행을 확인한다. consumer는 아직 받은 것이 없다.
        String eventId = "order-confirmed:v1:" + orderId;
        var order = orderRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
        OrderOutbox stored = onlyOutbox();
        assertThat(stored.getEventId()).isEqualTo(eventId);
        assertThat(stored.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(activityRows()).isZero();

        // 3. relay의 첫 broker 확인 뒤 PUBLISHED 저장 전에 멈춘다.
        doThrow(new IllegalStateException("test: stop before saving PUBLISHED"))
            .when(transactions).markPublished(anyLong());
        assertThatThrownBy(() -> relay.runOnce()).hasMessageContaining("test: stop");
        assertThat(onlyOutbox().getStatus()).isEqualTo(OrderOutboxStatus.PENDING);

        // consumer가 첫 record를 처리한다. PENDING인 행과 구매 활동 반영은 별개의 사실이다.
        await("첫 record의 구매 활동 저장", () -> completions() == 1 && activityRows() == 2);
        assertThat(onlyOutbox().getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        List<Map<String, Object>> firstActivities = activities();

        // 4. 같은 Outbox에서 relay를 새로 만들어 다시 발행한다.
        doCallRealMethod().when(transactions).markPublished(anyLong());
        assertThat(new OrderOutboxRelay(publisher, transactions, 20, Duration.ofSeconds(2), Duration.ofSeconds(60))
            .runOnce()).isEqualTo(1);
        OrderOutbox published = onlyOutbox();
        assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(2);

        // broker에 같은 eventId의 별도 record 두 개가 실제로 있다.
        List<ConsumerRecord<String, String>> records = readRecords(2);
        assertThat(records).hasSize(2);
        assertThat(records).extracting(ConsumerRecord::key).containsOnly(orderId.toString());
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
        assertThat(records).extracting(ConsumerRecord::offset).containsExactly(0L, 1L);
        for (ConsumerRecord<String, String> record : records) {
            assertThat(mapper.readTree(record.value()).get("eventId").asText()).isEqualTo(eventId);
            assertThat(record.value()).isEqualTo(stored.getPayload());
        }

        // 5. consumer가 두 record를 모두 지나간 뒤 DB의 상품별 구매 사실을 확인한다.
        TopicPartition partition = new TopicPartition(TOPIC, records.getFirst().partition());
        await("두 번째 record까지 consumer offset 진행", () -> committedOffset(partition) == 2L);
        assertThat(completions()).isEqualTo(1);
        assertThat(activities()).isEqualTo(firstActivities);
        assertThat(activities()).extracting(row -> row.get("product_id"), row -> row.get("quantity"))
            .containsExactlyInAnyOrder(tuple(socks, 2), tuple(cap, 1));
        assertThat(deadLetterRecords()).as("중복은 격리가 아니라 동일 사실 no-op이어야 한다").isZero();

        // 주문·PG·재고·포인트에 추가 효과가 없다.
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
        assertThat(products.findById(socks).orElseThrow().getStockQuantity()).isEqualTo(8);
        assertThat(products.findById(cap).orElseThrow().getStockQuantity()).isEqualTo(9);
        assertThat(orderRepository.findById(orderId).orElseThrow().getConfirmedAt()).isEqualTo(order.getConfirmedAt());
        assertThat(points(userId)).isEqualTo(pointsBefore);

        report(eventId, records, published, partition);
    }

    private void startConsumer() throws Exception {
        streamer = SpringAppProcess.streamer(SpringAppProcess.Database.fromSystemProperties(), BROKER.bootstrapServers(), TOPIC);
        await("실제 consumer의 partition 배정", CONSUMER_START_DEADLINE, this::consumerAssigned);
    }

    private boolean consumerAssigned() throws Exception {
        try (AdminClient admin = admin()) {
            var group = admin.describeConsumerGroups(List.of(GROUP)).all().get(10, TimeUnit.SECONDS).get(GROUP);
            int assigned = group.members().stream().mapToInt(member -> member.assignment().topicPartitions().size()).sum();
            return group.state() == ConsumerGroupState.STABLE && assigned == PARTITIONS;
        }
    }

    private Long createOrder(Long userId, Long socks, Long cap) {
        JsonNode response = post("/api/v1/orders", userId, Map.of("items", List.of(
            Map.of("productId", socks, "quantity", 2), Map.of("productId", cap, "quantity", 1))), HttpStatus.CREATED);
        return response.path("data").path("id").asLong();
    }

    private void confirmThroughPg(Long userId) {
        post("/api/v1/orders/" + orderId + "/confirm", userId, Map.of(
            "paymentAttemptKey", "outbox-e2e", "cardType", "TEST", "cardNo", "0000-0000-0000-0000"), HttpStatus.ACCEPTED);
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        post("/api/v1/orders/" + orderId + "/payment/reconcile", userId, Map.of(), HttpStatus.OK);
    }

    private JsonNode post(String path, Long userId, Object body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-USER-ID", userId.toString());
        ResponseEntity<JsonNode> response = rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).as("%s 응답: %s", path, response.getBody()).isEqualTo(expected);
        return response.getBody();
    }

    private OrderOutbox onlyOutbox() {
        assertThat(outbox.count()).isEqualTo(1);
        return outbox.findAll().getFirst();
    }

    private int completions() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_order_confirmation WHERE order_id = ?",
            Integer.class, orderId);
    }

    private int activityRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM product_purchase_activity WHERE order_id = ?",
            Integer.class, orderId);
    }

    private List<Map<String, Object>> activities() {
        return jdbc.queryForList("SELECT id, product_id, quantity, confirmed_at FROM product_purchase_activity "
            + "WHERE order_id = ? ORDER BY id", orderId);
    }

    private long points(Long userId) {
        return jdbc.queryForObject("SELECT balance FROM point_balance WHERE user_id = ?", Long.class, userId);
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        try (AdminClient admin = admin()) {
            OffsetAndMetadata offset = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                .get(10, TimeUnit.SECONDS).get(partition);
            return offset == null ? -1L : offset.offset();
        }
    }

    private long deadLetterRecords() throws Exception {
        try (AdminClient admin = admin()) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(DEAD_LETTER_TOPIC)) {
                return 0;
            }
        }
        try (KafkaConsumer<String, String> consumer = consumer()) {
            var partitions = consumer.partitionsFor(DEAD_LETTER_TOPIC).stream()
                .map(info -> new TopicPartition(info.topic(), info.partition())).toList();
            return consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private List<ConsumerRecord<String, String>> readRecords(int expected) {
        List<TopicPartition> partitions = IntStream.range(0, PARTITIONS)
            .mapToObj(partition -> new TopicPartition(TOPIC, partition)).toList();
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + RESULT_DEADLINE.toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private void report(String eventId, List<ConsumerRecord<String, String>> records, OrderOutbox published,
                        TopicPartition partition) throws Exception {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("orderId", orderId);
        facts.put("eventId", eventId);
        facts.put("outbox", Map.of("status", published.getStatus(), "attemptCount", published.getAttemptCount()));
        facts.put("records", records.stream().map(record -> record.partition() + ":" + record.offset()).toList());
        facts.put("committedOffset", committedOffset(partition));
        facts.put("completions", completions());
        facts.put("activities", activities().stream()
            .map(row -> row.get("product_id") + "x" + row.get("quantity")).toList());
        System.out.println("W7_OUTBOX_E2E " + mapper.writeValueAsString(facts));
    }

    private void await(String step, Callable<Boolean> condition) throws Exception {
        await(step, RESULT_DEADLINE, condition);
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

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false
        ));
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
