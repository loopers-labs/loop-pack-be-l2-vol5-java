package com.loopers.order.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.ApprovalRequest;
import com.loopers.order.domain.PaymentStatus;
import com.loopers.order.domain.StockReservationStatus;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.Stock;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@DisplayName("OrderConfirmed producer 발행 통합 테스트")
class OrderConfirmedPublicationIntegrationTest {

    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-10-21T15:00:00+09:00");
    private static final PaymentStatus[] DRAFT_PAYMENT_STATUSES = {
        PaymentStatus.PENDING,
        PaymentStatus.UNKNOWN,
        PaymentStatus.FAILED,
        PaymentStatus.CANCELED,
        PaymentStatus.CANCEL_PROCESSING
    };
    @Autowired private OrderUseCase orderUseCase;
    @Autowired private OrderPaymentTransactionService transactionService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private ConfigurableApplicationContext applicationContext;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private PaymentGateway paymentGateway;
    @MockitoBean private KafkaTemplate<Object, Object> kafkaTemplate;
    private JsonNode orderConfirmedContract;

    @BeforeEach
    void returnsSuccessfulSendMetadata() throws IOException {
        InputStream contract = getClass().getClassLoader()
            .getResourceAsStream("events/order/order-confirmed-v1.contract.json");
        assertThat(contract).as("OrderConfirmed v1 event contract resource").isNotNull();
        orderConfirmedContract = objectMapper.readTree(contract);

        when(kafkaTemplate.send(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(successfulSendResult()));
    }

    @Nested
    @DisplayName("주문 확정 후 이벤트 발행한다")
    class PublishAfterOrderConfirmation {

        @Test
        @DisplayName("1. 확정 트랜잭션 commit 후 send를 한 번 호출한다.")
        void callsSendOnceAfterConfirmationCommit() {
            when(kafkaTemplate.send(anyString(), anyString(), any())).thenAnswer(invocation -> {
                Long orderId = Long.valueOf(invocation.getArgument(1));
                assertOrderIsCommittedAsConfirmed(orderId);
                return CompletableFuture.completedFuture(successfulSendResult());
            });

            ConfirmedOrder confirmed = confirmPgOrder();

            assertThat(confirmed.order().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            verify(kafkaTemplate, times(1)).send(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("2. 계약된 topic·key로 보내고 payload가 스키마 및 DB에 저장된 주문 값과 일치한다.")
        void sendsEventAccordingToContract() {
            ConfirmedOrder confirmed = confirmPgOrder();
            var topic = org.mockito.ArgumentCaptor.forClass(String.class);
            var key = org.mockito.ArgumentCaptor.forClass(String.class);
            var value = org.mockito.ArgumentCaptor.forClass(Object.class);

            verify(kafkaTemplate).send(topic.capture(), key.capture(), value.capture());

            assertThat(topic.getValue()).isEqualTo(orderEventTopic());
            assertThat(key.getValue()).isEqualTo(confirmed.scenario().orderId().toString());
            JsonNode payload = objectMapper.valueToTree(value.getValue());
            assertThat(orderConfirmedSchema().validate(payload)).isEmpty();
            Order storedOrder = readOrder(confirmed.scenario().orderId());
            assertThat(payload.path("orderId").asLong()).isEqualTo(storedOrder.getId());
            assertThat(payload.path("eventId").asText())
                .isEqualTo("order-confirmed:" + storedOrder.getId());
            assertThat(Instant.parse(payload.path("confirmedAt").asText()))
                .isEqualTo(readConfirmedAt(storedOrder.getId()));

            Map<Long, Integer> actualItems = new HashMap<>();
            payload.path("items").forEach(item -> actualItems.put(
                item.path("productId").asLong(), item.path("quantity").asInt()));
            Map<Long, Integer> storedItems = new HashMap<>();
            storedOrder.getItems().forEach(item -> storedItems.put(item.productId(), item.quantity()));
            assertThat(actualItems).hasSize(payload.path("items").size()).isEqualTo(storedItems);
        }

        @Test
        @DisplayName("UNKNOWN 결제가 재기동 대사에서 SUCCESS로 확인되면 이벤트를 발행한다.")
        void publishesWhenStartupRecoveryFindsUnknownPaymentApproved() {
            Scenario scenario = createScenario();
            OrderPaymentTransactionService.DispatchPlan plan = prepare(scenario);
            transactionService.recordResult(scenario.orderId(), new PaymentGateway.Unknown());
            when(paymentGateway.findByPaymentKey(plan.request().paymentKey()))
                .thenReturn(approved(plan.request()));

            eventPublisher.publishEvent(new ApplicationReadyEvent(
                new SpringApplication(), new String[0], applicationContext, Duration.ZERO));

            Order confirmed = readOrder(scenario.orderId());
            assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(confirmed.getPayment().status()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(readReservationStatus(scenario.orderId())).isEqualTo(StockReservationStatus.CONSUMED);
            verify(paymentGateway).findByPaymentKey(plan.request().paymentKey());
            verify(paymentGateway, never()).approve(any());
            verify(kafkaTemplate).send(
                eq(orderEventTopic()), eq(scenario.orderId().toString()), any());
        }

        @Test
        @DisplayName("주문 확정 commit 후 Kafka 발행이 실패해도 주문 확정과 재고 예약 소비는 유지한다.")
        void keepsConfirmedOrderWhenKafkaSendFailsAfterCommit() {
            Scenario scenario = createScenario();
            String paymentKey = "payment-" + UUID.randomUUID();
            String attemptKey = "attempt-" + UUID.randomUUID();
            when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));

            orderUseCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);

            verify(kafkaTemplate).send(eq(orderEventTopic()), eq(scenario.orderId().toString()), any());
            assertThat(readOrder(scenario.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(readReservationStatus(scenario.orderId())).isEqualTo(StockReservationStatus.CONSUMED);
        }

        @Test
        @DisplayName("Kafka 전송 실패로 이벤트는 없지만 주문 확정은 유지된다.")
        void leavesConfirmedOrderWithoutKafkaRecordWhenSendFailsAtTestHook() throws Exception {
            String topic = orderEventTopic();
            ensureTopicExists(topic);
            Scenario scenario = createScenario();
            String paymentKey = "payment-" + UUID.randomUUID();
            String attemptKey = "attempt-" + UUID.randomUUID();
            String expectedEventId = "order-confirmed:" + scenario.orderId();
            when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));
            when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("test hook: fail before broker send")));

            orderUseCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);

            assertThat(readOrder(scenario.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(readReservationStatus(scenario.orderId())).isEqualTo(StockReservationStatus.CONSUMED);
            verify(kafkaTemplate).send(eq(topic), eq(scenario.orderId().toString()), any());
            assertThat(readBrokerContainsEvent(topic, expectedEventId)).isFalse();
        }
    }

    @Test
    @DisplayName("PG 미확정·카드 취소 결제 상태에서는 주문이 DRAFT에 남고 이벤트를 발행하지 않는다.")
    void doesNotPublishWhileOrdersRemainDraft() {
        Scenario unpaid = createScenario();
        Map<PaymentStatus, Scenario> scenariosByPaymentStatus = new EnumMap<>(PaymentStatus.class);

        for (PaymentStatus paymentStatus : DRAFT_PAYMENT_STATUSES) {
            Scenario scenario = createScenario();
            applyDraftPaymentStatus(scenario, paymentStatus);
            scenariosByPaymentStatus.put(paymentStatus, scenario);
        }

        assertDraftOrder(unpaid, null);
        for (PaymentStatus paymentStatus : DRAFT_PAYMENT_STATUSES) {
            assertDraftOrder(scenariosByPaymentStatus.get(paymentStatus), paymentStatus);
        }
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("최초 확정 트랜잭션이 rollback되면 Kafka 이벤트를 발행하지 않는다.")
    void doesNotPublishWhenConfirmationTransactionRollsBack() {
        Scenario scenario = createScenario();
        var plan = prepare(scenario);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            transactionService.recordResult(scenario.orderId(), approved(plan.request()));
            status.setRollbackOnly();
        });

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        Order rolledBack = readOrder(scenario.orderId());
        assertThat(rolledBack.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(rolledBack.getPayment().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(readReservationStatus(scenario.orderId())).isEqualTo(StockReservationStatus.RESERVED);
    }

    @Test
    @DisplayName("이미 완료된 주문에 확정 서비스를 다시 호출해도 새 이벤트를 발행하지 않는다.")
    void doesNotPublishAgainWhenConfirmingAlreadyConfirmedOrder() {
        Scenario scenario = createScenario();
        String paymentKey = "payment-" + UUID.randomUUID();
        String attemptKey = "attempt-" + UUID.randomUUID();
        when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));
        orderUseCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);

        assertThatThrownBy(() -> orderUseCase.confirm(
            scenario.buyerId(), scenario.orderId(),
            "retry-payment-" + UUID.randomUUID(), "retry-attempt-" + UUID.randomUUID()))
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED));

        verify(paymentGateway, times(1)).approve(any());
        verify(kafkaTemplate, times(1)).send(eq(orderEventTopic()), eq(scenario.orderId().toString()), any());
        assertThat(readOrder(scenario.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    private Scenario createScenario() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            Product first = new Product(1L, "order event first " + UUID.randomUUID(), 2_000L);
            Product second = new Product(1L, "order event second " + UUID.randomUUID(), 3_000L);
            entityManager.persist(first);
            entityManager.persist(second);
            entityManager.flush();
            entityManager.persist(new Stock(first.getId(), 5));
            entityManager.persist(new Stock(second.getId(), 5));
            User buyer = new User();
            entityManager.persist(buyer);
            entityManager.flush();
            entityManager.persist(new Point(buyer.getId(), 20_000L));
            Order order = new Order(buyer.getId(), List.of(
                OrderItem.of(first, 2),
                OrderItem.of(second, 1)
            ));
            entityManager.persist(order);
            entityManager.flush();
            return new Scenario(buyer.getId(), order.getId(), first.getId(), second.getId());
        });
    }

    private OrderPaymentTransactionService.DispatchPlan prepare(Scenario scenario) {
        return transactionService.prepare(
            scenario.buyerId(), scenario.orderId(), "payment-" + UUID.randomUUID(), "attempt-" + UUID.randomUUID());
    }

    private PaymentGateway.Approved approved(ApprovalRequest request) {
        return new PaymentGateway.Approved(
            request.paymentKey(), request.providerOrderId(), request.amount(), APPROVED_AT);
    }

    private ConfirmedOrder confirmPgOrder() {
        Scenario scenario = createScenario();
        String paymentKey = "payment-" + UUID.randomUUID();
        String attemptKey = "attempt-" + UUID.randomUUID();
        when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));
        Order order = orderUseCase.confirm(scenario.buyerId(), scenario.orderId(), paymentKey, attemptKey);
        return new ConfirmedOrder(scenario, order);
    }

    private Order readOrder(Long orderId) {
        entityManager.clear();
        return entityManager.find(Order.class, orderId);
    }

    private void applyDraftPaymentStatus(Scenario scenario, PaymentStatus paymentStatus) {
        switch (paymentStatus) {
            case PENDING -> prepare(scenario);
            case UNKNOWN -> {
                prepare(scenario);
                transactionService.recordResult(scenario.orderId(), new PaymentGateway.Unknown());
            }
            case FAILED -> {
                prepare(scenario);
                transactionService.recordResult(scenario.orderId(), new PaymentGateway.ProviderError(
                    403, "REJECT_CARD_PAYMENT", "payment rejected"));
            }
            case CANCELED -> {
                var plan = prepare(scenario);
                transactionService.recordResult(scenario.orderId(), new PaymentGateway.Unknown());
                transactionService.recordCancellationResultAndReleaseReservation(scenario.orderId(),
                    new PaymentGateway.CancellationConfirmed(plan.request().paymentKey(), null));
            }
            case CANCEL_PROCESSING -> {
                prepare(scenario);
                transactionService.recordResult(scenario.orderId(), new PaymentGateway.Unknown());
                transactionService.recordCancellationResultAndReleaseReservation(scenario.orderId(),
                    new PaymentGateway.CancellationUnknown());
            }
            case SUCCESS -> throw new IllegalArgumentException("SUCCESS cannot leave the order DRAFT");
        }
    }

    private void assertDraftOrder(Scenario scenario, PaymentStatus expectedPaymentStatus) {
        Order order = readOrder(scenario.orderId());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        if (expectedPaymentStatus == null) {
            assertThat(order.getPayment()).isNull();
            return;
        }
        assertThat(order.getPayment().status()).isEqualTo(expectedPaymentStatus);
    }

    private StockReservationStatus readReservationStatus(Long orderId) {
        return entityManager.createQuery(
                "select o.stockReservationStatus from Order o where o.id = :orderId", StockReservationStatus.class)
            .setParameter("orderId", orderId)
            .getSingleResult();
    }

    private Instant readConfirmedAt(Long orderId) {
        Object value = entityManager.createNativeQuery(
                "select confirmed_at from orders where id = :orderId")
            .setParameter("orderId", orderId)
            .getSingleResult();
        return ((Timestamp) value).toInstant();
    }

    private void assertOrderIsCommittedAsConfirmed(Long orderId) {
        TransactionTemplate independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        String status = independentTransaction.execute(transaction -> jdbcTemplate.queryForObject(
            "select status from orders where id = ?", String.class, orderId));

        assertThat(status).isEqualTo(OrderStatus.CONFIRMED.name());
    }

    private SendResult<Object, Object> successfulSendResult() {
        RecordMetadata metadata = new RecordMetadata(
            new TopicPartition(orderEventTopic(), 1), 0L, 0, 0L, -1, -1);
        ProducerRecord<Object, Object> record = new ProducerRecord<>(orderEventTopic(), "42", "{}");
        return new SendResult<>(record, metadata);
    }

    private Schema orderConfirmedSchema() {
        String schemaPath = orderConfirmedContract.path("payloadSchema").asText();
        InputStream schema = getClass().getClassLoader()
            .getResourceAsStream("events/order/" + schemaPath);
        assertThat(schema).as("OrderConfirmed v1 JSON Schema resource").isNotNull();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(
                SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
        return registry.getSchema(schema);
    }

    private String orderEventTopic() {
        return orderConfirmedContract.path("topic").asText();
    }

    private void ensureTopicExists(String topic) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaClientProperties())) {
            if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(topic)) {
                admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
            }
        }
    }

    private boolean readBrokerContainsEvent(String topic, String eventId) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaClientProperties())) {
            var topicDescription = admin.describeTopics(List.of(topic)).allTopicNames()
                .get(10, TimeUnit.SECONDS).get(topic);
            List<TopicPartition> partitions = topicDescription.partitions().stream()
                .map(partition -> new TopicPartition(topic, partition.partition()))
                .toList();
            try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(kafkaConsumerProperties())) {
                consumer.assign(partitions);
                consumer.seekToBeginning(partitions);
                Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
                long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
                do {
                    var records = consumer.poll(Duration.ofMillis(100));
                    for (var record : records) {
                        String payload = new String(record.value(), StandardCharsets.UTF_8);
                        if (payload.contains(eventId)) {
                            return true;
                        }
                    }
                    boolean reachedSnapshotEnd = partitions.stream()
                        .allMatch(partition -> consumer.position(partition) >= endOffsets.get(partition));
                    if (reachedSnapshotEnd) {
                        return false;
                    }
                } while (System.nanoTime() < deadline);
                throw new AssertionError("Timed out scanning Kafka topic " + topic + " for event " + eventId);
            }
        }
    }

    private Map<String, Object> kafkaClientProperties() {
        Map<String, Object> properties = new HashMap<>();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers());
        return properties;
    }

    private Map<String, Object> kafkaConsumerProperties() {
        Map<String, Object> properties = kafkaClientProperties();
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "week6-publication-gap-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return properties;
    }

    private String kafkaBootstrapServers() {
        return System.getenv().getOrDefault("BOOTSTRAP_SERVERS", "localhost:19092");
    }

    private record Scenario(Long buyerId, Long orderId, Long firstProductId, Long secondProductId) {
    }

    private record ConfirmedOrder(Scenario scenario, Order order) {
    }

}
