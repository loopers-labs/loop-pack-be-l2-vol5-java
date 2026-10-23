package com.loopers.application.payment;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.CardNumber;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.support.pg.PgFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PaymentFacadeIntegrationTest {

    private static final PgFixture PG = PgFixture.start();

    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final CardNumber CARD = CardNumber.of("0000-0000-0000-0000");

    private final PaymentFacade paymentFacade;
    private final PaymentRepository paymentRepository;
    private final OrderFacade orderFacade;
    private final ProductService productService;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final DataSource dataSource;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;
    private int createsBefore;

    @Autowired
    PaymentFacadeIntegrationTest(
        PaymentFacade paymentFacade,
        PaymentRepository paymentRepository,
        OrderFacade orderFacade,
        ProductService productService,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        DataSource dataSource,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentFacade = paymentFacade;
        this.paymentRepository = paymentRepository;
        this.orderFacade = orderFacade;
        this.productService = productService;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.dataSource = dataSource;
        this.databaseCleanUp = databaseCleanUp;
    }

    @DynamicPropertySource
    static void pgBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("pg.base-url", PG::baseUrl);
    }

    @AfterAll
    static void stopPg() {
        PG.close();
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(10));
        PG.reset();
        createsBefore = PG.createCount();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order place(int coats) {
        return orderFacade.place(new OrderCreateCommand(USER,
            List.of(new OrderCreateCommand.Line(coat, OrderQuantity.of(coats)))), NOW);
    }

    private Payment confirm(Long orderId, String attemptKey) {
        return paymentFacade.confirm(new PaymentConfirmCommand(
            USER, orderId, PaymentAttemptKey.of(attemptKey), CardType.TEST, CARD), NOW);
    }

    private static void lockOrder(Connection holder, Long orderId) throws Exception {
        try (PreparedStatement lock = holder.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
            lock.setLong(1, orderId);
            lock.executeQuery().close();
        }
    }

    private int creates() {
        return PG.createCount() - createsBefore;
    }

    private int lookups() {
        return (int) PG.receivedRequests().stream().filter(request -> request.method().equals("GET")).count();
    }

    private PgFixture.ReceivedRequest lastCreate() {
        return PG.receivedRequests().stream()
            .filter(request -> request.method().equals("POST"))
            .reduce((first, second) -> second)
            .orElseThrow();
    }

    @Nested
    @DisplayName("PAY-005, RSV-001: REQUESTED 를 commit 한 뒤 PG 생성을 한 번 보낸다")
    class Send {

        @DisplayName("DRAFT 주문을 confirm 하면 PG 에 우리 주문 번호와 저장된 금액으로 한 번 보낸다")
        @Test
        void sendsOnce() {
            Order order = place(2);

            Payment payment = confirm(order.getId(), "attempt-1");

            assertThat(creates()).isEqualTo(1);
            PgFixture.ReceivedRequest sent = lastCreate();
            assertThat(sent.body().get("orderId").asText()).isEqualTo(ProviderOrderId.of(order.getId()).value());
            assertThat(sent.body().get("amount").asLong()).isEqualTo(6_000);
            assertThat(sent.body().get("cardNo").asText()).isEqualTo(CARD.value());
            assertThat(paymentRepository.findByOrderId(order.getId())).map(Payment::getId).contains(payment.getId());
        }

        @DisplayName("같은 키를 다시 보내면 PG 를 다시 부르지 않는다")
        @Test
        void doesNotResendForSameKey() {
            Order order = place(2);
            Payment first = confirm(order.getId(), "attempt-1");

            Payment again = confirm(order.getId(), "attempt-1");

            assertThat(again.getId()).isEqualTo(first.getId());
            assertThat(creates()).isEqualTo(1);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }
    }

    @Nested
    @DisplayName("RESIL-010, PG-005, PG-010, PG-011: 생성 결과를 반영해 돌려주고, 결과를 기다리지 않는다")
    class ObserveCreate {

        @DisplayName("접수되면 PENDING 과 PG 가 만든 키를 돌려준다. 주문 PENDING, 재고는 묶인 채다")
        @Test
        void accepted() {
            Order order = place(2);

            Payment payment = confirm(order.getId(), "attempt-1");

            String created = PG.transactionsOf(ProviderOrderId.of(order.getId()).value()).stream()
                .map(PgFixture.Transaction::transactionKey)
                .max(String::compareTo)
                .orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(payment.getTransactionKey().value()).isEqualTo(created);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }

        @DisplayName("효과 0 건 거절이면 FAILED 를 돌려주고, 주문 FAILED, 재고를 돌려받는다")
        @Test
        void rejected() {
            Order order = place(2);
            PG.rejectNextCreate();

            Payment payment = confirm(order.getId(), "attempt-1");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(10));
        }

        @DisplayName("응답이 끊기면 UNKNOWN 을 돌려주고 다시 보내지 않는다. 재고는 묶인 채다")
        @Test
        void unknown() {
            Order order = place(2);
            PG.dropNextCreateResponse();

            Payment payment = confirm(order.getId(), "attempt-1");

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(creates()).isEqualTo(1);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }

        @DisplayName("같은 키를 다시 보내면 반영된 상태를 돌려준다")
        @Test
        void repeatReturnsObservedState() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");

            Payment again = confirm(order.getId(), "attempt-1");

            assertThat(again.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(again.getTransactionKey()).isNotNull();
        }

        @DisplayName("confirm 은 PG 조회를 부르지 않는다. 결과는 callback, reconcile 이 맡는다")
        @Test
        void doesNotLookup() {
            Order order = place(2);
            int lookupsBefore = lookups();

            confirm(order.getId(), "attempt-1");

            assertThat(lookups()).isEqualTo(lookupsBefore);
        }
    }

    @Nested
    @DisplayName("SETTLE-007, 9장: PG 가 받은 뒤 우리 쪽 반영이 실패해도 다시 보내지 않고, 조회로 한 번 끝난다")
    class InternalSaveFailure {

        @DisplayName("생성 응답을 반영하는 TX B 가 잠금 대기로 실패하면 오류 없이 REQUESTED 를 돌려주고, 잠금이 풀린 뒤 reconcile 이 한 번 확정한다")
        @Test
        void confirmKeepsRequestedThenReconcileSettles() throws Exception {
            Order order = place(2);
            PgFixture.HeldResponse held = PG.holdNextCreateResponse();
            CompletableFuture<Payment> confirming = CompletableFuture.supplyAsync(() -> confirm(order.getId(), "attempt-1"));
            held.awaitStored();

            Payment returned;
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                lockOrder(holder, order.getId());
                held.release();
                returned = confirming.get(15, TimeUnit.SECONDS);
                holder.rollback();
            }
            String key = PG.transactionsOf(ProviderOrderId.of(order.getId()).value()).stream()
                .map(PgFixture.Transaction::transactionKey).max(String::compareTo).orElseThrow();
            PG.changeStatus(key, PgFixture.Status.SUCCESS);

            Payment reconciled = paymentFacade.reconcile(USER, order.getId(), NOW).payment();

            assertThat(returned.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(reconciled.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(reconciled.getTransactionKey().value()).isEqualTo(key);
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
            assertThat(creates()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("RSV-001, PAY-003: TX A 가 거절하면 PG 를 부르지 않는다")
    class NotSent {

        @DisplayName("재고가 모자라면 create 0 이다")
        @Test
        void insufficientStock() {
            Order order = place(11);

            assertThatThrownBy(() -> confirm(order.getId(), "attempt-1"))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_STOCK);
            assertThat(creates()).isZero();
        }

        @DisplayName("시도가 있는 주문에 새 키가 오면 create 는 처음 한 번뿐이다")
        @Test
        void newKeyOnStartedOrder() {
            Order order = place(2);
            confirm(order.getId(), "attempt-1");

            assertThatThrownBy(() -> confirm(order.getId(), "attempt-2"))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_DRAFT);
            assertThat(creates()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("PAY-004, ORDER-018: 동시에 와도 PG 생성은 한 번이다")
    class Concurrent {

        @DisplayName("같은 키 confirm 10 건이 동시에 와도 create 1, 시도 1, 재고 차감 1 이고 모두 같은 시도를 받는다")
        @Test
        void sameKey() throws InterruptedException {
            Order order = place(2);

            List<Object> results = runConcurrently(10, i -> confirm(order.getId(), "attempt-1"));

            assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(Payment.class));
            assertThat(results).extracting(result -> ((Payment) result).getId()).containsOnly(
                paymentRepository.findByOrderId(order.getId()).orElseThrow().getId());
            assertThat(creates()).isEqualTo(1);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
        }

        @DisplayName("같은 주문에 다른 키 10 건이 동시에 오면 하나만 보내고 나머지는 ORDER_NOT_DRAFT 다")
        @Test
        void differentKeys() throws InterruptedException {
            Order order = place(2);

            List<Object> results = runConcurrently(10, i -> confirm(order.getId(), "attempt-" + i));

            assertThat(results).filteredOn(result -> result instanceof Payment).hasSize(1);
            assertThat(results).filteredOn(result -> result instanceof DomainException exception
                && exception.error() == DomainError.ORDER_NOT_DRAFT).hasSize(9);
            assertThat(creates()).isEqualTo(1);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }
    }

    private static List<Object> runConcurrently(int threads, IntFunction<Object> call) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            int index = i;
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    results.add(call.apply(index));
                } catch (Throwable t) {
                    results.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).as("모든 worker 가 준비된다").isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("모든 confirm 이 제한 시간 안에 끝난다").isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("worker 가 정리된다").isTrue();
        return List.copyOf(results);
    }
}
