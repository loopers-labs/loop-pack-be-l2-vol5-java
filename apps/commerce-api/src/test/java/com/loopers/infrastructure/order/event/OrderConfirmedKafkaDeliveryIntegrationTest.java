package com.loopers.infrastructure.order.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.ProviderOrderSuffixGenerator;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.testpg.TestPgControl;
import com.loopers.support.time.MutableClock;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedKafkaDeliveryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedKafkaDeliveryIntegrationTest.class);
    private static final String TOPIC = "order-events-publication-" + UUID.randomUUID();
    private static final Instant CONFIRMED_AT = Instant.parse("2026-09-07T01:00:00.123456Z");
    private static final Duration DEADLINE = Duration.ofSeconds(10);
    private static final long INITIAL_STOCK = 10L;

    @DynamicPropertySource
    static void isolatedTopic(DynamicPropertyRegistry registry) {
        registry.add("order.confirmed-event.topic", () -> TOPIC);
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        MutableClock reproductionClock() {
            return new MutableClock(CONFIRMED_AT, ZoneOffset.UTC);
        }
    }

    @Autowired private TestRestTemplate rest;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock clock;
    @Autowired private OrderRepository orderRepository;
    @Autowired private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BrandRepository brandRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @Autowired private KafkaAdmin kafkaAdmin;
    @Autowired private ConsumerFactory<Object, Object> consumerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private KafkaTemplate<Object, Object> kafkaTemplate;
    @MockitoBean private ProviderOrderSuffixGenerator suffixGenerator;

    @BeforeEach
    void setUp() {
        clock.set(CONFIRMED_AT);
        kafkaAdmin.setOperationTimeout(10);
    }

    @AfterEach
    void tearDown() {
        kafkaTemplate.flush();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("send 직전 차단한 실제 PG 확정 주문은 유지하고, 다른 정상 주문은 실제 broker metadata와 원본 payload로 확인한다.")
    @Test
    void keepsMissingCaseAndPublishesNormalCase() throws Exception {
        assertProducerContract();
        Fixture missing = requestedOrder();
        AtomicInteger blockedSends = new AtomicInteger();
        AtomicReference<CompletableFuture<?>> normalSend = new AtomicReference<>();
        doAnswer(invocation -> {
            Order committed = readOrderInNewTransaction(Long.valueOf(invocation.getArgument(1, String.class)));
            assertThat(committed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(committed.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
            if (missing.orderId().toString().equals(invocation.getArgument(1))) {
                blockedSends.incrementAndGet();
                throw new IllegalStateException("DB commit 후 실제 Kafka send 직전의 통제된 전송 오류");
            }
            CompletableFuture<?> future = (CompletableFuture<?>) invocation.callRealMethod();
            normalSend.set(future);
            return future;
        }).when(kafkaTemplate).send(eq(TOPIC), anyString(), any());

        assertThat(TestPgControl.complete(missing.providerOrderId())).isEqualTo(1);
        assertThat(reconcile(missing).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(blockedSends).hasValue(1);
        assertConfirmedWithReservedStock(missing);
        assertThat(logEnds().values()).allMatch(offset -> offset == 0L);
        logObservation("missing", missing, null);

        Fixture normal = requestedOrder();
        assertThat(TestPgControl.complete(normal.providerOrderId())).isEqualTo(1);
        assertThat(reconcile(normal).getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(normalSend.get()).isNotNull();
        Object completed = normalSend.get().get(DEADLINE.toSeconds(), TimeUnit.SECONDS);
        assertThat(completed).isInstanceOf(SendResult.class);
        RecordMetadata metadata = ((SendResult<?, ?>) completed).getRecordMetadata();
        ConsumerRecord<Object, Object> record = readRecord(metadata);
        assertThat(record.topic()).isEqualTo(metadata.topic()).isEqualTo(TOPIC);
        assertThat(record.partition()).isEqualTo(metadata.partition());
        assertThat(record.offset()).isEqualTo(metadata.offset());
        assertThat(record.key()).isEqualTo(normal.orderId().toString());
        assertThat(record.value()).isInstanceOf(byte[].class);
        JsonNode actual = objectMapper.readTree((byte[]) record.value());
        JsonNode expected = objectMapper.readTree("""
            {
              "eventType": "OrderConfirmed", "schemaVersion": 1,
              "eventId": "order-confirmed:v1:%d", "orderId": %d,
              "confirmedAt": "2026-09-07T01:00:00.123456Z",
              "items": [{"productId": %d, "quantity": 2}, {"productId": %d, "quantity": 1}]
            }
            """.formatted(normal.orderId(), normal.orderId(), normal.productIds().get(0), normal.productIds().get(1)));
        assertThat(actual).isEqualTo(expected);
        assertConfirmedWithReservedStock(normal);

        assertThat(reconcile(normal).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reconcile(missing).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq(normal.orderId().toString()), any());
        verify(kafkaTemplate, times(1)).send(eq(TOPIC), eq(missing.orderId().toString()), any());
        assertThat(logEnds().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1L);
        logObservation("normal", normal, metadata);
    }

    @DisplayName("로컬·테스트의 초기화는 없는 topic을 partition 3개·복제 1개로 만든다.")
    @Test
    void createsMissingTopic() throws Exception {
        String topic = "order-events-create-" + UUID.randomUUID();

        new OrderConfirmedKafkaConfig().orderEventsTopicInitializer(kafkaAdmin, topic)
            .run(new DefaultApplicationArguments());

        var partitions = kafkaAdmin.describeTopics(topic).get(topic).partitions();
        assertThat(partitions).hasSize(3);
        assertThat(partitions).allSatisfy(partition -> assertThat(partition.replicas()).hasSize(1));
    }

    @DisplayName("이미 존재하는 topic을 초기화해도 partition 수를 자동으로 늘리지 않는다.")
    @Test
    void preservesExistingPartitions() throws Exception {
        String topic = "order-events-existing-" + UUID.randomUUID();
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(topic).partitions(1).replicas(1).build());

        new OrderConfirmedKafkaConfig().orderEventsTopicInitializer(kafkaAdmin, topic)
            .run(new DefaultApplicationArguments());

        assertThat(kafkaAdmin.describeTopics(topic).get(topic).partitions()).hasSize(1);
    }

    private Fixture requestedOrder() throws Exception {
        User user = userRepository.save(User.create());
        Brand brand = brandRepository.save(Brand.create("publication-" + UUID.randomUUID()));
        Product first = Product.create(brand.getId(), "첫 상품", 3_500L);
        Product second = Product.create(brand.getId(), "둘째 상품", 4_800L);
        first.changeStockTo(INITIAL_STOCK);
        second.changeStockTo(INITIAL_STOCK);
        first = productRepository.save(first);
        second = productRepository.save(second);
        String body = """
            {"items":[{"productId":%d,"quantity":2},{"productId":%d,"quantity":1}]}
            """.formatted(first.getId(), second.getId());
        ResponseEntity<String> created = request("/api/v1/orders", user.getId(), body);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Long orderId = objectMapper.readTree(created.getBody()).path("data").path("id").asLong();
        assertThat(orderId).isPositive();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        when(suffixGenerator.next()).thenReturn(suffix);
        String providerOrderId = "LP-ORD-%010d-%s".formatted(orderId, suffix);
        TestPgControl.scenario(providerOrderId, "{\"scenario\":\"SKIP_CALLBACK\"}");
        ResponseEntity<String> confirmed = request("/api/v1/orders/" + orderId + "/confirm", user.getId(), """
            {"paymentAttemptKey":"%s","cardType":"TEST","cardNo":"0000"}
            """.formatted(UUID.randomUUID()));
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId).orElseThrow();
        assertThat(attempt.getProviderOrderId()).isEqualTo(providerOrderId);
        return new Fixture(user.getId(), orderId, attempt.getProviderOrderId(), List.of(first.getId(), second.getId()));
    }

    private ResponseEntity<String> reconcile(Fixture fixture) {
        return request("/api/v1/orders/" + fixture.orderId() + "/payment/reconcile", fixture.userId(), null);
    }

    private ResponseEntity<String> request(String path, Long userId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", userId.toString());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void assertConfirmedWithReservedStock(Fixture fixture) {
        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getConfirmedAt().toInstant()).isEqualTo(CONFIRMED_AT);
        assertThat(productRepository.findById(fixture.productIds().get(0)).orElseThrow().getStock().amount()).isEqualTo(8L);
        assertThat(productRepository.findById(fixture.productIds().get(1)).orElseThrow().getStock().amount()).isEqualTo(9L);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).createRequests()).isEqualTo(1);
        assertThat(TestPgControl.stats(fixture.providerOrderId()).transactions()).isEqualTo(1);
    }

    private void assertProducerContract() {
        ProducerConfig config = new ProducerConfig(kafkaTemplate.getProducerFactory().getConfigurationProperties());
        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(config.getInt(ProducerConfig.RETRIES_CONFIG)).isPositive();
        assertThat(config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)).isLessThanOrEqualTo(5);
        assertThat(config.originals()).doesNotContainKey(ProducerConfig.MAX_BLOCK_MS_CONFIG);
    }

    private Order readOrderInNewTransaction(Long orderId) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(true);
        return transaction.execute(status -> orderRepository.findById(orderId).orElseThrow());
    }

    private Map<TopicPartition, Long> logEnds() {
        Consumer<Object, Object> consumer = consumerFactory.createConsumer("publication-observe-" + UUID.randomUUID(), null);
        try {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC, DEADLINE).stream()
                .map(partition -> new TopicPartition(TOPIC, partition.partition())).toList();
            return consumer.endOffsets(partitions, DEADLINE);
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    private ConsumerRecord<Object, Object> readRecord(RecordMetadata metadata) {
        Consumer<Object, Object> consumer = consumerFactory.createConsumer("publication-read-" + UUID.randomUUID(), null);
        try {
            TopicPartition partition = new TopicPartition(metadata.topic(), metadata.partition());
            consumer.assign(List.of(partition));
            consumer.seek(partition, metadata.offset());
            Instant until = Instant.now().plus(DEADLINE);
            List<ConsumerRecord<Object, Object>> records = new ArrayList<>();
            while (records.isEmpty() && Instant.now().isBefore(until)) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            assertThat(records).hasSize(1);
            return records.getFirst();
        } finally {
            consumer.close(Duration.ofSeconds(5));
        }
    }

    private void logObservation(String role, Fixture fixture, RecordMetadata metadata) {
        Order order = orderRepository.findById(fixture.orderId()).orElseThrow();
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
        List<String> items = order.getItems().stream()
            .map(item -> item.getProductId() + "x" + item.getQuantity()).toList();
        List<Long> stocks = fixture.productIds().stream()
            .map(productId -> productRepository.findById(productId).orElseThrow().getStock().amount()).toList();
        log.info("W6_DIRECT_PUBLICATION role={} orderId={} providerOrderId={} transactionKey={} eventId={} confirmedAt={} "
                + "items={} orderStatus={} stock={} topic={} partition={} offset={}",
            role, order.getId(), attempt.getProviderOrderId(), attempt.getTransactionKey(), "order-confirmed:v1:" + order.getId(),
            order.getConfirmedAt().toInstant(), items, order.getStatus(), stocks, TOPIC,
            metadata == null ? "none" : metadata.partition(), metadata == null ? "none" : metadata.offset());
    }

    private record Fixture(Long userId, Long orderId, String providerOrderId, List<Long> productIds) {}
}
