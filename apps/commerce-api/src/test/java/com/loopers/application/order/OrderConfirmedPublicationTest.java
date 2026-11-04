package com.loopers.application.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.KafkaOrderConfirmedPublisher;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.testcontainers.KafkaTestBroker;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 PG 결과 적용 → MySQL commit → AFTER_COMMIT → 실제 Kafka record를 확인한다. */
@SpringBootTest(properties = "commerce.order-events.enabled=true")
class OrderConfirmedPublicationTest {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final FakePaymentServer PG = startPg();
    private static final String TOPIC = "order-events-publication-" + UUID.randomUUID();

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("commerce.order-events.topic", () -> TOPIC);
        registry.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        registry.add("spring.kafka.admin.properties.bootstrap.servers", BROKER::bootstrapServers);
    }

    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private OrderPaymentTransactions payments;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private PaymentAttemptJpaRepository attempts;
    @Autowired private EntityManager entityManager;
    @Autowired private TransactionTemplate transaction;
    @Autowired private OrderConfirmationPublisher publisher;
    @Autowired private KafkaTemplate<Object, Object> kafka;
    @Autowired private ObjectMapper mapper;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private StopBeforeSend stopBeforeSend;

    private Long userId;
    private Long orderId;
    private Long productId;
    private Long secondProductId;

    @BeforeEach
    void setUp() throws Exception {
        // 토픽 재생성 전 이전 producer의 topic metadata를 비워 테스트 간 상태를 분리한다.
        kafka.getProducerFactory().reset();
        try (AdminClient admin = admin()) {
            if (admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
                admin.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
            }
            admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        PG.reset();
        stopBeforeSend.reset();
        userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("Kafka 테스트 브랜드", null)).getId();
        productId = products.save(new ProductModel(brandId, "양말", 1_000L, 10)).getId();
        secondProductId = products.save(new ProductModel(brandId, "모자", 2_000L, 10)).getId();
        orderId = orders.create(userId, List.of(
            new OrderFacade.OrderLine(productId, 1), new OrderFacade.OrderLine(secondProductId, 1),
            new OrderFacade.OrderLine(productId, 2)
        )).id();
        orders.confirm(userId, orderId,
            new OrderFacade.PaymentCommand("kafka-publication-test", "TEST", "0000-0000-0000-0000"));
        assertThat(recordCount()).isZero();
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
    void publishesStoredOrderWithStableIdentityAndObservedRecordPosition() throws Exception {
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        OrderPaymentInfo confirmed = orders.reconcile(userId, orderId);
        kafka.flush();

        assertThat(confirmed.isFinished()).isTrue();
        List<ConsumerRecord<String, String>> first = readRecords(1);
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().key()).isEqualTo(orderId.toString());
        assertThat(first.getFirst().offset()).isZero();
        assertThat(first.getFirst().headers().toArray()).isEmpty();
        JsonNode payload = mapper.readTree(first.getFirst().value());
        var stored = orderRepository.findById(orderId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stored.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(payload).isEqualTo(mapper.readTree("""
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d",
             "orderId":%d,"confirmedAt":"%s",
             "items":[{"productId":%d,"quantity":3},{"productId":%d,"quantity":1}]}
            """.formatted(orderId, orderId, stored.getConfirmedAt(), productId, secondProductId)));

        orders.reconcile(userId, orderId);
        orders.handleCallback(attempts.findByOrderId(orderId).orElseThrow().getProviderOrderId());
        payments.applyRecordedResult(orderId);
        kafka.flush();
        assertThat(recordCount()).isEqualTo(1);

        // 전송 재시도에서도 같은 확정 사실의 ID가 유지되는지 producer 경계에서 확인한다.
        publisher.publish(new OrderConfirmed(orderId, stored.getConfirmedAt(), List.of(
            new OrderConfirmed.Item(productId, 3), new OrderConfirmed.Item(secondProductId, 1)
        )));
        kafka.flush();
        List<ConsumerRecord<String, String>> repeated = readRecords(2);
        assertThat(repeated).hasSize(2);
        assertThat(repeated).extracting(ConsumerRecord::partition).containsOnly(first.getFirst().partition());
        assertThat(repeated).extracting(ConsumerRecord::offset).containsExactly(0L, 1L);
        assertThat(mapper.readTree(repeated.get(1).value())).isEqualTo(payload);
        assertThat(recordCount()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(7);
    }

    @Test
    void rolledBackConfirmationLeavesNoKafkaRecordAndRecoveryPublishesOnce() {
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        payments.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"), PaymentEvent.Source.RECONCILE);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            payments.applyRecordedResult(orderId);
            entityManager.flush();
            throw new IllegalStateException("test: rollback after preparing Kafka event");
        })).isInstanceOf(IllegalStateException.class);

        kafka.flush();
        assertThat(recordCount()).isZero();
        assertThat(orderRepository.findById(orderId).orElseThrow().getConfirmedAt()).isNull();

        orders.reconcile(userId, orderId);
        kafka.flush();

        assertThat(readRecords(1)).hasSize(1);
        assertThat(recordCount()).isEqualTo(1);
        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    /**
     * 누락 구간 재현: 주문 A는 commit 뒤 send 직전에서 멈추고, 주문 B는 실제로 보낸다.
     * 프로세스 강제 종료가 아니라 hook으로 send 호출을 건너뛰어 같은 결과(로그 없음, record 없음)를 흉내 낸다.
     * B는 이 테스트가 record를 실제로 읽을 수 있음을 보이는 대조군이다.
     */
    @Test
    void confirmationCommittedButStoppedBeforeSendLeavesNoRecord() {
        Long missedOrderId = orderId;
        Long publishedOrderId = orders.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();
        orders.confirm(userId, publishedOrderId,
            new OrderFacade.PaymentCommand("kafka-publication-test-2", "TEST", "0000-0000-0000-0000"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        PG.changeStatus("TEST-TX-2", "SUCCESS");
        stopBeforeSend.stopFor(missedOrderId);

        orders.reconcile(userId, missedOrderId);
        orders.reconcile(userId, publishedOrderId);
        kafka.flush();

        var missed = orderRepository.findById(missedOrderId).orElseThrow();
        assertThat(missed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(missed.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(stopBeforeSend.stopped()).extracting(OrderConfirmed::eventId)
            .containsExactly("order-confirmed:v1:" + missedOrderId);

        List<ConsumerRecord<String, String>> records = readRecords(1);
        assertThat(records).extracting(ConsumerRecord::key).containsExactly(publishedOrderId.toString());
        assertThat(recordCount()).isEqualTo(1);
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

    @TestConfiguration
    static class StopBeforeSendConfig {
        @Bean
        @Primary
        StopBeforeSend stopBeforeSend(KafkaOrderConfirmedPublisher kafkaPublisher) {
            return new StopBeforeSend(kafkaPublisher);
        }
    }

    /** 지정한 주문만 send를 호출하지 않고 조용히 돌아간다. 나머지는 실제 Kafka publisher로 넘긴다. */
    static class StopBeforeSend implements OrderConfirmationPublisher {
        private final OrderConfirmationPublisher delegate;
        private final Set<Long> stopOrderIds = ConcurrentHashMap.newKeySet();
        private final List<OrderConfirmed> stopped = new CopyOnWriteArrayList<>();

        StopBeforeSend(OrderConfirmationPublisher delegate) {
            this.delegate = delegate;
        }

        @Override
        public void publish(OrderConfirmed event) {
            if (stopOrderIds.contains(event.orderId())) {
                stopped.add(event);
                return;
            }
            delegate.publish(event);
        }

        void stopFor(Long orderId) {
            stopOrderIds.add(orderId);
        }

        List<OrderConfirmed> stopped() {
            return List.copyOf(stopped);
        }

        void reset() {
            stopOrderIds.clear();
            stopped.clear();
        }
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
