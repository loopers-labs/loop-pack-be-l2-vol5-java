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
import com.loopers.order.infrastructure.outbox.OrderConfirmedOutbox;
import com.loopers.order.infrastructure.outbox.OrderConfirmedOutboxRepository;
import com.loopers.order.infrastructure.outbox.OrderConfirmedOutboxStatus;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.Stock;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = "outbox.relay.enabled=false")
@Import(MySqlTestContainersConfig.class)
@DisplayName("OrderConfirmed Outbox 기록 통합 테스트")
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
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrderConfirmedOutboxRepository outboxRepository;
    @MockitoBean private PaymentGateway paymentGateway;
    @MockitoBean private KafkaTemplate<Object, Object> kafkaTemplate;
    private JsonNode orderConfirmedSchema;

    @BeforeEach
    void loadsOrderConfirmedSchema() throws IOException {
        try (InputStream schema = getClass().getClassLoader()
            .getResourceAsStream("schemas/order/order-confirmed.v1.schema.json")) {
            assertThat(schema).as("OrderConfirmed v1 JSON Schema resource").isNotNull();
            orderConfirmedSchema = objectMapper.readTree(schema);
        }
    }

    @Test
    @DisplayName("주문 확정 TX에서 계약 payload를 PENDING Outbox로 저장하고 Kafka로 직접 보내지 않는다")
    void storesContractPayloadInOutboxAsPartOfConfirmation() throws IOException {
        ConfirmedOrder confirmed = confirmPgOrder();
        Order order = readOrder(confirmed.scenario().orderId());
        int expectedItemCount = order.getItems().size();
        OrderConfirmedOutbox outbox = readOutbox(order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(outbox.getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PENDING);
        assertThat(outbox.getEventId()).isEqualTo("order-confirmed:" + order.getId());
        assertThat(outbox.getOrderId()).isEqualTo(order.getId());
        assertThat(outbox.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(outbox.getSchemaVersion()).isEqualTo(1);
        assertThat(outbox.getMessageKey()).isEqualTo(order.getId().toString());

        JsonNode payload = objectMapper.readTree(outbox.getPayload());
        assertThat(orderConfirmedSchema().validate(payload)).isEmpty();
        assertThat(payload.path("orderId").asLong()).isEqualTo(order.getId());
        assertThat(payload.path("eventId").asText()).isEqualTo(outbox.getEventId());
        assertThat(Instant.parse(payload.path("confirmedAt").asText())).isEqualTo(readConfirmedAt(order.getId()));
        assertThat(payload.path("items")).hasSize(expectedItemCount);
        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any());
    }

    @Test
    @DisplayName("UNKNOWN 결제가 재기동 대사에서 SUCCESS로 확인되면 Outbox를 기록한다")
    void storesOutboxWhenStartupRecoveryFindsUnknownPaymentApproved() {
        Scenario scenario = createScenario();
        OrderPaymentTransactionService.DispatchPlan plan = prepare(scenario);
        transactionService.recordResult(scenario.orderId(), new PaymentGateway.Unknown());
        when(paymentGateway.findByPaymentKey(plan.request().paymentKey())).thenReturn(approved(plan.request()));

        eventPublisher.publishEvent(new ApplicationReadyEvent(
            new SpringApplication(), new String[0], applicationContext, Duration.ZERO));

        Order confirmed = readOrder(scenario.orderId());
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getPayment().status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(readReservationStatus(scenario.orderId())).isEqualTo(StockReservationStatus.CONSUMED);
        assertThat(readOutbox(scenario.orderId()).getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PENDING);
        verify(paymentGateway).findByPaymentKey(plan.request().paymentKey());
        verify(paymentGateway, never()).approve(any());
        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any());
    }

    @Test
    @DisplayName("Outbox가 기록된 뒤 Kafka 발행을 기다리지 않아도 주문 확정과 예약 소비가 완료된다")
    void confirmationCommitsWithoutWaitingForKafka() {
        Scenario scenario = createScenario();
        when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));

        orderUseCase.confirm(scenario.buyerId(), scenario.orderId(),
            "payment-" + UUID.randomUUID(), "attempt-" + UUID.randomUUID());

        assertThat(readOrder(scenario.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(readReservationStatus(scenario.orderId())).isEqualTo(StockReservationStatus.CONSUMED);
        assertThat(readOutbox(scenario.orderId()).getStatus()).isEqualTo(OrderConfirmedOutboxStatus.PENDING);
        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any());
    }

    @Test
    @DisplayName("PG 미확정·카드 취소 결제 상태에서는 주문이 DRAFT에 남고 Outbox를 기록하지 않는다")
    void doesNotCreateOutboxWhileOrdersRemainDraft() {
        Scenario unpaid = createScenario();
        Map<PaymentStatus, Scenario> scenariosByPaymentStatus = new EnumMap<>(PaymentStatus.class);

        for (PaymentStatus paymentStatus : DRAFT_PAYMENT_STATUSES) {
            Scenario scenario = createScenario();
            applyDraftPaymentStatus(scenario, paymentStatus);
            scenariosByPaymentStatus.put(paymentStatus, scenario);
        }

        assertDraftOrder(unpaid, null);
        assertThat(outboxRepository.findByEventId("order-confirmed:" + unpaid.orderId())).isEmpty();
        for (PaymentStatus paymentStatus : DRAFT_PAYMENT_STATUSES) {
            Scenario scenario = scenariosByPaymentStatus.get(paymentStatus);
            assertDraftOrder(scenario, paymentStatus);
            assertThat(outboxRepository.findByEventId("order-confirmed:" + scenario.orderId())).isEmpty();
        }
        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any());
    }

    @Test
    @DisplayName("최초 확정 트랜잭션이 rollback되면 주문 변경과 Outbox 기록이 함께 rollback된다")
    void rollsBackOutboxWhenConfirmationTransactionRollsBack() {
        Scenario scenario = createScenario();
        OrderPaymentTransactionService.DispatchPlan plan = prepare(scenario);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            transactionService.recordResult(scenario.orderId(), approved(plan.request()));
            status.setRollbackOnly();
        });

        assertThat(outboxRepository.findByEventId("order-confirmed:" + scenario.orderId())).isEmpty();
        Order rolledBack = readOrder(scenario.orderId());
        assertThat(rolledBack.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(rolledBack.getPayment().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(readReservationStatus(scenario.orderId())).isEqualTo(StockReservationStatus.RESERVED);
        verify(kafkaTemplate, never()).send(any(String.class), any(String.class), any());
    }

    @Test
    @DisplayName("이미 완료된 주문을 다시 확정해도 같은 Outbox 이벤트를 추가하지 않는다")
    void doesNotCreateAnotherOutboxWhenConfirmingAlreadyConfirmedOrder() {
        Scenario scenario = createScenario();
        when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));
        orderUseCase.confirm(scenario.buyerId(), scenario.orderId(),
            "payment-" + UUID.randomUUID(), "attempt-" + UUID.randomUUID());

        assertThatThrownBy(() -> orderUseCase.confirm(
            scenario.buyerId(), scenario.orderId(),
            "retry-payment-" + UUID.randomUUID(), "retry-attempt-" + UUID.randomUUID()))
            .isInstanceOfSatisfying(CoreException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_PROCESSED));

        verify(paymentGateway, times(1)).approve(any());
        assertThat(outboxRepository.findByEventId("order-confirmed:" + scenario.orderId())).isPresent();
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
        when(paymentGateway.approve(any())).thenAnswer(invocation -> approved(invocation.getArgument(0)));
        Order order = orderUseCase.confirm(
            scenario.buyerId(), scenario.orderId(), "payment-" + UUID.randomUUID(), "attempt-" + UUID.randomUUID());
        return new ConfirmedOrder(scenario, order);
    }

    private Order readOrder(Long orderId) {
        entityManager.clear();
        return entityManager.find(Order.class, orderId);
    }

    private OrderConfirmedOutbox readOutbox(Long orderId) {
        entityManager.clear();
        return outboxRepository.findByEventId("order-confirmed:" + orderId).orElseThrow();
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
        return ((java.sql.Timestamp) value).toInstant();
    }

    private Schema orderConfirmedSchema() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemaRegistryConfig(
                SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()));
        return registry.getSchema(orderConfirmedSchema);
    }

    private record Scenario(Long buyerId, Long orderId, Long firstProductId, Long secondProductId) { }

    private record ConfirmedOrder(Scenario scenario, Order order) { }
}
