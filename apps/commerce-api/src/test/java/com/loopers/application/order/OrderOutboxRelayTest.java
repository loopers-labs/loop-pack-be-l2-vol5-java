package com.loopers.application.order;

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
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 실제 MySQL·Kafka에서 저장된 Outbox를 전송하고, 전송 결과에 따라 행 상태가 어떻게 남는지 확인한다. 구매 활동 소비는 범위 밖이다. */
@SpringBootTest(properties = "commerce.order-events.enabled=true")
class OrderOutboxRelayTest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final FakePaymentServer PG = startPg();
    private static final String TOPIC = "order-outbox-relay-" + UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00Z");
    private static final Duration FIRST_RETRY = Duration.ofSeconds(2);
    private static final Duration MAX_RETRY = Duration.ofSeconds(60);

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", BROKER::bootstrapServers);
    }

    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private OrderOutboxRelay relay;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private OrderOutboxTransactions transactions;
    @MockitoSpyBean private OrderConfirmationPublisher publisher;
    @MockitoBean private Clock clock;

    private Long userId;
    private Long productId;
    private Long secondProductId;
    private int nextTransaction = 1;

    @BeforeEach
    void setUp() throws Exception {
        try (AdminClient admin = admin()) {
            if (admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
                admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
            }
            createTopicAfterDeletion(admin);
        }
        PG.reset();
        nextTransaction = 1;
        setTime(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("relay 테스트 브랜드", null)).getId();
        productId = products.save(new ProductModel(brandId, "양말", 1_000L, 10)).getId();
        secondProductId = products.save(new ProductModel(brandId, "모자", 2_000L, 10)).getId();
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeResources() throws InterruptedException {
        try {
            PG.close();
        } finally {
            BROKER.close();
        }
    }

    @Test
    void sendsStoredKeyAndPayloadAsIsThenMarksPublishedOnce() {
        Long orderId = confirmedOrder("relay-1");
        OrderOutbox stored = outboxOf(orderId);
        setTime(NOW.plusSeconds(1));

        assertThat(relay.runOnce()).isEqualTo(1);

        List<ConsumerRecord<String, String>> records = readRecords(1);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo(stored.getMessageKey());
        assertThat(records.getFirst().value()).isEqualTo(stored.getPayload());
        assertThat(records.getFirst().headers().toArray()).isEmpty();
        OrderOutbox published = outboxOf(orderId);
        assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(published.getPublishedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(published.getAttemptCount()).isEqualTo(1);

        assertThat(relay.runOnce()).isZero();
        assertThat(recordCount()).isEqualTo(1);
    }

    /** relay 전 중단: commit된 행만으로 새 relay가 같은 사건을 찾아 보낸다. */
    @Test
    void newRelayFindsEventCommittedBeforeAnyRelayRan() {
        Long orderId = confirmedOrder("relay-restart");
        assertThat(recordCount()).isZero();
        assertThat(outboxOf(orderId).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);

        assertThat(restartedRelay().runOnce()).isEqualTo(1);

        assertThat(readRecords(1)).extracting(ConsumerRecord::key).containsExactly(orderId.toString());
        assertThat(outboxOf(orderId).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
    }

    /** broker 확인 뒤 PUBLISHED 저장 전 중단: 같은 행이 PENDING으로 남아 같은 payload가 다른 offset에 다시 기록된다. */
    @Test
    void stopAfterBrokerAcknowledgementRepublishesSameEventAtNewOffset() {
        Long orderId = confirmedOrder("relay-stop");
        doThrow(new IllegalStateException("test: stop before saving PUBLISHED")).when(transactions).markPublished(anyLong());

        assertThatThrownBy(() -> relay.runOnce()).hasMessageContaining("test: stop");

        OrderOutbox unconfirmed = outboxOf(orderId);
        assertThat(unconfirmed.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(unconfirmed.getPublishedAt()).isNull();
        assertThat(recordCount()).isEqualTo(1);

        doCallRealMethod().when(transactions).markPublished(anyLong());
        assertThat(restartedRelay().runOnce()).isEqualTo(1);

        List<ConsumerRecord<String, String>> records = readRecords(2);
        assertThat(records).extracting(ConsumerRecord::offset).containsExactly(0L, 1L);
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(records.getFirst().partition());
        assertThat(records.get(1).value()).isEqualTo(records.getFirst().value());
        OrderOutbox published = outboxOf(orderId);
        assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(published.getAttemptCount()).isEqualTo(2);
    }

    /** 전송 실패: 주문·PG는 건드리지 않고 같은 행을 재시도 대상으로 두며, 회차를 멈춰 뒤 행을 기다리지 않는다. */
    @Test
    void sendFailureKeepsConfirmedOrderAndRetriesSameRowAfterDelay() {
        Long failedOrderId = confirmedOrder("relay-failed");
        Long nextOrderId = confirmedOrder("relay-next");
        doThrow(new IllegalStateException("test: broker unavailable")).when(publisher).publish(any());

        assertThat(relay.runOnce()).isZero();

        OrderOutbox failed = outboxOf(failedOrderId);
        assertThat(failed.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(failed.getAttemptCount()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isEqualTo(NOW.plus(FIRST_RETRY));
        assertThat(failed.getLastError()).contains("test: broker unavailable");
        assertThat(outboxOf(nextOrderId).getAttemptCount()).isZero();
        assertThat(recordCount()).isZero();
        var order = orderRepository.findById(failedOrderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);

        doCallRealMethod().when(publisher).publish(any());
        assertThat(relay.runOnce()).as("다음 행은 재시도 시각을 기다리지 않는다").isEqualTo(1);
        assertThat(outboxOf(nextOrderId).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(outboxOf(failedOrderId).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);

        setTime(NOW.plus(FIRST_RETRY));
        assertThat(relay.runOnce()).isEqualTo(1);

        OrderOutbox recovered = outboxOf(failedOrderId);
        assertThat(recovered.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(recovered.getAttemptCount()).isEqualTo(2);
        assertThat(recovered.getLastError()).isNull();
        assertThat(recordCount()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(2);
    }

    /** docs/week7/operations.md의 조회문이 실패한 행의 위치·원인을 보여 주는지 확인한다. */
    @Test
    void backlogQueriesShowOldestPendingAndLastError() {
        Long orderId = confirmedOrder("relay-backlog");
        doThrow(new IllegalStateException("test: broker unavailable")).when(publisher).publish(any());
        relay.runOnce();

        Map<String, Object> backlog = jdbc.queryForMap("""
            SELECT COUNT(*) AS pending, MIN(created_at) AS oldest_created_at, MAX(attempt_count) AS max_attempts
            FROM order_outbox WHERE status = 'PENDING'""");
        assertThat(((Number) backlog.get("pending")).intValue()).isEqualTo(1);
        assertThat(backlog.get("oldest_created_at")).isNotNull();
        assertThat(((Number) backlog.get("max_attempts")).intValue()).isEqualTo(1);

        List<Map<String, Object>> failing = jdbc.queryForList("""
            SELECT event_id, order_id, attempt_count, next_attempt_at, last_error
            FROM order_outbox WHERE status = 'PENDING' ORDER BY id LIMIT 20""");
        assertThat(failing).hasSize(1);
        assertThat(failing.getFirst().get("event_id")).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat((String) failing.getFirst().get("last_error")).contains("test: broker unavailable");
    }

    /** docs/week7/operations.md의 재처리 절차: 같은 행을 PENDING으로 돌리면 같은 eventId·payload가 새 offset에 기록된다. */
    @Test
    void replayOfPublishedRowSendsSameEventAgain() {
        Long orderId = confirmedOrder("relay-replay");
        relay.runOnce();
        OrderOutbox published = outboxOf(orderId);

        int updated = jdbc.update("""
            UPDATE order_outbox SET status = 'PENDING', published_at = NULL
            WHERE event_id = ? AND status = 'PUBLISHED'""", published.getEventId());

        assertThat(updated).isEqualTo(1);
        assertThat(relay.runOnce()).isEqualTo(1);
        List<ConsumerRecord<String, String>> records = readRecords(2);
        assertThat(records).extracting(ConsumerRecord::offset).containsExactly(0L, 1L);
        assertThat(records.get(1).value()).isEqualTo(records.getFirst().value());
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(outboxOf(orderId).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
    }

    @Test
    void oneRunSendsOnlyBatchSizeInIdOrder() {
        Long first = confirmedOrder("relay-batch-1");
        Long second = confirmedOrder("relay-batch-2");
        Long third = confirmedOrder("relay-batch-3");
        OrderOutboxRelay smallBatch = new OrderOutboxRelay(publisher, transactions, 2, FIRST_RETRY, MAX_RETRY);

        assertThat(smallBatch.runOnce()).isEqualTo(2);

        assertThat(outboxOf(first).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(outboxOf(second).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(outboxOf(third).getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
    }

    @Test
    void overlappingRunOnSameRelayDoesNothing() throws Exception {
        Long orderId = confirmedOrder("relay-overlap");
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(publisher).publish(any());

        var running = CompletableFuture.supplyAsync(relay::runOnce);
        assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(relay.runOnce()).isZero();

        release.countDown();
        assertThat(running.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        verify(publisher, times(1)).publish(any());
        assertThat(outboxOf(orderId).getAttemptCount()).isEqualTo(1);
    }

    @Test
    void unconfirmedOrderHasNothingToSend() {
        Long orderId = orders.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();

        assertThat(relay.runOnce()).isZero();

        assertThat(orderRepository.findById(orderId).orElseThrow().getConfirmedAt()).isNull();
        verify(publisher, never()).publish(any());
    }

    private Long confirmedOrder(String attemptKey) {
        Long orderId = orders.create(userId, List.of(
            new OrderFacade.OrderLine(productId, 1), new OrderFacade.OrderLine(secondProductId, 1)
        )).id();
        orders.confirm(userId, orderId, new OrderFacade.PaymentCommand(attemptKey, "TEST", "0000-0000-0000-0000"));
        PG.changeStatus("TEST-TX-" + nextTransaction++, "SUCCESS");
        orders.reconcile(userId, orderId);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        return orderId;
    }

    /** 재시작: 메모리 상태 없이 같은 DB·broker를 쓰는 새 relay 객체를 만든다. */
    private OrderOutboxRelay restartedRelay() {
        return new OrderOutboxRelay(publisher, transactions, 20, FIRST_RETRY, MAX_RETRY);
    }

    private OrderOutbox outboxOf(Long orderId) {
        return outbox.findAll().stream().filter(row -> row.getOrderId().equals(orderId)).findFirst().orElseThrow();
    }

    private void setTime(Instant instant) {
        when(clock.instant()).thenReturn(instant);
    }

    private void createTopicAfterDeletion(AdminClient admin) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
            assertThat(System.nanoTime()).as("이전 테스트의 토픽 삭제 완료").isLessThan(deadline);
            Thread.sleep(100);
        }
        admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
    }

    private List<TopicPartition> partitions() {
        return List.of(new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"
        ));
    }

    private long recordCount() {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            return consumer.endOffsets(partitions(), Duration.ofSeconds(10)).values().stream()
                .mapToLong(Long::longValue).sum();
        }
    }

    private List<ConsumerRecord<String, String>> readRecords(int expected) {
        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.assign(partitions());
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (records.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            return records;
        }
    }

    private AdminClient admin() {
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
