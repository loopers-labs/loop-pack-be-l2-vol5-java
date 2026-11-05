package com.loopers.application.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderOutbox;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderConfirmedMessage;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.order.OrderOutboxJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** 실제 MySQL에서 주문 확정·예약 소비와 Outbox가 같은 TX C로 commit·rollback되는지 확인한다. relay는 쓰지 않는다. */
@SpringBootTest
class OrderConfirmationOutboxAtomicityTest {
    private static final FakePaymentServer PG = startPg();
    private static final Instant NOW = Instant.parse("2026-11-02T01:00:00.123456789Z");
    private static final Instant STORED_TIME = Instant.parse("2026-11-02T01:00:00.123456Z");
    private static final int STOCK_AFTER_RESERVATION = 7;

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orders;
    @Autowired private OrderPaymentTransactions payments;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired private BrandJpaRepository brands;
    @Autowired private ProductJpaRepository products;
    @Autowired private OrderJpaRepository orderRepository;
    @Autowired private PaymentAttemptJpaRepository attempts;
    @Autowired private OrderOutboxJpaRepository outbox;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoSpyBean private ObjectMapper mapper;
    @MockitoBean private Clock clock;

    private Long userId;
    private Long orderId;
    private Long productId;
    private Long secondProductId;

    @BeforeEach
    void setUp() {
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        userId = registration.register().getId();
        Long brandId = brands.save(new BrandModel("테스트 브랜드", null)).getId();
        productId = products.save(new ProductModel(brandId, "양말", 1_000L, 10)).getId();
        secondProductId = products.save(new ProductModel(brandId, "모자", 2_000L, 10)).getId();
        orderId = orders.create(userId, List.of(
            new OrderFacade.OrderLine(productId, 1),
            new OrderFacade.OrderLine(secondProductId, 1),
            new OrderFacade.OrderLine(productId, 2)
        )).id();
        orders.confirm(userId, orderId,
            new OrderFacade.PaymentCommand("outbox-atomicity-test", "TEST", "0000-0000-0000-0000"));
    }

    @AfterEach
    void tearDown() {
        cleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void firstSuccessCommitsConfirmationAndPendingOutboxWithFixedFacts() throws Exception {
        recordSuccess();

        payments.applyRecordedResult(orderId);

        assertConfirmed();
        OrderOutbox stored = onlyOutbox();
        assertThat(stored.getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(stored.getOrderId()).isEqualTo(orderId);
        assertThat(stored.getEventType()).isEqualTo("OrderConfirmed");
        assertThat(stored.getSchemaVersion()).isEqualTo(1);
        assertThat(stored.getMessageKey()).isEqualTo(orderId.toString());
        assertThat(mapper.readTree(stored.getPayload())).isEqualTo(mapper.readTree("""
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d",
             "orderId":%d,"confirmedAt":"%s",
             "items":[{"productId":%d,"quantity":3},{"productId":%d,"quantity":1}]}
            """.formatted(orderId, orderId, STORED_TIME, productId, secondProductId)));
        assertThat(stored.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(stored.getAttemptCount()).isZero();
        assertThat(stored.getNextAttemptAt()).isEqualTo(STORED_TIME);
        assertThat(stored.getLastError()).isNull();
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getPublishedAt()).isNull();
    }

    @Test
    void repeatedResultApplicationKeepsOneOutboxAndFirstFacts() {
        recordSuccess();
        payments.applyRecordedResult(orderId);
        String firstPayload = onlyOutbox().getPayload();

        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        payments.applyRecordedResult(orderId);
        orders.reconcile(userId, orderId);
        orders.handleCallback(attempts.findByOrderId(orderId).orElseThrow().getProviderOrderId());

        assertConfirmed();
        assertThat(onlyOutbox().getPayload()).isEqualTo(firstPayload);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED"})
    void nonSuccessResultCreatesNoOutbox(String result) {
        PG.changeStatus("TEST-TX-1", result);

        orders.reconcile(userId, orderId);

        assertThat(order().getConfirmedAt()).isNull();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void unknownLookupCreatesNoOutbox() {
        PG.replyNext(503, "{}");
        PG.replyNext(503, "{}");
        PG.replyNext(503, "{}");

        OrderPaymentInfo result = orders.reconcile(userId, orderId);

        assertThat(result.checked()).isFalse();
        assertUnconfirmedWithoutOutbox();
    }

    /** 직렬화 실패: TX C만 rollback되고, 저장해 둔 TX B 결과로 PG 호출 없이 다시 확정한다. */
    @Test
    void serializationFailureRollsBackConfirmationAndStoredResultRecoversWithoutPg() throws Exception {
        doThrow(new JsonProcessingException("test: serialization failure") {})
            .when(mapper).writeValueAsString(any(OrderConfirmedMessage.class));
        recordSuccess();
        int lookupsBeforeFailure = PG.lookupCalls();

        assertThatThrownBy(() -> payments.applyRecordedResult(orderId)).isInstanceOf(IllegalStateException.class);

        assertUnconfirmedWithoutOutbox();
        assertThat(attempts.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);

        doCallRealMethod().when(mapper).writeValueAsString(any(OrderConfirmedMessage.class));
        orders.reconcile(userId, orderId);

        assertConfirmed();
        assertThat(onlyOutbox().getEventId()).isEqualTo("order-confirmed:v1:" + orderId);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(lookupsBeforeFailure);
    }

    /** 실제 INSERT 실패: 같은 eventId 행이 이미 있으면 DB 유일 제약이 막고 주문 확정도 함께 rollback된다. */
    @Test
    void outboxInsertFailureRollsBackConfirmationAndReservation() {
        outbox.save(OrderOutbox.pending(new OrderConfirmed(orderId, STORED_TIME.minusSeconds(1),
            List.of(new OrderConfirmed.Item(productId, 1))), "OrderConfirmed", 1, "{}"));
        recordSuccess();

        assertThatThrownBy(() -> payments.applyRecordedResult(orderId))
            .isInstanceOf(DataIntegrityViolationException.class);

        OrderModel rolledBack = order();
        assertThat(rolledBack.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(rolledBack.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(rolledBack.getConfirmedAt()).isNull();
        assertThat(onlyOutbox().getPayload()).isEqualTo("{}");
        assertThat(attempts.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
    }

    /** Outbox가 INSERT된 뒤 같은 TX가 실패하면 별도 commit 없이 주문과 함께 사라져야 한다. */
    @Test
    void failureAfterOutboxInsertRollsBackBothThenLaterApplicationConfirmsOnce() {
        recordSuccess();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            payments.applyRecordedResult(orderId);
            entityManager.flush();
            throw new IllegalStateException("test: rollback after outbox insert");
        })).isInstanceOf(IllegalStateException.class);

        assertUnconfirmedWithoutOutbox();
        assertThat(orderRepository.existsById(orderId)).isTrue();
        assertThat(attempts.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);

        payments.applyRecordedResult(orderId);

        assertConfirmed();
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void concurrentResultApplicationStoresOneOutbox() throws Exception {
        recordSuccess();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = IntStream.range(0, 2).mapToObj(ignored -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test: result application start timed out");
                }
                return payments.applyRecordedResult(orderId);
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        } finally {
            start.countDown();
        }

        assertConfirmed();
        assertThat(outbox.count()).isEqualTo(1);
    }

    private void recordSuccess() {
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        payments.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"), PaymentEvent.Source.RECONCILE);
    }

    private OrderModel order() {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private OrderOutbox onlyOutbox() {
        assertThat(outbox.count()).isEqualTo(1);
        return outbox.findAll().getFirst();
    }

    private void assertConfirmed() {
        OrderModel confirmed = order();
        assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
        assertThat(confirmed.getConfirmedAt()).isEqualTo(STORED_TIME);
        assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(STOCK_AFTER_RESERVATION);
    }

    private void assertUnconfirmedWithoutOutbox() {
        OrderModel unconfirmed = order();
        assertThat(unconfirmed.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(unconfirmed.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(unconfirmed.getConfirmedAt()).isNull();
        assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(STOCK_AFTER_RESERVATION);
        assertThat(outbox.count()).isZero();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
