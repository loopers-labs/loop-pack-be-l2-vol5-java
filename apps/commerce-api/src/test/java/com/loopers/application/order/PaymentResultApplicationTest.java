package com.loopers.application.order;

import com.loopers.application.user.UserRegistrationService;
import com.loopers.domain.brand.BrandModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.ReservationStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** TX B의 결과 저장과 TX C의 주문 반영을 각각 실패시켜, 결제 사실 보존과 중복 효과 방지를 확인한다. */
@SpringBootTest
class PaymentResultApplicationTest {

    private static final FakePaymentServer PG = startPg();

    @DynamicPropertySource
    static void pgUrl(DynamicPropertyRegistry registry) {
        registry.add("commerce.payment.pg.base-url", PG::baseUrl);
    }

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
    @Autowired private EntityManager entityManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;
    @MockitoSpyBean private PaymentEventRepositoryImpl paymentEvents;

    private Long userId;
    private Long productId;
    private Long orderId;

    @BeforeEach
    void setUp() {
        PG.reset();
        resilientPaymentProvider.lookupCircuitBreaker().reset();
        userId = registration.register().getId();
        Long brandId = brandJpaRepository.save(new BrandModel("나이키", null)).getId();
        productId = productJpaRepository.save(new ProductModel(brandId, "양말", 1_000L, 5)).getId();
        orderId = orderFacade.create(userId, List.of(new OrderFacade.OrderLine(productId, 3))).id();
        orderFacade.confirm(userId, orderId,
            new OrderFacade.PaymentCommand("attempt-abc", "TEST", "0000-0000-0000-0000"));
    }

    @AfterEach
    void tearDown() {
        reset(paymentEvents, paymentTransactions);
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void stopPg() throws InterruptedException {
        PG.close();
    }

    @Test
    void failedResultStorageRollsBackBAndRequeryRecovers() {
        PG.changeStatus("TEST-TX-1", "FAILED");
        long eventsBefore = paymentEventJpaRepository.count();
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            if (failFirst.getAndSet(false)) {
                entityManager.flush();
                assertThat(nativeNumber("select quantity from product where id = :id", productId)).isEqualTo(2);
                assertThat(entityManager.createNativeQuery("select status from payment_attempt where order_id = :id")
                    .setParameter("id", orderId).getSingleResult()).isEqualTo("FAILED");
                throw new IllegalStateException("test: TX B failed after changes");
            }
            return saved;
        }).when(paymentEvents).save(any(PaymentEvent.class));

        assertThatThrownBy(() -> orderFacade.reconcile(userId, orderId))
            .isInstanceOf(IllegalStateException.class);

        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(storedAttempt().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(stock()).isEqualTo(2);
        assertThat(paymentEventJpaRepository.count()).isEqualTo(eventsBefore);

        OrderPaymentInfo recovered = orderFacade.reconcile(userId, orderId);

        assertThat(recovered.paymentStatus()).isEqualTo("FAILED");
        assertOrder(OrderStatus.PAYMENT_FAILED, ReservationStatus.RELEASED);
        assertThat(stock()).isEqualTo(5);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.requests()).filteredOn(request -> request.path().equals("/api/v1/payments/TEST-TX-1"))
            .hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void recordingFinalResultDoesNotApplyItToOrderOrStock(String status) {
        PG.changeStatus("TEST-TX-1", status);
        PaymentObservation observation = paymentProvider.findByTransactionKey("TEST-TX-1");

        OrderPaymentInfo recorded = paymentTransactions.applyLookup(orderId, observation, PaymentEvent.Source.RECONCILE);

        assertThat(recorded.paymentStatus()).isEqualTo(status);
        assertThat(storedAttempt().getStatus().name()).isEqualTo(status);
        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getPaidAmount()).isZero();
        assertThat(stock()).isEqualTo(2);
        assertThat(recorded.isFinished()).isFalse();
    }

    @Test
    void successResultStorageRollsBackBAndRequeryRecovers() {
        PG.changeStatus("TEST-TX-1", "SUCCESS");
        long eventsBefore = paymentEventJpaRepository.count();
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object saved = invocation.callRealMethod();
            if (failFirst.getAndSet(false)) {
                entityManager.flush();
                assertThat(entityManager.createNativeQuery("select status from orders where id = :id")
                    .setParameter("id", orderId).getSingleResult()).isEqualTo("PAYMENT_PENDING");
                assertThat(entityManager.createNativeQuery("select status from payment_attempt where order_id = :id")
                    .setParameter("id", orderId).getSingleResult()).isEqualTo("SUCCESS");
                assertThat(nativeNumber("select paid_amount from orders where id = :id", orderId)).isZero();
                throw new IllegalStateException("test: TX B failed after success changes");
            }
            return saved;
        }).when(paymentEvents).save(any(PaymentEvent.class));

        assertThatThrownBy(() -> orderFacade.reconcile(userId, orderId))
            .isInstanceOf(IllegalStateException.class);

        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getPaidAmount()).isZero();
        assertThat(storedAttempt().getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        assertThat(stock()).isEqualTo(2);
        assertThat(paymentEventJpaRepository.count()).isEqualTo(eventsBefore);

        OrderPaymentInfo recovered = orderFacade.reconcile(userId, orderId);
        orderFacade.reconcile(userId, orderId);

        assertThat(recovered.paymentStatus()).isEqualTo("SUCCESS");
        assertOrder(OrderStatus.CONFIRMED, ReservationStatus.CONSUMED);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getPaidAmount()).isEqualTo(3_000L);
        assertThat(storedAttempt().getTransactionKey()).isEqualTo("TEST-TX-1");
        assertThat(stock()).isEqualTo(2);
        assertThat(paymentEventJpaRepository.count()).isEqualTo(eventsBefore + 1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(2);
        assertThat(PG.transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS, CONFIRMED, CONSUMED, 2, 3000", "FAILED, PAYMENT_FAILED, RELEASED, 5, 0"})
    void failureAfterApplyingOrderRollsBackOnlyCAndRecoversWithoutPg(
        String result, OrderStatus orderStatus, ReservationStatus reservation, int stockAfter, int paidAfter
    ) {
        PG.changeStatus("TEST-TX-1", result);
        long eventsBefore = paymentEventJpaRepository.count();
        AtomicBoolean failFirst = new AtomicBoolean(true);
        doAnswer(invocation -> {
            Object applied = invocation.callRealMethod();
            if (failFirst.getAndSet(false)) {
                entityManager.flush();
                assertThat(entityManager.createNativeQuery("select status from orders where id = :id")
                    .setParameter("id", orderId).getSingleResult()).isEqualTo(orderStatus.name());
                assertThat(nativeNumber("select quantity from product where id = :id", productId)).isEqualTo(stockAfter);
                assertThat(nativeNumber("select paid_amount from orders where id = :id", orderId)).isEqualTo(paidAfter);
                throw new IllegalStateException("test: TX C failed after changes");
            }
            return applied;
        }).when(paymentTransactions).applyRecordedResult(orderId);

        assertThatThrownBy(() -> orderFacade.reconcile(userId, orderId)).isInstanceOf(IllegalStateException.class);

        assertThat(storedAttempt().getStatus().name()).isEqualTo(result);
        assertThat(paymentEventJpaRepository.count()).isEqualTo(eventsBefore + 1);
        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        assertThat(stock()).isEqualTo(2);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getPaidAmount()).isZero();
        resilientPaymentProvider.lookupCircuitBreaker().transitionToOpenState();

        OrderPaymentInfo recovered = orderFacade.reconcile(userId, orderId);
        orderFacade.reconcile(userId, orderId);

        assertThat(recovered.isFinished()).isTrue();
        assertThat(recovered.checked()).isTrue();
        assertOrder(orderStatus, reservation);
        assertThat(stock()).isEqualTo(stockAfter);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getPaidAmount()).isEqualTo(paidAfter);
        assertThat(PG.lookupCalls()).isEqualTo(1);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(paymentEventJpaRepository.count()).isEqualTo(eventsBefore + 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCESS", "FAILED"})
    void sameKeyConfirmAppliesStoredResultWithoutAnotherPgRequest(String result) {
        PG.changeStatus("TEST-TX-1", result);
        paymentTransactions.applyLookup(orderId, paymentProvider.findByTransactionKey("TEST-TX-1"),
            PaymentEvent.Source.RECONCILE);
        assertOrder(OrderStatus.PAYMENT_PENDING, ReservationStatus.RESERVED);
        resilientPaymentProvider.lookupCircuitBreaker().transitionToOpenState();

        OrderPaymentInfo recovered = orderFacade.confirm(userId, orderId,
            new OrderFacade.PaymentCommand("attempt-abc", "TEST", "0000-0000-0000-0000"));

        assertThat(recovered.isFinished()).isTrue();
        assertThat(recovered.checked()).isTrue();
        assertThat(recovered.paymentStatus()).isEqualTo(result);
        assertThat(stock()).isEqualTo("SUCCESS".equals(result) ? 2 : 5);
        assertThat(PG.createCalls()).isEqualTo(1);
        assertThat(PG.lookupCalls()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"SUCCESS, CONFIRMED, CONSUMED, 2", "FAILED, PAYMENT_FAILED, RELEASED, 5"})
    void sameFinalResultAppliedConcurrentlyChangesOrderAndStockOnce(
        String result, OrderStatus orderStatus, ReservationStatus reservation, int stockAfter
    ) throws Exception {
        PG.changeStatus("TEST-TX-1", result);
        PaymentObservation observed = paymentProvider.findByTransactionKey("TEST-TX-1");
        CountDownLatch enteredC = new CountDownLatch(4);
        doAnswer(invocation -> {
            enteredC.countDown();
            Object applied = invocation.callRealMethod();
            entityManager.flush();
            // 첫 TX C가 주문 잠금을 보유한 채, 나머지가 TX C에 진입한 것을 확인한다.
            assertThat(enteredC.await(10, TimeUnit.SECONDS)).isTrue();
            return applied;
        }).when(paymentTransactions).applyRecordedResult(orderId);

        List<String> statuses = applyTogether(observed, 4);

        assertThat(statuses).containsOnly(result);
        assertOrder(orderStatus, reservation);
        assertThat(stock()).isEqualTo(stockAfter);
        List<PaymentEvent> lookupEvents = paymentEventJpaRepository.findAll().stream()
            .filter(event -> event.getSource() == PaymentEvent.Source.RECONCILE)
            .toList();
        assertThat(lookupEvents).extracting(PaymentEvent::getResult).containsExactlyInAnyOrder(
            PaymentAttempt.Result.APPLIED, PaymentAttempt.Result.IGNORED,
            PaymentAttempt.Result.IGNORED, PaymentAttempt.Result.IGNORED);
    }

    private List<String> applyTogether(PaymentObservation observation, int count) throws Exception {
        var executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch recorded = new CountDownLatch(count);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test start timed out");
                    }
                    paymentTransactions.applyLookup(orderId, observation, PaymentEvent.Source.RECONCILE);
                    recorded.countDown();
                    if (!recorded.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("result recording timed out");
                    }
                    return paymentTransactions.applyRecordedResult(orderId).paymentStatus();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> statuses = new ArrayList<>();
            for (Future<String> future : futures) {
                statuses.add(future.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertOrder(OrderStatus status, ReservationStatus reservation) {
        OrderModel order = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getReservationStatus()).isEqualTo(reservation);
    }

    private PaymentAttempt storedAttempt() {
        return paymentAttemptJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    private int stock() {
        return productJpaRepository.findById(productId).orElseThrow().getStockQuantity();
    }

    private int nativeNumber(String sql, Long id) {
        return ((Number) entityManager.createNativeQuery(sql).setParameter("id", id).getSingleResult()).intValue();
    }

    private static FakePaymentServer startPg() {
        try {
            return new FakePaymentServer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
