package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.product.ProductModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.PaymentEventJpaRepository;
import com.loopers.infrastructure.payment.PaymentEventRepositoryImpl;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** 아무도 확인하지 않은 결제 시도를 배치가 reconcile과 같은 규칙으로 마무리하는지 실제 MySQL과 가짜 PG로 확인한다. */
@SpringBootTest
class PaymentRecoveryBatchTest {

    private static final FakePaymentServer PG = startPg();
    private static final Duration OLD = Duration.ofMinutes(11);
    private static final Duration LONG_UNRESOLVED = Duration.ofMinutes(31);

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

    @Autowired private PaymentRecoveryBatch batch;
    @Autowired private UserRegistrationService registration;
    @Autowired private OrderFacade orderFacade;
    @Autowired private OrderPaymentTransactions paymentTransactions;
    @Autowired private PaymentProvider paymentProvider;
    @Autowired private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired private BrandJpaRepository brandJpaRepository;
    @Autowired private ProductJpaRepository productJpaRepository;
    @Autowired private OrderJpaRepository orderJpaRepository;
    @Autowired private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired private PaymentEventJpaRepository paymentEventJpaRepository;
    @Autowired private TransactionTemplate transaction;
    @Autowired private EntityManager entityManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean private PaymentEventRepositoryImpl paymentEvents;

    private Long userId;
    private Long productId;

    @BeforeEach
    void setUp() {
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        userId = registration.register().getId();
        Long brandId = brandJpaRepository.save(new BrandModel("나이키", null)).getId();
        productId = productJpaRepository.save(new ProductModel(brandId, "양말", 1_000L, 100)).getId();
    }

    @AfterEach
    void tearDown() {
        reset(paymentEvents);
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void attemptYoungerThanTenMinutesIsNotTouched() {
        Long orderId = confirmedOrder();
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        age(orderId, Duration.ofMinutes(9));

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(0, 0, 0));
        assertThat(PG.lookupCalls()).isZero();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
    }

    @Test
    void oldPendingAttemptIsConfirmedByLookupWithoutAnotherCreate() {
        Long orderId = confirmedOrder();
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        age(orderId, OLD);

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(1, 0, 0));
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(stock()).isEqualTo(97);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(paymentEventJpaRepository.findAll()).extracting(PaymentEvent::getSource)
            .contains(PaymentEvent.Source.BATCH);
    }

    @Test
    void requestedAttemptWithoutPgTransactionKeepsReservationAndIsNeverSent() {
        Long orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
        paymentTransactions.start(userId, orderId, "attempt-" + orderId, "TEST", "0000-0000-0000-0000");
        age(orderId, OLD);

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(0, 1, 0));
        assertThat(paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(stock()).isEqualTo(97);
        assertThat(PG.createCalls()).isZero();
        assertThat(PG.requests().getLast().rawQuery()).startsWith("orderId=");
    }

    @Test
    void storedFailureWithoutOrderApplicationIsFinishedWithoutAskingPg() {
        Long orderId = confirmedOrder();
        PG.changeStatus("TEST-TX-1", "FAILED");
        paymentTransactions.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"),
            PaymentEvent.Source.RECONCILE);
        age(orderId, OLD);
        int lookupsBefore = PG.lookupCalls();

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(1, 0, 0));
        assertOrder(orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
        assertThat(stock()).isEqualTo(100);
        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore);
    }

    @Test
    void unreadableLookupLeavesStateAndNextAttemptIsStillProcessed() {
        Long first = confirmedOrder();
        Long second = confirmedOrder();
        PG.changeStatus("TEST-TX-2", "SUCCESS");
        age(first, OLD);
        age(second, OLD);
        for (int i = 0; i < 3; i++) {
            PG.replyNext(503, "");
        }

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(1, 1, 0));
        assertOrder(first, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertOrder(second, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(PG.createCalls()).isEqualTo(2);
    }

    @Test
    void openCircuitSkipsLookupAndKeepsState() {
        Long orderId = confirmedOrder();
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        age(orderId, OLD);
        resilientPaymentProvider.lookupCircuitBreaker().transitionToOpenState();

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(0, 1, 0));
        assertThat(PG.lookupCalls()).isZero();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
    }

    @Test
    void finishedOrdersAreNotCandidates() {
        Long orderId = confirmedOrder();
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        orderFacade.reconcile(userId, orderId);
        age(orderId, OLD);
        int lookupsBefore = PG.lookupCalls();

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(0, 0, 0));
        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore);
    }

    @Test
    void failureOfOneAttemptIsCountedAndTheRestContinue() {
        Long first = confirmedOrder();
        Long second = confirmedOrder();
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        PG.changeStatus("TEST-TX-2", "SUCCESS");
        age(first, OLD);
        age(second, OLD);
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failFirst.getAndSet(false)) {
                throw new IllegalStateException("test: first batch item failed");
            }
            return invocation.callRealMethod();
        }).when(paymentEvents).save(any(PaymentEvent.class));

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(1, 0, 1));
        assertOrder(first, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertOrder(second, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);

        reset(paymentEvents);
        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(1, 0, 0));
        assertOrder(first, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
    }

    @Test
    void scansBeyondOnePageAndRepeatedRunHasNoFurtherEffect() {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            Long orderId = confirmedOrder(1);
            PG.changeStatus("TEST-TX-" + (i + 1), "FAILED");
            age(orderId, OLD);
            orderIds.add(orderId);
        }

        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(51, 0, 0));
        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(0, 0, 0));

        assertThat(stock()).isEqualTo(100);
        assertThat(orderIds).allSatisfy(orderId ->
            assertOrder(orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED));
        assertThat(PG.lookupCalls()).isEqualTo(51);
        assertThat(PG.createCalls()).isEqualTo(51);
    }

    @Test
    void unknownAttemptWithoutPgTransactionKeepsReservationEvenAfterThirtyMinutes() {
        Long orderId = unknownOrderWithoutPgTransaction();
        age(orderId, LONG_UNRESOLVED);

        PaymentRecoveryBatch.Result result = batch.run();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(0, 1, 0));
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(stock()).isEqualTo(97);
        PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(attempt.getTransactionKey()).isNull();
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        PaymentEvent event = paymentEventJpaRepository.findAll().getLast();
        assertThat(event.getSource()).isEqualTo(PaymentEvent.Source.BATCH);
        assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.IGNORED);
        assertThat(event.getReason()).isEqualTo(PaymentEvent.Reason.NO_TRANSACTION_YET);

        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(0, 1, 0));
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(stock()).isEqualTo(97);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void requestedAttemptLeftForThirtyMinutesKeepsReservationWithoutSending() {
        Long orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
        paymentTransactions.start(userId, orderId, "attempt-" + orderId, "TEST", "0000-0000-0000-0000");
        age(orderId, LONG_UNRESOLVED);

        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(0, 1, 0));
        PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(attempt.getTransactionKey()).isNull();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(stock()).isEqualTo(97);
        assertThat(PG.createCalls()).isZero();
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.requests().getLast().rawQuery()).startsWith("orderId=");
    }

    @Test
    void pendingAttemptIsNotClosedEvenAfterThirtyMinutes() {
        Long orderId = confirmedOrder();
        age(orderId, LONG_UNRESOLVED);

        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(0, 1, 0));
        assertThat(paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.PENDING);
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
    }

    @Test
    void longUnresolvedAttemptKeepsReservationWhenPgCannotBeAsked() {
        Long orderId = unknownOrderWithoutPgTransaction();
        age(orderId, LONG_UNRESOLVED);
        for (int i = 0; i < 3; i++) {
            PG.replyNext(503, "");
        }

        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(0, 1, 0));
        assertThat(paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(stock()).isEqualTo(97);
    }

    @Test
    void transactionFoundAfterThirtyMinutesIsApplied() {
        Long orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
        PG.dropNextCreateResponse();
        orderFacade.confirm(userId, orderId, new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        age(orderId, LONG_UNRESOLVED);

        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(1, 0, 0));
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(stock()).isEqualTo(97);
    }

    @Test
    void lateSuccessAfterEmptyLookupFinishesOrderOnceWithoutAnotherCreate() {
        Long orderId = unknownOrderWithoutPgTransaction();
        age(orderId, LONG_UNRESOLVED);
        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(0, 1, 0));
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(stock()).isEqualTo(97);
        String providerOrderId = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow().getProviderOrderId();
        // PG가 같은 주문 번호의 거래를 늦게 만든 상황을 재현한다. 애플리케이션의 POST 재전송이 아니다.
        String lateKey = paymentProvider.create(new PaymentRequest(providerOrderId, "TEST", "0000-0000-0000-0000", 3_000L))
            .transactions().getFirst().transactionKey();
        PG.changeStatus(lateKey, "SUCCESS");
        int createsBeforeRecovery = PG.createCalls();

        assertThat(orderFacade.handleCallback(providerOrderId)).isEqualTo(OrderFacade.CallbackResult.CHECKED);

        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(stock()).isEqualTo(97);
        PaymentEvent event = paymentEventJpaRepository.findAll().getLast();
        assertThat(event.getSource()).isEqualTo(PaymentEvent.Source.CALLBACK);
        assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.APPLIED);
        assertThat(event.getReason()).isNull();

        int lookupsAfterRecovery = PG.lookupCalls();
        assertThat(orderFacade.handleCallback(providerOrderId)).isEqualTo(OrderFacade.CallbackResult.ALREADY_FINISHED);
        assertThat(orderFacade.reconcile(userId, orderId).isFinished()).isTrue();
        assertThat(batch.run()).isEqualTo(new PaymentRecoveryBatch.Result(0, 0, 0));
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(stock()).isEqualTo(97);
        assertThat(PG.lookupCalls()).isEqualTo(lookupsAfterRecovery);
        assertThat(PG.createCalls()).isEqualTo(createsBeforeRecovery);
    }

    /** 생성 POST가 503을 받아 결과를 모르는 결제. 가짜 PG는 503일 때 거래를 만들지 않는다. */
    private Long unknownOrderWithoutPgTransaction() {
        PG.replyNext(503, "");
        Long orderId = confirmedOrder();
        assertThat(paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow().getStatus())
            .isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(PG.transactionCount()).isZero();
        return orderId;
    }

    private Long confirmedOrder() {
        return confirmedOrder(3);
    }

    private Long confirmedOrder(int quantity) {
        Long orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, quantity))).id();
        orderFacade.confirm(userId, orderId,
            new OrderFacade.PaymentCommand("attempt-" + orderId, "TEST", "0000-0000-0000-0000"));
        return orderId;
    }

    /** 결제 시도의 생성 시각만 과거로 옮긴다. 배치는 실제 시계 기준으로 10분이 지난 시도를 고른다. */
    private void age(Long orderId, Duration age) {
        transaction.executeWithoutResult(status -> entityManager
            .createNativeQuery("update payment_attempt set created_at = :createdAt where order_id = :orderId")
            .setParameter("createdAt", ZonedDateTime.now().minus(age))
            .setParameter("orderId", orderId)
            .executeUpdate());
    }

    private void assertOrder(Long orderId, OrderStatus status, ReservationStatus reservation) {
        OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getReservationStatus()).isEqualTo(reservation);
    }

    private int stock() {
        return productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
