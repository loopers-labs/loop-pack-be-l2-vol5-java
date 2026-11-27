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
import com.loopers.domain.payment.PaymentTransaction;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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
    @MockitoSpyBean private OrderPaymentTransactions paymentTransactions;
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
    void recoversThreeLostResponsesAndLeavesOneUnsentOrderForReview() throws IOException {
        PG.settleCreatedAs("SUCCESS");
        List<Long> lostOrders = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            PG.dropNextCreateResponse();
            lostOrders.add(confirmedOrder(1));
        }
        Long unsent = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 1))).id();
        paymentTransactions.start(userId, unsent, "attempt-" + unsent, "TEST", "0000-0000-0000-0000");

        Map<String, String> keysBefore = new LinkedHashMap<>();
        PG.transactions().forEach(tx -> {
            assertThat(tx.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(tx.path("amount").asLong()).isEqualTo(1_000L);
            keysBefore.put(tx.path("orderId").asText(), tx.path("transactionKey").asText());
        });
        var createsBefore = PG.createCallsByOrder();
        assertThat(keysBefore).hasSize(3);
        assertThat(createsBefore.keySet()).containsExactlyInAnyOrderElementsOf(keysBefore.keySet());
        assertThat(createsBefore.values()).containsOnly(1);
        for (Long id : lostOrders) {
            PaymentAttempt attempt = observeBatchOrder("before", id, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
            assertThat(attempt.getTransactionKey()).isNull();
            assertThat(keysBefore).containsKey(attempt.getProviderOrderId());
            age(id, OLD);
        }
        PaymentAttempt requested = observeBatchOrder("before", unsent, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(requested.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(keysBefore).doesNotContainKey(requested.getProviderOrderId());
        age(unsent, OLD);
        long unknownBefore = paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN);
        assertThat(unknownBefore).isEqualTo(3);
        assertThat(PG.lookupCalls()).isZero();
        assertThat(stock()).isEqualTo(96);

        long started = System.nanoTime();
        PaymentRecoveryBatch.Result result = batch.run();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(result).isEqualTo(new PaymentRecoveryBatch.Result(3, 1, 0));
        for (Long id : lostOrders) {
            PaymentAttempt attempt = observeBatchOrder("after", id, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
            assertThat(attempt.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
            assertThat(attempt.getTransactionKey()).isEqualTo(keysBefore.get(attempt.getProviderOrderId()));
        }
        PaymentAttempt remaining = observeBatchOrder("after", unsent, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(remaining.getStatus()).isEqualTo(PaymentAttemptStatus.REQUESTED);
        assertThat(remaining.getTransactionKey()).isNull();
        assertThat(paymentEventJpaRepository.findAll())
            .filteredOn(e -> e.getAttemptId().equals(remaining.getId()) && e.getSource() == PaymentEvent.Source.BATCH)
            .singleElement().satisfies(event -> {
                assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.IGNORED);
                assertThat(event.getReason()).isEqualTo(PaymentEvent.Reason.NO_TRANSACTION_YET);
                System.out.printf("[batch-review] orderId=%d source=%s reason=%s%n", unsent, event.getSource(), event.getReason());
            });
        long unknownAfter = paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN);
        assertThat(unknownAfter).isZero();
        assertThat(stock()).isEqualTo(96);
        assertThat(PG.lookupCalls()).isEqualTo(4);
        assertThat(PG.createCallsByOrder()).isEqualTo(createsBefore);
        System.out.printf("[batch-result] %s dbUnknown=%d->%d pgCreates=%s pgLookups=%d stock=%d elapsedMs=%d%n",
            result, unknownBefore, unknownAfter, createsBefore, PG.lookupCalls(), stock(), elapsedMs);
    }

    /**
     * 똑같이 미완료로 세어지는 두 주문의 다음 조치가 다른지 본다. A는 PG가 아직 처리 중(PENDING)이라 기다리고,
     * B는 주문 번호는 같지만 금액이 다른 성공 거래라 자동 적용을 멈추고 사람이 본다. A만 PG에서 성공으로 바뀐 뒤
     * 두 번째 배치가 A를 같은 거래 키로 확정하고 B는 그대로 두는지 확인한다.
     */
    @Test
    void pendingOrderWaitsAndAmountMismatchStaysForReview() throws IOException {
        PG.dropNextCreateResponse();
        Long pendingOrder = confirmedOrder(1);
        PG.dropNextCreateResponse();
        Long mismatchedOrder = confirmedOrder(1);
        PG.changeStatus("TEST-TX-2", "SUCCESS");
        PG.changeAmount("TEST-TX-2", 2_000L);
        age(pendingOrder, OLD);
        age(mismatchedOrder, OLD);
        var createsBefore = PG.createCallsByOrder();
        assertThat(createsBefore.values()).containsOnly(1);
        observeBatchOrder("before", pendingOrder, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        observeBatchOrder("before", mismatchedOrder, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        long unknownBefore = paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN);
        assertThat(unknownBefore).isEqualTo(2);

        PaymentRecoveryBatch.Result first = batch.run();

        PaymentAttempt waiting = observeBatchOrder("first", pendingOrder, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        PaymentAttempt conflicted = observeBatchOrder("first", mismatchedOrder, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        long unknownAfterFirst = paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN);
        printBatchEvents("first", waiting);
        printBatchEvents("first", conflicted);
        System.out.printf("[batch-result] round=first %s dbUnknown=%d->%d pgLookups=%d stock=%d%n",
            first, unknownBefore, unknownAfterFirst, PG.lookupCalls(), stock());
        assertThat(first).isEqualTo(new PaymentRecoveryBatch.Result(0, 2, 0));
        assertThat(waiting.getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(waiting.getTransactionKey()).isEqualTo("TEST-TX-1");
        assertThat(conflicted.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(conflicted.getTransactionKey()).isNull();
        assertThat(lastBatchEvent(waiting)).satisfies(event -> {
            assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.APPLIED);
            assertThat(event.getObservedStatus()).isEqualTo(PaymentTransaction.Status.PENDING);
        });
        assertThat(lastBatchEvent(conflicted)).satisfies(event -> {
            assertThat(event.getResult()).isEqualTo(PaymentAttempt.Result.CONFLICT);
            assertThat(event.getReason()).isEqualTo(PaymentEvent.Reason.AMOUNT_MISMATCH);
            assertThat(event.getObservedAmount()).isEqualTo(2_000L);
        });
        assertThat(unknownAfterFirst).isEqualTo(1);

        PG.changeStatus("TEST-TX-1", "SUCCESS");
        PaymentRecoveryBatch.Result second = batch.run();

        PaymentAttempt confirmed = observeBatchOrder("second", pendingOrder, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        PaymentAttempt stillConflicted = observeBatchOrder("second", mismatchedOrder, OrderStatus.PAYMENT_PENDING,
            ReservationStatus.RESERVED);
        printBatchEvents("second", confirmed);
        printBatchEvents("second", stillConflicted);
        System.out.printf("[batch-result] round=second %s dbUnknown=%d pgCreates=%s pgLookups=%d stock=%d%n",
            second, paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN), PG.createCallsByOrder(),
            PG.lookupCalls(), stock());
        assertThat(second).isEqualTo(new PaymentRecoveryBatch.Result(1, 1, 0));
        assertThat(confirmed.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(confirmed.getTransactionKey()).isEqualTo("TEST-TX-1");
        assertThat(stillConflicted.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(lastBatchEvent(stillConflicted).getReason()).isEqualTo(PaymentEvent.Reason.AMOUNT_MISMATCH);
        assertThat(PG.createCallsByOrder()).isEqualTo(createsBefore);
        assertThat(stock()).isEqualTo(98);
    }

    /** 생성 응답을 잃어 거래 키가 없는 주문도 PG callback이 오면 주문 번호 조회로 끝난다. 사람이나 배치가 개입하지 않는다. */
    @Test
    void callbackFinishesLostResponseWithoutHumanOrBatch() throws IOException {
        PG.settleCreatedAs("SUCCESS");
        PG.dropNextCreateResponse();
        Long orderId = confirmedOrder(1);
        PaymentAttempt before = observeBatchOrder("before-callback", orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(before.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(before.getTransactionKey()).isNull();
        var createsBefore = PG.createCallsByOrder();
        int lookupsBefore = PG.lookupCalls();

        OrderFacade.CallbackResult callback = orderFacade.handleCallback(before.getProviderOrderId());

        PaymentAttempt after = observeBatchOrder("after-callback", orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        printBatchEvents("callback", after);
        System.out.printf("[callback-result] orderId=%d callback=%s pgCreates=%s pgLookups=%d->%d lastLookupQuery=%s stock=%d%n",
            orderId, callback, PG.createCallsByOrder(), lookupsBefore, PG.lookupCalls(), PG.requests().getLast().rawQuery(), stock());
        assertThat(callback).isEqualTo(OrderFacade.CallbackResult.CHECKED);
        assertThat(after.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(after.getTransactionKey()).isEqualTo(PG.transactions().getFirst().path("transactionKey").asText());
        assertThat(PG.requests().getLast().rawQuery()).isEqualTo("orderId=" + before.getProviderOrderId());
        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore + 1);
        assertThat(PG.createCallsByOrder()).isEqualTo(createsBefore);
        assertThat(paymentEventJpaRepository.findAll()).extracting(PaymentEvent::getSource)
            .doesNotContain(PaymentEvent.Source.BATCH, PaymentEvent.Source.RECONCILE);
        assertThat(stock()).isEqualTo(99);
    }

    /**
     * 복구 도중 PG 조회가 계속 실패하면 reconcile은 결과를 바꾸지 않고 보류를 남긴다. PG가 회복된 뒤 같은 reconcile을
     * 다시 호출하면 새 결제 없이 끝난다(runbook 재진입).
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void reconcileDuringLookupOutageKeepsStateAndSucceedsAfterRecovery(CapturedOutput output) throws IOException {
        PG.settleCreatedAs("SUCCESS");
        PG.dropNextCreateResponse();
        Long orderId = confirmedOrder(1);
        var createsBefore = PG.createCallsByOrder();
        for (int i = 0; i < 3; i++) {
            PG.replyNext(503, "");
        }

        OrderPaymentInfo deferred = orderFacade.reconcile(userId, orderId);

        PaymentAttempt kept = observeBatchOrder("during-outage", orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        int lookupsDuringOutage = PG.lookupCalls();
        System.out.printf("[reentry-result] round=outage orderId=%d checked=%s attempt=%s key=%s pgLookups=%d stock=%d%n",
            orderId, deferred.checked(), kept.getStatus(), kept.getTransactionKey(), lookupsDuringOutage, stock());
        assertThat(deferred.checked()).isFalse();
        assertThat(kept.getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        assertThat(kept.getTransactionKey()).isNull();
        assertThat(paymentEventJpaRepository.findAll()).extracting(PaymentEvent::getSource)
            .doesNotContain(PaymentEvent.Source.RECONCILE);
        assertThat(output).contains("결제 결과 확인 보류: orderId=" + orderId + " source=RECONCILE cause=TEMPORARY_FAILURE");

        OrderPaymentInfo recovered = orderFacade.reconcile(userId, orderId);

        PaymentAttempt finished = observeBatchOrder("after-recovery", orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        printBatchEvents("reentry", finished);
        System.out.printf("[reentry-result] round=recovered orderId=%d checked=%s attempt=%s key=%s pgCreates=%s pgLookups=%d stock=%d%n",
            orderId, recovered.checked(), finished.getStatus(), finished.getTransactionKey(), PG.createCallsByOrder(),
            PG.lookupCalls(), stock());
        assertThat(recovered.checked()).isTrue();
        assertThat(finished.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(PG.createCallsByOrder()).isEqualTo(createsBefore);
        assertThat(stock()).isEqualTo(99);
    }

    /**
     * FAILED로 끝난 주문의 PG 거래가 나중에 SUCCESS로 바뀌어도, 끝난 주문은 callback·reconcile·배치가 다시 조회하지 않는다.
     * 충돌 로그·결제 이력·UNKNOWN 집계 어디에도 남지 않는 것을 확인한다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void finishedFailedOrderIsNotRecheckedWhenPgLaterSaysSuccess(CapturedOutput output) throws IOException {
        PG.settleCreatedAs("FAILED");
        Long orderId = confirmedOrder(1);
        PaymentAttempt pending = observeBatchOrder("after-create", orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(orderFacade.handleCallback(pending.getProviderOrderId())).isEqualTo(OrderFacade.CallbackResult.CHECKED);
        PaymentAttempt failed = observeBatchOrder("after-failed", orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
        assertThat(failed.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);

        PG.changeStatus(failed.getTransactionKey(), "SUCCESS");
        int lookupsBefore = PG.lookupCalls();
        long eventsBefore = paymentEventJpaRepository.count();
        age(orderId, OLD);

        OrderFacade.CallbackResult callback = orderFacade.handleCallback(failed.getProviderOrderId());
        OrderPaymentInfo reconciled = orderFacade.reconcile(userId, orderId);
        PaymentRecoveryBatch.Result batchResult = batch.run();

        PaymentAttempt after = observeBatchOrder("after-pg-changed", orderId, OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
        printBatchEvents("opposite", after);
        System.out.printf("[opposite-result] orderId=%d pg=%s callback=%s reconcileFinished=%s batch=%s pgLookups=%d->%d events=%d->%d"
                + " unknownCount=%d requestedCount=%d stock=%d conflictLog=%s%n",
            orderId, PG.transactions().getFirst().path("status").asText(), callback, reconciled.isFinished(), batchResult,
            lookupsBefore, PG.lookupCalls(), eventsBefore, paymentEventJpaRepository.count(),
            paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN),
            paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.REQUESTED), stock(), output.getAll().contains("PG 결과 충돌"));
        assertThat(callback).isEqualTo(OrderFacade.CallbackResult.ALREADY_FINISHED);
        assertThat(reconciled.isFinished()).isTrue();
        assertThat(batchResult).isEqualTo(new PaymentRecoveryBatch.Result(0, 0, 0));
        assertThat(PG.lookupCalls()).isEqualTo(lookupsBefore);
        assertThat(paymentEventJpaRepository.count()).isEqualTo(eventsBefore);
        assertThat(after.getStatus()).isEqualTo(PaymentAttemptStatus.FAILED);
        assertThat(output.getAll()).doesNotContain("PG 결과 충돌");
    }

    /**
     * 주문 반영(TX C)만 실패하면 결제 시도는 SUCCESS, 주문은 대기로 남는다. UNKNOWN·REQUESTED 집계에 잡히지 않고 ERROR 로그만 남는다.
     * 같은 reconcile을 다시 호출하면 PG 조회 없이 TX C만 다시 실행한다.
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void orderApplyFailureLeavesSuccessAttemptOutsideCountsAndRetriesWithoutLookup(CapturedOutput output) throws IOException {
        PG.settleCreatedAs("SUCCESS");
        PG.dropNextCreateResponse();
        Long orderId = confirmedOrder(1);
        var createsBefore = PG.createCallsByOrder();
        doThrow(new IllegalStateException("주문 반영 실패 주입")).doCallRealMethod()
            .when(paymentTransactions).applyRecordedResult(orderId);

        assertThatThrownBy(() -> orderFacade.reconcile(userId, orderId)).isInstanceOf(IllegalStateException.class);

        PaymentAttempt stuck = observeBatchOrder("after-tx-c-failure", orderId, OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        int lookupsAfterFailure = PG.lookupCalls();
        System.out.printf("[tx-c-result] round=failed orderId=%d attempt=%s key=%s unknownCount=%d requestedCount=%d pgLookups=%d stock=%d%n",
            orderId, stuck.getStatus(), stuck.getTransactionKey(),
            paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN),
            paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.REQUESTED), lookupsAfterFailure, stock());
        assertThat(stuck.getStatus()).isEqualTo(PaymentAttemptStatus.SUCCESS);
        assertThat(paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.UNKNOWN)).isZero();
        assertThat(paymentAttemptJpaRepository.countByStatus(PaymentAttemptStatus.REQUESTED)).isZero();
        assertThat(output.getAll()).contains("결제 결과 저장 실패: orderId=" + orderId + " step=TX_C");

        OrderPaymentInfo retried = orderFacade.reconcile(userId, orderId);

        PaymentAttempt finished = observeBatchOrder("after-retry", orderId, OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        printBatchEvents("tx-c", finished);
        System.out.printf("[tx-c-result] round=retried orderId=%d finished=%s pgCreates=%s pgLookups=%d->%d stock=%d%n",
            orderId, retried.isFinished(), PG.createCallsByOrder(), lookupsAfterFailure, PG.lookupCalls(), stock());
        assertThat(retried.isFinished()).isTrue();
        assertThat(PG.lookupCalls()).isEqualTo(lookupsAfterFailure);
        assertThat(PG.createCallsByOrder()).isEqualTo(createsBefore);
        assertThat(stock()).isEqualTo(99);
    }

    private PaymentEvent lastBatchEvent(PaymentAttempt attempt) {
        return paymentEventJpaRepository.findAll().stream()
            .filter(e -> e.getAttemptId().equals(attempt.getId()) && e.getSource() == PaymentEvent.Source.BATCH)
            .reduce((earlier, later) -> later).orElseThrow();
    }

    private void printBatchEvents(String round, PaymentAttempt attempt) {
        paymentEventJpaRepository.findAll().stream()
            .filter(e -> e.getAttemptId().equals(attempt.getId()))
            .forEach(e -> System.out.printf("[batch-event] round=%s orderId=%d source=%s observed=%s:%s:%s result=%s reason=%s%n",
                round, attempt.getOrderId(), e.getSource(), e.getObservedTransactionKey(), e.getObservedStatus(),
                e.getObservedAmount(), e.getResult(), e.getReason()));
    }

    private PaymentAttempt observeBatchOrder(String phase, Long orderId, OrderStatus status, ReservationStatus reservation) {
        PaymentAttempt attempt = paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
        OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
        System.out.printf("[batch-order] %s orderId=%d providerOrderId=%s key=%s payment=%s order=%s reservation=%s%n",
            phase, orderId, attempt.getProviderOrderId(), attempt.getTransactionKey(), attempt.getStatus(),
            order.getStatus(), order.getReservationStatus());
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getReservationStatus()).isEqualTo(reservation);
        return attempt;
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
