package com.loopers.application.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.fixture.OrderFixture;
import com.loopers.application.order.OrderConfirmedPublisher;
import com.loopers.application.payment.fixture.OrderPaymentFixture;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.StockReservationStatus;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.infrastructure.order.StockReservationJpaRepository;
import com.loopers.kafka.event.OrderConfirmed;
import com.loopers.support.payment.LocalPgServer;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class OrderConfirmedPublicationTest {
    private static final LocalPgServer PG = LocalPgServer.start();
    private static final String TOPIC = "order-events-publication-" + UUID.randomUUID();
    private static final List<TopicPartition> PARTITIONS = List.of(
            new TopicPartition(TOPIC, 0), new TopicPartition(TOPIC, 1));
    private static final Instant CLOCK_TIME = Instant.parse("2026-10-29T01:00:00.123456789Z");
    private static final Instant CONFIRMED_AT = CLOCK_TIME.truncatedTo(ChronoUnit.MICROS);

    @Autowired private RequestOrderPaymentFacade requestPayment;
    @Autowired private ReconcileOrderPaymentFacade reconcilePayment;
    @Autowired private ReceivePaymentCallbackFacade receiveCallback;
    @Autowired private OrderPaymentTransactions transactions;
    @Autowired private PaymentGateway paymentGateway;
    @Autowired private OrderPaymentFixture paymentFixture;
    @Autowired private OrderFixture orders;
    @Autowired private DatabaseCleanUp cleanUp;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private StockReservationJpaRepository reservations;
    @MockitoBean(name = "orderConfirmationClock") private Clock confirmationClock;
    @MockitoSpyBean private OrderConfirmedPublisher publisher;

    private AdminClient admin;
    private Map<TopicPartition, Long> offsetsBefore;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("payment-provider.base-url", PG::baseUrl);
        registry.add("order-events.topic", () -> TOPIC);
    }

    @BeforeEach
    void prepare() throws Exception {
        PG.reset();
        when(confirmationClock.instant()).thenReturn(CLOCK_TIME);
        admin = AdminClient.create(Map.of("bootstrap.servers", KafkaTestContainersConfig.bootstrapServers()));
        if (!admin.listTopics().names().get(10, TimeUnit.SECONDS).contains(TOPIC)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 2, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        offsetsBefore = endOffsets();
    }

    @AfterEach
    void clean() {
        admin.close(Duration.ofSeconds(5));
        cleanUp.deleteAllEntities();
    }

    @AfterAll
    static void closeResources() throws Exception {
        try (AdminClient cleanup = AdminClient.create(
                Map.of("bootstrap.servers", KafkaTestContainersConfig.bootstrapServers()))) {
            cleanup.deleteTopics(List.of(TOPIC)).all().get(10, TimeUnit.SECONDS);
        } finally {
            PG.close();
        }
    }

    @Test
    void PG_성공으로_처음_확정된_주문을_Kafka에_발행한다() throws Exception {
        // Arrange
        var unpaid = paymentFixture.createUnpaidOrder();
        var accepted = requestPayment.request(unpaid.userId(), unpaid.orderId(),
                paymentFixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        assertThat(orders.order(unpaid.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(endOffsets()).isEqualTo(offsetsBefore);

        // Act
        reconcilePayment.reconcile(unpaid.userId(), unpaid.orderId());

        // Assert
        var confirmed = orders.order(unpaid.orderId());
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        var record = readPublishedRecord();
        var event = objectMapper.readValue(record.value(), OrderConfirmed.class);
        assertThat(record.key()).isEqualTo(Long.toString(unpaid.orderId()));
        assertThat(event.eventId()).isEqualTo("order-confirmed:v1:" + unpaid.orderId());
        assertThat(event.confirmedAt()).isEqualTo(confirmed.getConfirmedAt());
        assertThat(event.confirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(event.items()).containsExactly(new OrderConfirmed.Item(unpaid.productId(), unpaid.orderQuantity()));
        assertThat(objectMapper.readTree(record.value()).get("confirmedAt").asText())
                .isEqualTo(CONFIRMED_AT.toString());
        assertThat(publishedRecordCount()).isEqualTo(1);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 트랜잭션_안에서는_발행하지_않고_실제_커밋이_끝난_뒤에만_발행한다() throws Exception {
        // Arrange
        var pending = prepareSuccessfulPgPayment();
        var observed = paymentGateway.findByTransactionKey(pending.transactionKey());
        var transaction = new TransactionTemplate(transactionManager);
        List<OrderStatus> statesObservedBeforeSend = new ArrayList<>();
        doAnswer(invocation -> {
            Order saved = readCommittedOrder(pending.orderId());
            statesObservedBeforeSend.add(saved.getStatus());
            assertThat(saved.getConfirmedAt()).isEqualTo(CONFIRMED_AT);
            return invocation.callRealMethod();
        }).when(publisher).publish(any(OrderConfirmed.class));
        assertThat(orders.order(pending.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(publishedRecordCount()).isZero();

        // Act
        transaction.executeWithoutResult(status -> {
            transactions.recordLookup(pending.orderId(), observed);
            assertThat(readCommittedOrder(pending.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(publishedRecordCount()).isZero();
            assertThat(statesObservedBeforeSend).isEmpty();
        });

        // Assert
        assertThat(statesObservedBeforeSend).containsExactly(OrderStatus.CONFIRMED);
        assertThat(readPublishedRecord().key()).isEqualTo(Long.toString(pending.orderId()));
        assertThat(publishedRecordCount()).isEqualTo(1);
    }

    @Test
    void 이벤트를_알린_뒤_확정_트랜잭션이_롤백되면_Kafka에_발행하지_않는다() {
        // Arrange
        var pending = prepareSuccessfulPgPayment();
        var observed = paymentGateway.findByTransactionKey(pending.transactionKey());
        var transaction = new TransactionTemplate(transactionManager);
        assertThat(orders.order(pending.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(orders.order(pending.orderId()).getConfirmedAt()).isNull();
        assertThat(publishedRecordCount()).isZero();

        // Act
        assertThrows(RollbackForTest.class, () -> transaction.executeWithoutResult(status -> {
            transactions.recordLookup(pending.orderId(), observed);
            assertThat(publishedRecordCount()).isZero();
            throw new RollbackForTest();
        }));

        // Assert
        assertThat(orders.order(pending.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(orders.order(pending.orderId()).getConfirmedAt()).isNull();
        assertThat(reservations.findByOrderIdAndStatusOrderByProductIdAsc(
                pending.orderId(), StockReservationStatus.HELD)).hasSize(1);
        assertThat(publishedRecordCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 콜백과_reconcile을_반복해도_이벤트와_최초_확정_시각은_하나다(boolean callbackFirst) throws Exception {
        // Arrange
        var pending = prepareSuccessfulPgPayment();
        var callback = new ReceivePaymentCallbackFacade.Command(
                pending.providerOrderId(), pending.transactionKey(), PaymentStatus.SUCCESS);
        assertThat(publishedRecordCount()).isZero();

        // Act
        if (callbackFirst) {
            receiveCallback.receive(callback);
            when(confirmationClock.instant()).thenReturn(CLOCK_TIME.plusSeconds(60));
            reconcilePayment.reconcile(pending.userId(), pending.orderId());
        } else {
            reconcilePayment.reconcile(pending.userId(), pending.orderId());
            when(confirmationClock.instant()).thenReturn(CLOCK_TIME.plusSeconds(60));
            receiveCallback.receive(callback);
        }
        reconcilePayment.reconcile(pending.userId(), pending.orderId());
        receiveCallback.receive(callback);

        // Assert
        assertThat(publishedRecordCount()).isEqualTo(1);
        var event = objectMapper.readValue(readPublishedRecord().value(), OrderConfirmed.class);
        assertThat(event.eventId()).isEqualTo("order-confirmed:v1:" + pending.orderId());
        assertThat(event.confirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(orders.order(pending.orderId()).getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(PG.createRequestCount()).isEqualTo(1);
    }

    @Test
    void 주문_커밋_후_전송_직전_실패해도_주문과_예약_소비는_성공으로_남는다(CapturedOutput output) {
        // Arrange
        var pending = prepareSuccessfulPgPayment();
        doThrow(new IllegalStateException("통제된 send 직전 실패"))
                .when(publisher).publish(any(OrderConfirmed.class));
        assertThat(orders.order(pending.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(publishedRecordCount()).isZero();

        // Act
        var result = reconcilePayment.reconcile(pending.userId(), pending.orderId());

        // Assert
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orders.order(pending.orderId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(orders.order(pending.orderId()).getConfirmedAt()).isEqualTo(CONFIRMED_AT);
        assertThat(reservations.findByOrderIdAndStatusOrderByProductIdAsc(
                pending.orderId(), StockReservationStatus.CONSUMED)).hasSize(1);
        assertThat(publishedRecordCount()).isZero();
        assertThat(PG.createRequestCount()).isEqualTo(1);
        assertThat(output).contains("order_confirmation_publication_unconfirmed")
                .contains("order-confirmed:v1:" + pending.orderId());
    }

    @Test
    void 콜백과_reconcile이_동시에_성공을_반영해도_이벤트는_하나만_발행된다() throws Exception {
        // Arrange
        var pending = prepareSuccessfulPgPayment();
        var callback = new ReceivePaymentCallbackFacade.Command(
                pending.providerOrderId(), pending.transactionKey(), PaymentStatus.SUCCESS);
        var startTogether = new CountDownLatch(1);
        assertThat(publishedRecordCount()).isZero();

        try (var callers = Executors.newFixedThreadPool(2)) {
            // Act
            var first = callers.submit(() -> {
                startTogether.await();
                return receiveCallback.receive(callback);
            });
            var second = callers.submit(() -> {
                startTogether.await();
                return reconcilePayment.reconcile(pending.userId(), pending.orderId());
            });
            startTogether.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            // Assert
            assertThat(publishedRecordCount()).isEqualTo(1);
            assertThat(readPublishedRecord().key()).isEqualTo(Long.toString(pending.orderId()));
            assertThat(orders.order(pending.orderId()).getConfirmedAt()).isEqualTo(CONFIRMED_AT);
            assertThat(PG.createRequestCount()).isEqualTo(1);
        }
    }

    @Test
    void 초안과_PG_대기와_실패_상태에서는_확정_이벤트가_없다() {
        // Arrange
        var unpaid = paymentFixture.createUnpaidOrder();
        assertThat(orders.order(unpaid.orderId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(publishedRecordCount()).isZero();

        // Act
        var accepted = requestPayment.request(unpaid.userId(), unpaid.orderId(),
                paymentFixture.createPaymentRequestCommand());
        assertThat(orders.order(unpaid.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(publishedRecordCount()).isZero();
        PG.failPaymentForOrder(accepted.providerOrderId());
        reconcilePayment.reconcile(unpaid.userId(), unpaid.orderId());

        // Assert
        assertThat(orders.order(unpaid.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(orders.order(unpaid.orderId()).getConfirmedAt()).isNull();
        assertThat(publishedRecordCount()).isZero();
    }

    @Test
    void PG_응답이_유실되어_UNKNOWN이면_확정_이벤트를_발행하지_않는다() {
        // Arrange
        var unpaid = paymentFixture.createUnpaidOrder();
        PG.loseResponseAfterCreatingTransaction();
        assertThat(publishedRecordCount()).isZero();

        // Act
        var result = requestPayment.request(unpaid.userId(), unpaid.orderId(),
                paymentFixture.createPaymentRequestCommand());

        // Assert
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(orders.order(unpaid.orderId()).getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(orders.order(unpaid.orderId()).getConfirmedAt()).isNull();
        assertThat(publishedRecordCount()).isZero();
    }

    @Test
    void 이전_POINT_확정_주문을_조회해도_이벤트를_소급_발행하지_않는다() {
        // Arrange
        long buyer = paymentFixture.createBuyer();
        var product = paymentFixture.createProductWithStock(3_500, 5);
        var previousOrder = orders.createConfirmedOrder(buyer,
                List.of(new Order.RequestedItem(product.getId(), 2, product.getPrice())));
        assertThat(publishedRecordCount()).isZero();

        // Act
        var stored = orders.order(previousOrder.getId());

        // Assert
        assertThat(stored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(stored.getConfirmedAt()).isNull();
        assertThat(publishedRecordCount()).isZero();
        assertThat(PG.createRequestCount()).isZero();
    }

    private PendingConfirmation prepareSuccessfulPgPayment() {
        var unpaid = paymentFixture.createUnpaidOrder();
        var accepted = requestPayment.request(unpaid.userId(), unpaid.orderId(),
                paymentFixture.createPaymentRequestCommand());
        PG.succeedPaymentForOrder(accepted.providerOrderId());
        return new PendingConfirmation(unpaid.orderId(), unpaid.userId(),
                accepted.providerOrderId(), accepted.transactionKey());
    }

    private Order readCommittedOrder(long orderId) {
        var independentTransaction = new TransactionTemplate(transactionManager);
        independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independentTransaction.setReadOnly(true);
        return independentTransaction.execute(status -> orders.order(orderId));
    }

    private long publishedRecordCount() {
        var current = endOffsets();
        return PARTITIONS.stream().mapToLong(partition -> current.get(partition) - offsetsBefore.get(partition)).sum();
    }

    private Map<TopicPartition, Long> endOffsets() {
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        PARTITIONS.forEach(partition -> latest.put(partition, OffsetSpec.latest()));
        try {
            var result = admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, Long> offsets = new HashMap<>();
            result.forEach((partition, info) -> offsets.put(partition, info.offset()));
            return offsets;
        } catch (Exception exception) {
            throw new IllegalStateException("실제 broker의 끝 오프셋을 읽지 못했습니다.", exception);
        }
    }

    private ConsumerRecord<String, byte[]> readPublishedRecord() {
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(Map.of(
                "bootstrap.servers", KafkaTestContainersConfig.bootstrapServers(),
                "group.id", "publication-check-" + UUID.randomUUID(),
                "enable.auto.commit", false,
                "key.deserializer", StringDeserializer.class,
                "value.deserializer", ByteArrayDeserializer.class))) {
            consumer.assign(PARTITIONS);
            offsetsBefore.forEach(consumer::seek);
            List<ConsumerRecord<String, byte[]>> received = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (received.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(250)).forEach(received::add);
            }
            assertThat(received).as("새로 발행된 주문 확정 이벤트").hasSize(1);
            return received.getFirst();
        }
    }

    private record PendingConfirmation(long orderId, long userId, String providerOrderId, String transactionKey) {}

    private static class RollbackForTest extends RuntimeException {}
}
