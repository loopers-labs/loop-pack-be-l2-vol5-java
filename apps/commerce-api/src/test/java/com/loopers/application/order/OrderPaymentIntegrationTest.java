package com.loopers.application.order;

import com.loopers.application.point.PointFacade;
import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.payment.PaymentEventJpaRepository;
import com.loopers.infrastructure.payment.ResilientPaymentProvider;
import com.loopers.infrastructure.point.PointBalanceJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.payment.FakePaymentServer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** 주문 42 예시(재고 5, 수량 3, 3,000원)를 실제 MySQL과 가짜 PG HTTP 서버로 따라간다. */
@SpringBootTest
class OrderPaymentIntegrationTest {

    private static final FakePaymentServer PG = startPg();

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
        registry.add("commerce.payment.pg.read-timeout", () -> "500ms");
    }

    @Autowired
    private UserRegistrationService registration;
    @Autowired
    private OrderFacade orderFacade;
    @Autowired
    private AdminOrderFacade adminOrderFacade;
    @Autowired
    private OrderPaymentTransactions paymentTransactions;
    @Autowired
    private PaymentProvider paymentProvider;
    @Autowired
    private ResilientPaymentProvider resilientPaymentProvider;
    @Autowired
    private PointFacade pointFacade;
    @Autowired
    private BrandJpaRepository brandJpaRepository;
    @Autowired
    private ProductService productService;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired
    private PaymentEventJpaRepository paymentEventJpaRepository;
    @Autowired
    private PointBalanceJpaRepository pointBalanceJpaRepository;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private ProductModel product;

    @BeforeEach
    void setUp() {
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        user = registration.register();
        pointFacade.charge(user.getId(), 10_000L);
        BrandModel brand = brandJpaRepository.save(new BrandModel("나이키", null));
        product = productJpaRepository.save(new ProductModel(brand.getId(), "양말", 1_000L, 5));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void confirmReservesStockAndRecordsAcceptedPendingWithoutTouchingPoints() {
        Long orderId = draftOf(3);

        OrderPaymentInfo payment = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        assertThat(payment.paymentStatus()).isEqualTo("PENDING");
        assertThat(payment.isFinished()).isFalse();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(storedOrder(orderId).getPaymentMethod()).isEqualTo(PaymentMethod.PG);
        assertThat(stock()).isEqualTo(2);
        PaymentAttempt attempt = storedAttempt(orderId);
        assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(attempt.getTransactionKey()).isEqualTo("TEST-TX-1");
        assertThat(attempt.getProviderOrderId()).isEqualTo("LP-ORD-%010d".formatted(orderId));
        assertThat(attempt.getAmount()).isEqualTo(3_000L);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(points()).isEqualTo(10_000L);
        assertThat(events()).extracting(PaymentEvent::getSource, PaymentEvent::getResult)
            .containsExactly(tuple(PaymentEvent.Source.CREATE_RESPONSE, PaymentAttempt.Result.APPLIED));
    }

    @Test
    void reconcileAppliesVerifiedSuccessWithoutDeductingStockAgain() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        OrderPaymentInfo payment = orderFacade.reconcile(user.getId(), orderId);

        assertThat(payment.paymentStatus()).isEqualTo("SUCCESS");
        assertThat(payment.isFinished()).isTrue();
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED, 3_000L);
        assertThat(stock()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.requests().getLast().path()).isEqualTo("/api/v1/payments/TEST-TX-1");
        assertThat(points()).isEqualTo(10_000L);
    }

    @Test
    void lostCreateResponseStaysUnknownAndIsRecoveredByProviderOrderIdWithoutNewCreate() {
        Long orderId = draftOf(3);
        PG.dropNextCreateResponse();

        OrderPaymentInfo unknown = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        assertThat(unknown.paymentStatus()).isEqualTo("UNKNOWN");
        assertThat(unknown.checked()).isFalse();
        assertThat(storedAttempt(orderId).getTransactionKey()).isNull();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(stock()).isEqualTo(2);
        assertThat(PG.transactionCount()).isEqualTo(1);

        PG.changeStatus("TEST-TX-1", "SUCCESS");
        OrderPaymentInfo recovered = orderFacade.reconcile(user.getId(), orderId);

        assertThat(recovered.paymentStatus()).isEqualTo("SUCCESS");
        assertThat(recovered.checked()).isTrue();
        assertThat(storedAttempt(orderId).getTransactionKey()).isEqualTo("TEST-TX-1");
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED, 3_000L);
        assertThat(PG.requests().getLast().rawQuery()).isEqualTo("orderId=LP-ORD-%010d".formatted(orderId));
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void verifiedFailureReleasesStockOnceAndRepeatedFailureIsIgnored() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        PG.changeStatus("TEST-TX-1", "FAILED");

        orderFacade.reconcile(user.getId(), orderId);
        paymentTransactions.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"), PaymentEvent.Source.RECONCILE);

        assertOrder(orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED, 0L);
        assertThat(storedAttempt(orderId).getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(stock()).isEqualTo(5);
        assertThat(events()).extracting(PaymentEvent::getResult).containsExactly(
            PaymentAttempt.Result.APPLIED, PaymentAttempt.Result.APPLIED, PaymentAttempt.Result.IGNORED);
        assertThat(points()).isEqualTo(10_000L);
    }

    @Test
    void mismatchedCreateResponseIsRecordedAsConflictAndRecoveredByProviderOrderId() {
        Long orderId = draftOf(3);
        PG.replyNext(202, "{\"transactionKey\":\"TEST-TX-9\",\"orderId\":\"LP-ORD-%010d\","
            .formatted(orderId) + "\"amount\":9999,\"status\":\"PENDING\"}");

        OrderPaymentInfo payment = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        assertThat(payment.paymentStatus()).isEqualTo("REQUESTED");
        assertThat(storedAttempt(orderId).getTransactionKey()).isNull();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(stock()).isEqualTo(2);
        PaymentEvent event = events().getLast();
        assertThat(event.getSource()).isEqualTo(PaymentEvent.Source.CREATE_RESPONSE);
        assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.CONFLICT);
        assertThat(event.getReason()).isEqualTo(PaymentEvent.Reason.AMOUNT_MISMATCH);
        assertThat(event.getObservedTransactionKey()).isEqualTo("TEST-TX-9");
        assertThat(event.getObservedAmount()).isEqualTo(9_999L);

        orderFacade.reconcile(user.getId(), orderId);

        assertThat(PG.requests().getLast().rawQuery()).isEqualTo("orderId=LP-ORD-%010d".formatted(orderId));
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void verifiedFailureReleasesStockOfProductDeletedAfterReservation() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        productService.delete(product.getId());
        PG.changeStatus("TEST-TX-1", "FAILED");

        OrderPaymentInfo payment = orderFacade.reconcile(user.getId(), orderId);

        assertThat(payment.paymentStatus()).isEqualTo("FAILED");
        assertOrder(orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED, 0L);
        assertThat(stock()).isEqualTo(5);
        assertThat(productService.findActive(product.getId())).isEmpty();
    }

    @Test
    void verifiedSuccessConfirmsOrderOfProductDeletedAfterReservation() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        productService.delete(product.getId());
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        orderFacade.reconcile(user.getId(), orderId);

        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED, 3_000L);
        assertThat(stock()).isEqualTo(2);
    }

    @Test
    void provenRejectionBeforeCreateFailsPaymentAndReleasesStockWithoutTransaction() {
        Long orderId = draftOf(3);
        PG.replyNext(422, "{\"code\":\"TEST_PG_REJECTED_BEFORE_CREATE\",\"orderId\":\"LP-ORD-%010d\","
            .formatted(orderId) + "\"amount\":3000,\"transactionCreated\":false}");

        OrderPaymentInfo payment = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        assertThat(payment.paymentStatus()).isEqualTo("FAILED");
        assertThat(payment.isFinished()).isTrue();
        assertOrder(orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED, 0L);
        assertThat(storedAttempt(orderId).getTransactionKey()).isNull();
        assertThat(stock()).isEqualTo(5);
        assertThat(PG.transactionCount()).isZero();
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(points()).isEqualTo(10_000L);
        PaymentEvent event = events().getLast();
        assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.APPLIED);
        assertThat(event.getReason()).isEqualTo(PaymentEvent.Reason.REJECTED_BEFORE_CREATE);
        assertThat(event.getObservedTransactionKey()).isNull();

        orderFacade.reconcile(user.getId(), orderId);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(stock()).isEqualTo(5);
    }

    @Test
    void createThatCannotBorrowAConnectionFailsPaymentAndReleasesStockAtOnce() throws Exception {
        Long existing = draftOf(1);
        orderFacade.confirm(user.getId(), existing, command("attempt-existing"));
        Long orderId = draftOf(3);
        PG.delayEveryResponse(Duration.ofMillis(450));
        ExecutorService executor = Executors.newFixedThreadPool(5);
        OrderPaymentInfo payment;
        try {
            // 조회 5개가 연결 5개를 모두 잡은 동안 결제를 시작한다. 생성 POST는 연결 빌리기 대기(300ms)에서 끝나 PG에 닿지 않는다.
            for (int i = 0; i < 5; i++) {
                executor.submit(() -> resilientPaymentProvider.findByTransactionKey("TEST-TX-1"));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (PG.lookupCalls() < 5 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            payment = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        } finally {
            executor.shutdownNow();
            PG.delayEveryResponse(null);
        }

        // 보내지 못한 것이 확실하므로 거래를 기다리지 않고 실패로 닫는다.
        assertThat(payment.paymentStatus()).isEqualTo("FAILED");
        assertThat(payment.isFinished()).isTrue();
        assertThat(payment.checked()).isTrue();
        assertOrder(orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED, 0L);
        assertThat(storedAttempt(orderId).getTransactionKey()).isNull();
        assertThat(stock()).isEqualTo(4);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
        PaymentEvent event = events().getLast();
        assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.APPLIED);
        assertThat(event.getReason()).isEqualTo(PaymentEvent.Reason.NOT_SENT);
    }

    @Test
    void finishedPaymentIsReturnedWithoutAnotherLookup() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        orderFacade.reconcile(user.getId(), orderId);

        OrderPaymentInfo again = orderFacade.reconcile(user.getId(), orderId);

        assertThat(again.paymentStatus()).isEqualTo("SUCCESS");
        assertThat(PG.lookupCalls()).isEqualTo(1);
    }

    @Test
    void emptyLookupKeepsReservationAndDoesNotCreateAgain() {
        Long orderId = draftOf(3);
        PG.dropNextCreateResponse();
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        PG.reset();
        OrderPaymentInfo noTransaction = orderFacade.reconcile(user.getId(), orderId);

        assertThat(noTransaction.paymentStatus()).isEqualTo("UNKNOWN");
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(stock()).isEqualTo(2);
        assertThat(events().getLast().getReason()).isEqualTo(PaymentEvent.Reason.NO_TRANSACTION_YET);
        assertThat(PG.createCalls()).isZero();
    }

    @Test
    void unreadableLookupReturnsStoredStateWithoutRecording() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        int eventsBefore = events().size();
        for (int i = 0; i < 3; i++) {
            PG.replyNext(503, "");
        }

        OrderPaymentInfo payment = orderFacade.reconcile(user.getId(), orderId);

        assertThat(payment.paymentStatus()).isEqualTo("PENDING");
        assertThat(payment.checked()).isFalse();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(events()).hasSize(eventsBefore);
        assertThat(PG.lookupCalls()).isEqualTo(3);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void openLookupCircuitReturnsStoredStateWithoutAskingPg() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        resilientPaymentProvider.lookupCircuitBreaker().transitionToOpenState();
        int eventsBefore = events().size();

        OrderPaymentInfo payment = orderFacade.reconcile(user.getId(), orderId);

        assertThat(payment.paymentStatus()).isEqualTo("PENDING");
        assertThat(payment.checked()).isFalse();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(stock()).isEqualTo(2);
        assertThat(events()).hasSize(eventsBefore);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void oneFailedLookupIsRecoveredWithinTheSameReconcile() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        PG.replyNext(503, "");

        OrderPaymentInfo payment = orderFacade.reconcile(user.getId(), orderId);

        assertThat(payment.paymentStatus()).isEqualTo("SUCCESS");
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED, 3_000L);
        assertThat(stock()).isEqualTo(2);
        assertThat(PG.lookupCalls()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
    }

    @Test
    void twoTransactionsForOneOrderAreRecordedAsConflictWithoutChoosingOne() {
        Long orderId = draftOf(3);
        PG.dropNextCreateResponse();
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        paymentProvider.create(new PaymentRequest(
            "LP-ORD-%010d".formatted(orderId), "TEST", "0000-0000-0000-0000", 3_000L));
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        OrderPaymentInfo payment = orderFacade.reconcile(user.getId(), orderId);

        assertThat(payment.paymentStatus()).isEqualTo("UNKNOWN");
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(stock()).isEqualTo(2);
        PaymentEvent last = events().getLast();
        assertThat(last.getResult()).isEqualTo(PaymentAttempt.Result.CONFLICT);
        assertThat(last.getReason()).isEqualTo(PaymentEvent.Reason.MULTIPLE_TRANSACTIONS);
    }

    @Test
    void oppositeFinalResultIsRecordedAsConflictAndDoesNotReleaseSoldStock() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        orderFacade.reconcile(user.getId(), orderId);
        PG.changeStatus("TEST-TX-1", "FAILED");

        paymentTransactions.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"), PaymentEvent.Source.RECONCILE);

        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED, 3_000L);
        assertThat(stock()).isEqualTo(2);
        PaymentEvent last = events().getLast();
        assertThat(last.getResult()).isEqualTo(PaymentAttempt.Result.CONFLICT);
        assertThat(last.getReason()).isEqualTo(PaymentEvent.Reason.OPPOSITE_FINAL_RESULT);
        assertThat(last.getObservedStatus()).hasToString("FAILED");
    }

    @ParameterizedTest
    @CsvSource({
        "true, 3000, TEST-TX-1, ORDER_MISMATCH",
        "false, 9999, TEST-TX-1, AMOUNT_MISMATCH",
        "false, 3000, TEST-TX-9, OTHER_TRANSACTION_KEY"
    })
    void mismatchedLookupIsRecordedWithoutChangingPaymentOrStock(
        boolean otherOrder, long observedAmount, String observedKey, PaymentEvent.Reason reason
    ) {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        String observedOrder = otherOrder ? "OTHER-ORDER" : "LP-ORD-%010d".formatted(orderId);
        PG.replyNext(200, "{\"transactionKey\":\"%s\",\"orderId\":\"%s\",\"amount\":%d,\"status\":\"SUCCESS\"}"
            .formatted(observedKey, observedOrder, observedAmount));

        OrderPaymentInfo payment = orderFacade.reconcile(user.getId(), orderId);

        assertThat(payment.paymentStatus()).isEqualTo("PENDING");
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(storedAttempt(orderId).getTransactionKey()).isEqualTo("TEST-TX-1");
        assertThat(stock()).isEqualTo(2);
        PaymentEvent event = events().getLast();
        assertThat(event.getSource()).isEqualTo(PaymentEvent.Source.RECONCILE);
        assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.CONFLICT);
        assertThat(event.getReason()).isEqualTo(reason);
        assertThat(event.getObservedTransactionKey()).isEqualTo(observedKey);
        assertThat(event.getObservedAmount()).isEqualTo(observedAmount);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(points()).isEqualTo(10_000L);
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCESS, CONFIRMED, CONSUMED, 10, 3000",
        "FAILED, PAYMENT_FAILED, RELEASED, 13, 0"
    })
    void paymentResultPreservesStockChangedAfterReservation(
        String paymentStatus, OrderStatus orderStatus, ReservationStatus reservation, int expectedStock, long paidAmount
    ) {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        productService.changeStock(product.getId(), 10);
        PG.changeStatus("TEST-TX-1", paymentStatus);

        orderFacade.reconcile(user.getId(), orderId);
        orderFacade.reconcile(user.getId(), orderId);

        assertOrder(orderId, orderStatus, reservation, paidAmount);
        assertThat(stock()).isEqualTo(expectedStock);
        assertThat(storedAttempt(orderId).getStatus().name()).isEqualTo(paymentStatus);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(points()).isEqualTo(10_000L);
    }

    @Test
    void insufficientStockRollsBackWithoutCallingPg() {
        Long orderId = draftOf(6);

        assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId, command("attempt-abc")))
            .isInstanceOf(CoreException.class);

        assertThat(storedOrder(orderId).getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(storedOrder(orderId).getPaymentMethod()).isNull();
        assertThat(paymentAttemptJpaRepository.findByOrderId(orderId)).isEmpty();
        assertThat(stock()).isEqualTo(5);
        assertThat(PG.createCalls()).isZero();
    }

    @Test
    void insufficientLaterItemRollsBackEarlierReservationWithoutCallingPg() {
        ProductModel scarce = productJpaRepository.save(new ProductModel(product.getBrandId(), "모자", 2_000L, 1));
        Long orderId = orderFacade.create(user.getId(), List.of(
            new OrderFacade.OrderLine(product.getId(), 3), new OrderFacade.OrderLine(scarce.getId(), 2)
        )).id();

        assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId, command("attempt-abc")))
            .isInstanceOf(CoreException.class);

        assertOrder(orderId, OrderStatus.DRAFT, null, 0L);
        assertThat(storedOrder(orderId).getPaymentMethod()).isNull();
        assertThat(stock()).isEqualTo(5);
        assertThat(productJpaRepository.findById(scarce.getId()).orElseThrow().getStockQuantity()).isEqualTo(1);
        assertThat(paymentAttemptJpaRepository.findByOrderId(orderId)).isEmpty();
        assertThat(events()).isEmpty();
        assertThat(PG.createCalls()).isZero();
    }

    @Test
    void sameKeyReturnsStoredStateAndOtherKeysAreRejectedWithoutAnotherCreate() {
        Long orderId = draftOf(3);
        Long otherOrderId = draftOf(1);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        OrderPaymentInfo repeated = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        assertThat(repeated.paymentStatus()).isEqualTo("PENDING");
        assertThat(repeated.checked()).isFalse();
        assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId, command("attempt-xyz")))
            .isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> orderFacade.confirm(user.getId(), otherOrderId, command("attempt-abc")))
            .isInstanceOf(CoreException.class);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(stock()).isEqualTo(2);
        assertThat(storedOrder(otherOrderId).getStatus()).isEqualTo(OrderStatus.DRAFT);
    }

    @Test
    void sameKeyAfterLostCreateResponseReturnsUnknownWithoutCheckingAgain() {
        Long orderId = draftOf(3);
        PG.dropNextCreateResponse();
        OrderPaymentInfo first = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        assertThat(first.checked()).isFalse();
        int eventsBefore = events().size();
        PG.changeStatus("TEST-TX-1", "SUCCESS");

        OrderPaymentInfo repeated = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        assertThat(repeated.paymentStatus()).isEqualTo("UNKNOWN");
        assertThat(repeated.checked()).isFalse();
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(stock()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(PG.transactionCount()).isEqualTo(1);
        assertThat(events()).hasSize(eventsBefore);
    }

    @Test
    void sameKeyAfterCompletionReturnsTheStoredFinalResultWithoutCheckingAgain() {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        orderFacade.reconcile(user.getId(), orderId);
        int eventsBefore = events().size();

        OrderPaymentInfo repeated = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));

        assertThat(repeated.paymentStatus()).isEqualTo("SUCCESS");
        assertThat(repeated.checked()).isTrue();
        assertOrder(orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED, 3_000L);
        assertThat(stock()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(events()).hasSize(eventsBefore);
    }

    @ParameterizedTest
    @CsvSource({
        "REQUESTED, TEST, 1111-1111-1111-1111",
        "REQUESTED, OTHER, 0000-0000-0000-0000",
        "PENDING, TEST, 1111-1111-1111-1111",
        "PENDING, OTHER, 0000-0000-0000-0000",
        "UNKNOWN, TEST, 1111-1111-1111-1111",
        "UNKNOWN, OTHER, 0000-0000-0000-0000",
        "SUCCESS, TEST, 1111-1111-1111-1111",
        "SUCCESS, OTHER, 0000-0000-0000-0000",
        "FAILED, TEST, 1111-1111-1111-1111",
        "FAILED, OTHER, 0000-0000-0000-0000"
    })
    void sameKeyWithDifferentCardConflictsWithoutChangingPaymentOrStock(
        String paymentStatus, String cardType, String cardNo
    ) {
        Long orderId = draftOf(3);
        if (paymentStatus.equals("REQUESTED")) {
            paymentTransactions.start(user.getId(), orderId, "attempt-abc", "TEST", "0000-0000-0000-0000");
        } else {
            if (paymentStatus.equals("UNKNOWN")) {
                PG.dropNextCreateResponse();
            }
            orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
            if (paymentStatus.equals("SUCCESS") || paymentStatus.equals("FAILED")) {
                PG.changeStatus("TEST-TX-1", paymentStatus);
                orderFacade.reconcile(user.getId(), orderId);
            }
        }
        OrderModel orderBefore = storedOrder(orderId);
        int stockBefore = stock();
        int eventsBefore = events().size();
        int createsBefore = PG.createCalls();
        int lookupsBefore = PG.lookupCalls();
        int transactionsBefore = PG.transactionCount();

        OrderPaymentInfo repeated = orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        assertThat(repeated.paymentStatus()).isEqualTo(paymentStatus);

        assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId,
            new OrderFacade.PaymentCommand("attempt-abc", cardType, cardNo)))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));

        assertThat(storedAttempt(orderId).getStatus().name()).isEqualTo(paymentStatus);
        assertOrder(orderId, orderBefore.getStatus(), orderBefore.getReservationStatus(), orderBefore.getPaidAmount());
        assertThat(stock()).isEqualTo(stockBefore);
        assertThat(PG.createCalls()).isEqualTo(createsBefore);
        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore);
        assertThat(PG.transactionCount()).isEqualTo(transactionsBefore);
        assertThat(events()).hasSize(eventsBefore);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void differentCardCannotTriggerApplicationOfTheStoredFinalResult(String paymentStatus) {
        Long orderId = draftOf(3);
        orderFacade.confirm(user.getId(), orderId, command("attempt-abc"));
        PG.changeStatus("TEST-TX-1", paymentStatus);
        paymentTransactions.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"),
            PaymentEvent.Source.RECONCILE);
        int eventsBefore = events().size();

        assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId,
            new OrderFacade.PaymentCommand("attempt-abc", "TEST", "1111-1111-1111-1111")))
            .isInstanceOfSatisfying(CoreException.class,
                exception -> assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT));

        assertThat(storedAttempt(orderId).getStatus().name()).isEqualTo(paymentStatus);
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(stock()).isEqualTo(2);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
        assertThat(events()).hasSize(eventsBefore);
    }

    @Test
    void orderLookupsShowPaymentStatusAndAdminAlsoSeesPgIdentifiers() {
        Long pendingId = draftOf(1);
        Long unknownId = draftOf(1);
        Long draftId = draftOf(1);
        orderFacade.confirm(user.getId(), pendingId, command("attempt-pending"));
        PG.dropNextCreateResponse();
        orderFacade.confirm(user.getId(), unknownId, command("attempt-unknown"));

        assertThat(orderFacade.getMyOrder(user.getId(), pendingId).payment().status()).isEqualTo("PENDING");
        assertThat(orderFacade.getMyOrder(user.getId(), draftId).payment()).isNull();
        assertThat(orderFacade.getMyOrders(user.getId()))
            .extracting(OrderInfo::id, order -> order.payment() == null ? null : order.payment().status())
            .containsExactlyInAnyOrder(tuple(pendingId, "PENDING"), tuple(unknownId, "UNKNOWN"), tuple(draftId, null));

        OrderInfo.PaymentSummary admin = adminOrderFacade.get(pendingId).payment();
        assertThat(admin.providerOrderId()).isEqualTo("LP-ORD-%010d".formatted(pendingId));
        assertThat(admin.transactionKey()).isEqualTo("TEST-TX-1");
        assertThat(adminOrderFacade.getAll()).extracting(OrderInfo::id).contains(pendingId, unknownId, draftId);
        assertThat(PG.lookupCalls()).isZero();
    }

    @Test
    void pastPointOrderCannotStartPgPayment() {
        Long orderId = draftOf(1);
        OrderModel pointOrder = storedOrder(orderId);
        pointOrder.confirm();
        orderJpaRepository.save(pointOrder);

        assertThatThrownBy(() -> orderFacade.confirm(user.getId(), orderId, command("attempt-abc")))
            .isInstanceOf(CoreException.class);

        assertThat(storedOrder(orderId).getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
        assertThat(storedOrder(orderId).getReservationStatus()).isNull();
        assertThat(PG.createCalls()).isZero();
    }

    @Test
    void invalidCardInputIsRejectedBeforeReservingStock() {
        Long orderId = draftOf(3);

        assertThatThrownBy(() -> orderFacade.confirm(
            user.getId(), orderId, new OrderFacade.PaymentCommand("attempt-abc", "TEST", " ")))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(storedOrder(orderId).getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(stock()).isEqualTo(5);
        assertThat(PG.createCalls()).isZero();
    }

    @Test
    void pastPointOrderKeepsItsPaymentFactsItemsAndOwnership() {
        Long orderId = draftOf(2);
        OrderModel pointOrder = storedOrder(orderId);
        pointOrder.confirm();
        orderJpaRepository.save(pointOrder);
        Long otherUserId = registration.register().getId();

        OrderInfo mine = orderFacade.getMyOrder(user.getId(), orderId);

        assertThat(mine.status()).isEqualTo("CONFIRMED");
        assertThat(mine.paymentMethod()).isEqualTo("POINT");
        assertThat(mine.totalAmount()).isEqualTo(2_000L);
        assertThat(mine.paidAmount()).isEqualTo(2_000L);
        assertThat(mine.reservationStatus()).isNull();
        assertThat(mine.payment()).isNull();
        assertThat(mine.items()).extracting(OrderInfo.OrderItemInfo::productId, OrderInfo.OrderItemInfo::unitPrice,
            OrderInfo.OrderItemInfo::quantity).containsExactly(tuple(product.getId(), 1_000L, 2));
        assertThat(orderFacade.getMyOrders(user.getId())).extracting(OrderInfo::id, OrderInfo::paymentMethod)
            .containsExactly(tuple(orderId, "POINT"));
        assertThat(adminOrderFacade.get(orderId).paymentMethod()).isEqualTo("POINT");
        assertThatThrownBy(() -> orderFacade.getMyOrder(otherUserId, orderId)).isInstanceOf(CoreException.class);
        assertThatThrownBy(() -> orderFacade.reconcile(user.getId(), orderId)).isInstanceOf(CoreException.class);
        assertThat(orderFacade.getMyOrders(otherUserId)).isEmpty();
        assertThat(PG.createCalls()).isZero();
        assertThat(PG.lookupCalls()).isZero();
        assertThat(points()).isEqualTo(10_000L);
    }

    @Test
    void sameKeyConfirmedConcurrentlySendsCreateOnce() throws Exception {
        Long orderId = draftOf(3);
        List<Callable<OrderPaymentInfo>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> orderFacade.confirm(user.getId(), orderId, command("attempt-abc")));
        }

        List<OrderPaymentInfo> results = runTogether(calls);

        assertThat(results).hasSize(8);
        assertThat(results).filteredOn(OrderPaymentInfo::checked).hasSize(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.transactionCount()).isEqualTo(1);
        assertThat(paymentAttemptJpaRepository.count()).isEqualTo(1);
        assertOrder(orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED, 0L);
        assertThat(stock()).isEqualTo(2);
    }

    @Test
    void adminStockChangeRacingWithFailureReleaseKeepsBothChanges() throws Exception {
        for (int round = 0; round < 5; round++) {
            Long orderId = draftOf(3);
            orderFacade.confirm(user.getId(), orderId, command("attempt-" + round));
            PG.changeStatus("TEST-TX-" + (round + 1), "FAILED");

            runTogether(List.<Callable<Object>>of(
                () -> productService.changeStock(product.getId(), 10),
                () -> orderFacade.reconcile(user.getId(), orderId)));

            // 관리자 변경이 먼저면 10에 반환 3을 더한 13, 반환이 먼저면 관리자가 정한 10. 어느 쪽 변경도 사라지지 않는다.
            assertThat(stock()).isIn(10, 13);
            assertOrder(orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED, 0L);
            productService.changeStock(product.getId(), 5);
        }
    }

    private <T> List<T> runTogether(List<Callable<T>> calls) throws Exception {
        var executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> call : calls) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Long draftOf(int quantity) {
        return orderFacade.create(user.getId(), List.of(new OrderFacade.OrderLine(product.getId(), quantity))).id();
    }

    private static OrderFacade.PaymentCommand command(String attemptKey) {
        return new OrderFacade.PaymentCommand(attemptKey, "TEST", "0000-0000-0000-0000");
    }

    private void assertOrder(Long orderId, OrderStatus status, ReservationStatus reservation, long paidAmount) {
        OrderModel order = storedOrder(orderId);
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getReservationStatus()).isEqualTo(reservation);
        assertThat(order.getPaidAmount()).isEqualTo(paidAmount);
    }

    private OrderModel storedOrder(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private PaymentAttempt storedAttempt(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private List<PaymentEvent> events() {
        return paymentEventJpaRepository.findAll().stream()
            .sorted(Comparator.comparing(PaymentEvent::getId))
            .toList();
    }

    private int stock() {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStockQuantity();
    }

    private long points() {
        return pointBalanceJpaRepository.findByUserId(user.getId()).orElseThrow().getBalance();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
