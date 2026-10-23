package com.loopers.application.order;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.loopers.application.payment.PaymentResultApplier;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentConflict;
import com.loopers.domain.payment.PaymentConflictReason;
import com.loopers.domain.payment.PaymentConflictRepository;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserModel;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.pg.TestPg;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 고객 결제 확인(reconcile)과 결과 반영 경계(TX B)의 충돌 기록·삭제 상품 처리(docs/week5/design.md D-005, D-010~D-012,
 * D-019, D-037~D-039). 결제를 미리 처리중·UNKNOWN 상태로 만든 뒤, 테스트 PG의 조회 응답을 바꿔 가며 확인한다.
 */
@SpringBootTest
class OrderPaymentReconcileTest {

    private static final long PRICE = 2_000L;
    private static final int INITIAL_STOCK = 10;
    private static final int QUANTITY = 2;
    private static final long AMOUNT = PRICE * QUANTITY;

    @Autowired
    private OrderFacade orderFacade;

    @Autowired
    private PaymentResultApplier paymentResultApplier;

    @Autowired
    private PaymentAttemptRepository paymentAttemptRepository;

    @Autowired
    private PaymentConflictRepository paymentConflictRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private CircuitBreaker pgLookupCircuitBreaker;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final WireMockServer pg = TestPg.server();

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private record Fixture(Long userId, Long orderId, List<Long> productIds) {
        String providerOrderId() {
            return String.format("LP-ORD-%010d", orderId);
        }

        String transactionKey() {
            return TestPg.transactionKeyOf(providerOrderId());
        }
    }

    private Fixture draft(int productCount) {
        Long userId = userJpaRepository.save(new UserModel()).getId();
        List<Long> productIds = new ArrayList<>();
        List<OrderItemRequest> items = new ArrayList<>();
        for (int i = 0; i < productCount; i++) {
            Long productId = productJpaRepository.save(new ProductModel("상품" + i, PRICE, 1L, INITIAL_STOCK)).getId();
            productIds.add(productId);
            items.add(new OrderItemRequest(productId, QUANTITY));
        }
        Long orderId = orderFacade.createOrder(userId, items).id();
        return new Fixture(userId, orderId, productIds);
    }

    /** 테스트 PG 기본 응답(202 PENDING)으로 확정해 처리중으로 만든다. */
    private Fixture pending(int productCount) {
        Fixture fixture = draft(productCount);
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command());
        assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.PENDING);
        return fixture;
    }

    /** 생성 응답을 잃고 바로 재조회도 0건이라 UNKNOWN이 된 결제. 늦게 기록되는 생성 요청이 다음 테스트로 넘어가지 않게 기록될 때까지 기다린다. */
    private Fixture unknown() throws InterruptedException {
        Fixture fixture = draft(1);
        pg.stubFor(post(urlEqualTo(TestPg.PAYMENTS_PATH)).willReturn(aResponse().withStatus(202).withFixedDelay(1_000)));
        stubList(fixture, "[]");
        orderFacade.confirmOrder(fixture.orderId(), fixture.userId(), command());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (pg.findAll(postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH))).isEmpty() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertThat(attempt(fixture).getStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN);
        return fixture;
    }

    private static ConfirmPaymentCommand command() {
        return new ConfirmPaymentCommand("key-1", "TEST", "0000-0000-0000-0000");
    }

    private void stubList(Fixture fixture, String body) {
        pg.stubFor(get(urlPathEqualTo(TestPg.PAYMENTS_PATH))
            .withQueryParam("orderId", equalTo(fixture.providerOrderId()))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubSingle(Fixture fixture, int status, String body) {
        pg.stubFor(get(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + fixture.transactionKey()))
            .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static String transactionJson(Fixture fixture, String transactionKey, long amount, String status) {
        return "{\"transactionKey\":\"" + transactionKey + "\",\"orderId\":\"" + fixture.providerOrderId()
            + "\",\"amount\":" + amount + ",\"status\":\"" + status + "\"}";
    }

    private PaymentAttempt attempt(Fixture fixture) {
        return paymentAttemptRepository.findByOrderId(fixture.orderId()).orElseThrow();
    }

    private OrderStatus orderStatus(Fixture fixture) {
        return orderJpaRepository.findById(fixture.orderId()).orElseThrow().getStatus();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getRemainingStock();
    }

    private List<PaymentConflict> conflicts(Fixture fixture) {
        return paymentConflictRepository.findAllByAttemptId(attempt(fixture).getId());
    }

    private PaymentObservation observation(Fixture fixture, PaymentObservation.Status status) {
        return new PaymentObservation(fixture.providerOrderId(), fixture.transactionKey(), status, AMOUNT);
    }

    @DisplayName("미종결 결제를 확인하면, ")
    @Nested
    class Unfinished {
        @DisplayName("응답을 잃은(UNKNOWN) 결제는 주문 식별자로 목록을 조회해, SUCCESS 거래 한 건으로 확정한다. create는 다시 보내지 않는다.")
        @Test
        void confirmsUnknown_byListLookup() throws InterruptedException {
            // arrange
            Fixture fixture = unknown();
            stubList(fixture, "[" + transactionJson(fixture, "TEST-TX-LATE", AMOUNT, "SUCCESS") + "]");

            // act
            OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.SUCCESS),
                () -> assertThat(info.paidAmount()).isEqualTo(AMOUNT),
                () -> assertThat(attempt(fixture).getTransactionKey()).isEqualTo("TEST-TX-LATE"),
                () -> assertThat(stockOf(fixture.productIds().get(0))).isEqualTo(INITIAL_STOCK - QUANTITY)
            );
            pg.verify(exactly(1), postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH)));
        }

        @DisplayName("처리중 결제는 거래 키로 단건 조회해, FAILED면 결제 실패로 종결하고 재고를 돌려준다.")
        @Test
        void failsPending_bySingleLookup() {
            // arrange
            Fixture fixture = pending(1);
            stubSingle(fixture, 200, transactionJson(fixture, fixture.transactionKey(), AMOUNT, "FAILED"));

            // act
            OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(stockOf(fixture.productIds().get(0))).isEqualTo(INITIAL_STOCK)
            );
        }

        @DisplayName("조회 결과가 없으면(404) 실패로 보지 않고 상태와 재고를 그대로 둔다.")
        @Test
        void keepsState_whenNotFound() {
            // arrange
            Fixture fixture = pending(1);
            stubSingle(fixture, 404, "");

            // act
            OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.PENDING),
                () -> assertThat(stockOf(fixture.productIds().get(0))).isEqualTo(INITIAL_STOCK - QUANTITY)
            );
        }

        @DisplayName("조회 회로가 열려 있으면 PG를 부르지 않고, 상태를 바꾸지 않은 채 저장된 상태를 돌려준다. (D-029, D-038)")
        @Test
        void keepsState_whenCircuitIsOpen() {
            // arrange
            Fixture fixture = pending(1);
            stubSingle(fixture, 200, transactionJson(fixture, fixture.transactionKey(), AMOUNT, "SUCCESS"));
            pgLookupCircuitBreaker.transitionToOpenState();

            // act
            OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.PENDING);
            pg.verify(exactly(0), getRequestedFor(urlPathMatching(TestPg.PAYMENTS_PATH + ".*")));
        }

        @DisplayName("조회가 실패하면 상태를 바꾸지 않고, 고객 경로의 정한 횟수(3회)까지만 조회한다. (D-038)")
        @Test
        void keepsState_andRetriesThreeTimes_whenLookupFails() {
            // arrange
            Fixture fixture = pending(1);
            stubSingle(fixture, 500, "");

            // act
            OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.PENDING);
            pg.verify(exactly(3), getRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH + "/" + fixture.transactionKey())));
        }
    }

    @DisplayName("확인할 결제가 없거나 이미 끝났으면, ")
    @Nested
    class NothingToReconcile {
        @DisplayName("이미 종결된 결제는 PG를 부르지 않고 저장된 상태를 돌려준다. (D-037)")
        @Test
        void returnsStoredState_whenTerminal() {
            // arrange
            Fixture fixture = pending(1);
            stubSingle(fixture, 200, transactionJson(fixture, fixture.transactionKey(), AMOUNT, "FAILED"));
            orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());
            pg.resetRequests();

            // act
            OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            assertThat(info.status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
            pg.verify(exactly(0), getRequestedFor(urlPathMatching(TestPg.PAYMENTS_PATH + ".*")));
        }

        @DisplayName("결제 시도가 없는 주문(DRAFT)은 409다. (D-039)")
        @Test
        void conflicts_whenNoAttempt() {
            Fixture fixture = draft(1);
            CoreException exception = assertThrows(CoreException.class,
                () -> orderFacade.reconcilePayment(fixture.orderId(), fixture.userId()));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.CONFLICT);
        }

        @DisplayName("남의 주문이면 404다.")
        @Test
        void notFound_whenNotOwner() {
            Fixture fixture = pending(1);
            CoreException exception = assertThrows(CoreException.class,
                () -> orderFacade.reconcilePayment(fixture.orderId(), fixture.userId() + 999));
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("조회 결과가 우리 기록과 어긋나면, (D-010~D-012)")
    @Nested
    class Conflicts {
        @DisplayName("같은 주문 식별자로 거래가 2건이면 고르지 않고 충돌로 기록하며, 상태·재고는 그대로이고 create도 다시 보내지 않는다.")
        @Test
        void recordsMultipleTransactions() throws InterruptedException {
            // arrange
            Fixture fixture = unknown();
            stubList(fixture, "[" + transactionJson(fixture, "TX-1", AMOUNT, "SUCCESS") + ","
                + transactionJson(fixture, "TX-2", AMOUNT, "FAILED") + "]");

            // act
            OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            List<PaymentConflict> conflicts = conflicts(fixture);
            assertAll(
                () -> assertThat(info.payment().status()).isEqualTo(PaymentAttemptStatus.UNKNOWN),
                () -> assertThat(stockOf(fixture.productIds().get(0))).isEqualTo(INITIAL_STOCK - QUANTITY),
                () -> assertThat(conflicts).hasSize(1),
                () -> assertThat(conflicts.get(0).getReason()).isEqualTo(PaymentConflictReason.MULTIPLE_TRANSACTIONS),
                () -> assertThat(conflicts.get(0).getRecordedStatus()).isEqualTo(PaymentAttemptStatus.UNKNOWN)
            );
            pg.verify(exactly(1), postRequestedFor(urlEqualTo(TestPg.PAYMENTS_PATH)));
        }

        @DisplayName("금액이 다른 거래면 MISMATCH로 기록하고 상태를 바꾸지 않는다.")
        @Test
        void recordsMismatch() {
            // arrange
            Fixture fixture = pending(1);
            stubSingle(fixture, 200, transactionJson(fixture, fixture.transactionKey(), AMOUNT + 1, "SUCCESS"));

            // act
            OrderInfo info = orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            List<PaymentConflict> conflicts = conflicts(fixture);
            assertAll(
                () -> assertThat(info.status()).isEqualTo(OrderStatus.PAYMENT_PENDING),
                () -> assertThat(conflicts).hasSize(1),
                () -> assertThat(conflicts.get(0).getReason()).isEqualTo(PaymentConflictReason.MISMATCH),
                () -> assertThat(conflicts.get(0).getObservedAmount()).isEqualTo(AMOUNT + 1)
            );
        }

        @DisplayName("같은 충돌을 다시 발견하면 새 행을 만들지 않고 마지막 발견 시각만 바꾼다.")
        @Test
        void updatesLastDetectedAt_whenSameConflictIsFoundAgain() throws InterruptedException {
            // arrange
            Fixture fixture = pending(1);
            stubSingle(fixture, 200, transactionJson(fixture, fixture.transactionKey(), AMOUNT + 1, "SUCCESS"));
            orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());
            PaymentConflict first = conflicts(fixture).get(0);
            TimeUnit.MILLISECONDS.sleep(20);

            // act
            orderFacade.reconcilePayment(fixture.orderId(), fixture.userId());

            // assert
            List<PaymentConflict> conflicts = conflicts(fixture);
            assertAll(
                () -> assertThat(conflicts).hasSize(1),
                () -> assertThat(conflicts.get(0).getLastDetectedAt()).isAfter(first.getLastDetectedAt()),
                () -> assertThat(conflicts.get(0).getFirstDetectedAt()).isEqualTo(first.getFirstDetectedAt())
            );
        }

        @DisplayName("FAILED로 종결된 뒤 같은 거래가 SUCCESS로 관찰되면, 상태를 바꾸지 않고 DIFFERENT_OUTCOME_AFTER_TERMINAL로 기록한다.")
        @Test
        void recordsDifferentOutcomeAfterTerminal() {
            // arrange
            Fixture fixture = pending(1);
            paymentResultApplier.apply(fixture.orderId(), observation(fixture, PaymentObservation.Status.FAILED));

            // act
            paymentResultApplier.apply(fixture.orderId(), observation(fixture, PaymentObservation.Status.SUCCESS));

            // assert
            List<PaymentConflict> conflicts = conflicts(fixture);
            assertAll(
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(stockOf(fixture.productIds().get(0))).isEqualTo(INITIAL_STOCK),
                () -> assertThat(conflicts).hasSize(1),
                () -> assertThat(conflicts.get(0).getReason()).isEqualTo(PaymentConflictReason.DIFFERENT_OUTCOME_AFTER_TERMINAL),
                () -> assertThat(conflicts.get(0).getRecordedStatus()).isEqualTo(PaymentAttemptStatus.FAILED),
                () -> assertThat(conflicts.get(0).getObservedStatus()).isEqualTo(PaymentObservation.Status.SUCCESS)
            );
        }
    }

    @DisplayName("결제 실패를 반영할 때, ")
    @Nested
    class ReturnStock {
        @DisplayName("그 사이 삭제된 상품은 반환을 건너뛰고(비노출 유지), 나머지 상품은 돌려주며 주문은 결제 실패로 끝난다. (D-019)")
        @Test
        void skipsDeletedProduct() {
            // arrange
            Fixture fixture = pending(2);
            Long deletedId = fixture.productIds().get(0);
            Long aliveId = fixture.productIds().get(1);
            ProductModel deleted = productJpaRepository.findById(deletedId).orElseThrow();
            deleted.delete();
            productJpaRepository.saveAndFlush(deleted);

            // act
            paymentResultApplier.apply(fixture.orderId(), new PaymentObservation(
                fixture.providerOrderId(), fixture.transactionKey(), PaymentObservation.Status.FAILED, AMOUNT * 2));

            // assert
            ProductModel deletedAfter = productJpaRepository.findById(deletedId).orElseThrow();
            assertAll(
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(deletedAfter.getRemainingStock()).isEqualTo(INITIAL_STOCK - QUANTITY),
                () -> assertThat(deletedAfter.getDeletedAt()).isNotNull(),
                () -> assertThat(stockOf(aliveId)).isEqualTo(INITIAL_STOCK)
            );
        }

        @DisplayName("결제 대기 중에 관리자가 가용 재고를 다시 설정했다면, 실패 반영은 설정값 위에 예약분만 더한다. (INV-006, design.md 6번)")
        @Test
        void returnsReservationOnTopOfAdminSetting() {
            // arrange — 재고 10에서 2를 확보(가용 8), 그 사이 관리자가 가용 재고를 5로 설정
            Fixture fixture = pending(1);
            productService.changeStock(fixture.productIds().get(0), 5);

            // act
            paymentResultApplier.apply(fixture.orderId(), observation(fixture, PaymentObservation.Status.FAILED));

            // assert — 10으로 되돌리는 게 아니라 5 + 2
            assertThat(stockOf(fixture.productIds().get(0))).isEqualTo(5 + QUANTITY);
        }

        @DisplayName("같은 FAILED가 먼저 반영된 뒤 다시 들어오면(callback과 reconcile이 겹침) 재고를 두 번 돌려주지 않는다. (INV-002)")
        @Test
        void returnsOnce_whenSameFailureArrivesTwice() {
            // arrange
            Fixture fixture = pending(1);
            paymentResultApplier.apply(fixture.orderId(), observation(fixture, PaymentObservation.Status.FAILED));

            // act
            paymentResultApplier.apply(fixture.orderId(), observation(fixture, PaymentObservation.Status.FAILED));

            // assert
            assertAll(
                () -> assertThat(stockOf(fixture.productIds().get(0))).isEqualTo(INITIAL_STOCK),
                () -> assertThat(conflicts(fixture)).isEmpty()
            );
        }

        @DisplayName("같은 FAILED 반영 네 개가 동시에 들어와도 재고는 한 번만 돌려준다. (INV-002)")
        @Test
        void returnsOnce_whenFailuresArriveConcurrently() throws Exception {
            // arrange
            Fixture fixture = pending(1);
            List<Callable<Object>> requests = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                requests.add(() -> paymentResultApplier.apply(fixture.orderId(), observation(fixture, PaymentObservation.Status.FAILED)));
            }

            // act
            List<Throwable> failures = runConcurrently(requests);

            // assert
            assertAll(
                () -> assertThat(failures).isEmpty(),
                () -> assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PAYMENT_FAILED),
                () -> assertThat(stockOf(fixture.productIds().get(0))).isEqualTo(INITIAL_STOCK)
            );
        }
    }

    @DisplayName("주문 목록에도 결제 시도 상태가 함께 나온다.")
    @Test
    void listsOrdersWithPayment() {
        // arrange
        Fixture fixture = pending(1);
        Long draftId = orderFacade.createOrder(fixture.userId(), List.of(new OrderItemRequest(fixture.productIds().get(0), 1))).id();

        // act
        List<OrderInfo> orders = orderFacade.getMyOrders(fixture.userId());

        // assert
        OrderInfo started = orders.stream().filter(order -> order.id().equals(fixture.orderId())).findFirst().orElseThrow();
        OrderInfo draft = orders.stream().filter(order -> order.id().equals(draftId)).findFirst().orElseThrow();
        assertAll(
            () -> assertThat(started.payment().status()).isEqualTo(PaymentAttemptStatus.PENDING),
            () -> assertThat(draft.payment()).isNull()
        );
    }

    private static List<Throwable> runConcurrently(List<Callable<Object>> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Throwable>> futures = new ArrayList<>();
            for (Callable<Object> request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await(30, TimeUnit.SECONDS);
                    try {
                        request.call();
                        return null;
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> future : futures) {
                Throwable failure = future.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            return failures;
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }
}
