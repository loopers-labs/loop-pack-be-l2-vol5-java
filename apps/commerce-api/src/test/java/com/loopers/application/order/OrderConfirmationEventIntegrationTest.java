package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderConfirmationPublisher;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 실제 MySQL TX와 PG 경로를 사용하고 발행 포트만 대역으로 두어 커밋 경계를 검증한다. */
@SpringBootTest(properties = "commerce.order-events.enabled=true")
class OrderConfirmationEventIntegrationTest {
    private static final FakePaymentServer PG = startPg();
    private static final Instant NOW = Instant.parse("2026-10-25T01:00:00.123456789Z");
    private static final Instant STORED_TIME = Instant.parse("2026-10-25T01:00:00.123456Z");

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
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp cleanUp;
    @MockitoBean private OrderConfirmationPublisher publisher;
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
            new OrderFacade.PaymentCommand("confirmation-event-test", "TEST", "0000-0000-0000-0000"));
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
    void publishesSnapshotOnlyAfterFirstConfirmationIsVisibleInAnotherTransaction() {
        doAnswer(invocation -> {
            OrderModel committed = readInNewTransaction();
            assertThat(committed.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(committed.getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED);
            assertThat(committed.getConfirmedAt()).isEqualTo(STORED_TIME);
            return null;
        }).when(publisher).publish(any());
        recordSuccess();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            payments.applyRecordedResult(orderId);
            entityManager.flush();
            verifyNoInteractions(publisher);
        });

        ArgumentCaptor<OrderConfirmed> event = ArgumentCaptor.forClass(OrderConfirmed.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().orderId()).isEqualTo(orderId);
        assertThat(event.getValue().confirmedAt()).isEqualTo(STORED_TIME);
        assertThat(event.getValue().items()).containsExactly(
            new OrderConfirmed.Item(productId, 3), new OrderConfirmed.Item(secondProductId, 1)
        );

        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        payments.applyRecordedResult(orderId);
        orders.reconcile(userId, orderId);
        orders.handleCallback(attempts.findByOrderId(orderId).orElseThrow().getProviderOrderId());

        verify(publisher, times(1)).publish(any());
        assertThat(readInNewTransaction().getConfirmedAt()).isEqualTo(STORED_TIME);
        assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(7);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void rollbackAfterPreparingEventDoesNotPublishAndRecoveryUsesItsOwnConfirmationTime() {
        recordSuccess();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            payments.applyRecordedResult(orderId);
            entityManager.flush();
            verifyNoInteractions(publisher);
            throw new IllegalStateException("test: rollback after preparing order event");
        })).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(publisher);
        OrderModel rolledBack = readInNewTransaction();
        assertThat(rolledBack.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(rolledBack.getConfirmedAt()).isNull();
        assertThat(attempts.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);

        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        payments.applyRecordedResult(orderId);

        ArgumentCaptor<OrderConfirmed> event = ArgumentCaptor.forClass(OrderConfirmed.class);
        verify(publisher).publish(event.capture());
        assertThat(event.getValue().confirmedAt()).isEqualTo(STORED_TIME.plusSeconds(60));
        assertThat(readInNewTransaction().getConfirmedAt()).isEqualTo(event.getValue().confirmedAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED"})
    void unfinishedOrFailedPaymentDoesNotPublish(String result) {
        PG.changeStatus("TEST-TX-1", result);

        orders.reconcile(userId, orderId);

        verifyNoInteractions(publisher);
        assertThat(readInNewTransaction().getConfirmedAt()).isNull();
    }

    @Test
    void unavailableLookupDoesNotPublishOrConfirmOrder() {
        PG.replyNext(503, "{}");
        PG.replyNext(503, "{}");
        PG.replyNext(503, "{}");

        OrderPaymentInfo result = orders.reconcile(userId, orderId);

        assertThat(result.checked()).isFalse();
        verifyNoInteractions(publisher);
        assertThat(readInNewTransaction().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(readInNewTransaction().getConfirmedAt()).isNull();
    }

    @Test
    void concurrentResultApplicationPublishesOneConfirmation() throws Exception {
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

        verify(publisher, times(1)).publish(any());
        assertThat(readInNewTransaction().getConfirmedAt()).isEqualTo(STORED_TIME);
        assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(7);
    }

    @Test
    void postCommitPublisherFailureKeepsConfirmedOrderAndDoesNotCreatePaymentAgain() {
        doThrow(new IllegalStateException("test: publisher unavailable")).when(publisher).publish(any());
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        OrderPaymentInfo result = orders.reconcile(userId, orderId);
        OrderPaymentInfo repeated = orders.reconcile(userId, orderId);

        assertThat(result.isFinished()).isTrue();
        assertThat(result.checked()).isTrue();
        assertThat(repeated.isFinished()).isTrue();
        assertThat(readInNewTransaction().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(readInNewTransaction().getConfirmedAt()).isEqualTo(STORED_TIME);
        assertThat(products.findById(productId).orElseThrow().getStockQuantity()).isEqualTo(7);
        assertThat(PG.createCalls()).isEqualTo(1);
        verify(publisher, times(1)).publish(any());
    }

    private void recordSuccess() {
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        payments.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"), PaymentEvent.Source.RECONCILE);
        verifyNoInteractions(publisher);
    }

    private OrderModel readInNewTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction.execute(status -> orderRepository.findById(orderId).orElseThrow());
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
