package com.loopers.application.payment;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.CreateObservation;
import com.loopers.domain.payment.LookupResult;
import com.loopers.domain.payment.PaymentDiscrepancy;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.PgTransaction;
import com.loopers.domain.payment.ProviderOrderId;
import com.loopers.domain.payment.TransactionKey;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PaymentObserveIntegrationTest {

    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(5);
    private static final TransactionKey KEY = new TransactionKey("TEST-TX-0001");

    private final PaymentTransactions paymentTransactions;
    private final OrderFacade orderFacade;
    private final ProductService productService;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;
    private Order order;
    private Payment payment;

    @Autowired
    PaymentObserveIntegrationTest(
        PaymentTransactions paymentTransactions,
        OrderFacade orderFacade,
        ProductService productService,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentTransactions = paymentTransactions;
        this.orderFacade = orderFacade;
        this.productService = productService;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(10));
        order = orderFacade.place(new OrderCreateCommand(USER,
            List.of(new OrderCreateCommand.Line(coat, OrderQuantity.of(2)))), NOW);
        payment = paymentTransactions.begin(USER, order.getId(), PaymentAttemptKey.of("attempt-1"), CardType.TEST, NOW)
            .payment();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Payment observe(CreateObservation observation) {
        return paymentTransactions.observe(payment.getId(), observation, LATER);
    }

    private Payment lookup(PgTransaction.Status status) {
        return lookup(status, 6_000);
    }

    private Payment lookup(PgTransaction.Status status, long amount) {
        LookupResult.Found found = new LookupResult.Found(List.of(new PgTransaction(
            KEY, ProviderOrderId.of(order.getId()), Money.of(amount), status)));
        return paymentTransactions.observe(payment.getId(), found, PaymentDiscrepancy.Source.CALLBACK, LATER);
    }

    private Order storedOrder() {
        return orderFacade.get(USER, order.getId());
    }

    private Quantity stock() {
        return productService.getStock(coat);
    }

    private int discrepancies() {
        return jdbcTemplate.queryForObject(
            "select count(*) from payment_discrepancy where payment_id = ?", Integer.class, payment.getId());
    }

    @Nested
    @DisplayName("PG-005, PG-010, PG-011, PG-015: 생성 결과를 반영한다")
    class CreateResult {

        @DisplayName("접수되면 결제는 PENDING 과 키를 저장하고, 주문 PENDING 과 묶인 재고는 그대로다")
        @Test
        void accepted() {
            Payment result = observe(new CreateObservation.Accepted(KEY));

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
            assertThat(result.getTransactionKey()).isEqualTo(KEY);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(stock()).isEqualTo(Quantity.of(8));
        }

        @DisplayName("효과 0 건 거절이면 결제와 주문이 FAILED 가 되고 재고를 돌려받는다")
        @Test
        void rejectedNoEffect() {
            Payment result = observe(new CreateObservation.RejectedNoEffect());

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(storedOrder().getPaidAmount()).isNull();
            assertThat(stock()).isEqualTo(Quantity.of(10));
        }

        @DisplayName("보내지 못했으면 거절과 같다")
        @Test
        void notSent() {
            Payment result = observe(new CreateObservation.NotSent());

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(stock()).isEqualTo(Quantity.of(10));
        }

        @DisplayName("결과를 모르면 UNKNOWN 이고, 주문 PENDING 과 묶인 재고는 그대로다")
        @Test
        void unknown() {
            Payment result = observe(new CreateObservation.CreateUnknown());

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.UNKNOWN);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(stock()).isEqualTo(Quantity.of(8));
        }
    }

    @Nested
    @DisplayName("SETTLE-002, RSV-002, RSV-003: 검증한 결과로 주문과 재고를 마무리한다")
    class Verified {

        @DisplayName("SUCCESS 면 주문이 CONFIRMED 가 되고 결제 금액과 확정 시각을 남긴다. 재고는 다시 빼지 않는다")
        @Test
        void success() {
            observe(new CreateObservation.Accepted(KEY));

            Payment result = lookup(PgTransaction.Status.SUCCESS);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(storedOrder().getPaidAmount()).isEqualTo(Money.of(6_000));
            assertThat(storedOrder().getConfirmedAt()).isEqualTo(LATER);
            assertThat(stock()).isEqualTo(Quantity.of(8));
        }

        @DisplayName("FAILED 면 주문이 FAILED 가 되고 재고를 돌려받는다")
        @Test
        void failed() {
            observe(new CreateObservation.Accepted(KEY));

            Payment result = lookup(PgTransaction.Status.FAILED);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.FAILED);
            assertThat(stock()).isEqualTo(Quantity.of(10));
        }

        @DisplayName("SETTLE-012: 생성 응답이 없어도 조회한 결과로 끝난다. 키는 조회에서 붙는다")
        @Test
        void settlesWithoutCreateResponse() {
            observe(new CreateObservation.CreateUnknown());

            Payment result = lookup(PgTransaction.Status.SUCCESS);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(result.getTransactionKey()).isEqualTo(KEY);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        }

        @DisplayName("RSV-007: 결제 중에 상품이 삭제돼도 실패하면 재고를 돌려받고 결제가 끝난다")
        @Test
        void restocksDeletedProduct() {
            observe(new CreateObservation.Accepted(KEY));
            productFacade.delete(coat);

            Payment result = lookup(PgTransaction.Status.FAILED);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(jdbcTemplate.queryForObject(
                "select quantity from product where id = ?", Integer.class, coat)).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("PAY-007, RSV-003: 같은 결과는 한 번만 반영한다")
    class Once {

        @DisplayName("FAILED 를 두 번 보아도 재고는 한 번만 돌려받는다")
        @Test
        void restocksOnce() {
            observe(new CreateObservation.Accepted(KEY));

            lookup(PgTransaction.Status.FAILED);
            lookup(PgTransaction.Status.FAILED);

            assertThat(stock()).isEqualTo(Quantity.of(10));
        }

        @DisplayName("SUCCESS 를 두 번 보아도 확정 시각은 처음 그대로다")
        @Test
        void confirmsOnce() {
            observe(new CreateObservation.Accepted(KEY));
            lookup(PgTransaction.Status.SUCCESS);

            paymentTransactions.observe(payment.getId(), new LookupResult.Found(List.of(new PgTransaction(
                KEY, ProviderOrderId.of(order.getId()), Money.of(6_000), PgTransaction.Status.SUCCESS))),
                PaymentDiscrepancy.Source.LOOKUP, LATER.plusSeconds(60));

            assertThat(storedOrder().getConfirmedAt()).isEqualTo(LATER);
        }

        @DisplayName("FAILED callback 10 건이 동시에 와도 재고는 한 번만 돌려받는다")
        @Test
        void restocksOnceUnderConcurrency() throws InterruptedException {
            observe(new CreateObservation.Accepted(KEY));

            List<Throwable> failures = runConcurrently(10, () -> lookup(PgTransaction.Status.FAILED));

            assertThat(failures).isEmpty();
            assertThat(stock()).isEqualTo(Quantity.of(10));
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("PAY-009, PAY-017: 모순은 고치지 않고 기록한다")
    class Contradiction {

        @DisplayName("금액이 다르면 CONFLICT 로 멈추고 불일치 한 줄. 주문 PENDING 과 묶인 재고는 그대로다")
        @Test
        void amountMismatch() {
            observe(new CreateObservation.Accepted(KEY));

            Payment result = lookup(PgTransaction.Status.SUCCESS, 9_000);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.CONFLICT);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(stock()).isEqualTo(Quantity.of(8));
            assertThat(discrepancies()).isEqualTo(1);
        }

        @DisplayName("같은 모순이 반복돼도 불일치는 한 줄이다")
        @Test
        void recordsOnce() {
            observe(new CreateObservation.Accepted(KEY));

            lookup(PgTransaction.Status.SUCCESS, 9_000);
            lookup(PgTransaction.Status.SUCCESS, 9_000);

            assertThat(discrepancies()).isEqualTo(1);
        }

        @DisplayName("SUCCESS 로 끝난 뒤 FAILED 가 보이면 덮어쓰지 않는다. 주문 CONFIRMED, 재고 그대로, 불일치 한 줄")
        @Test
        void resultAfterSettled() {
            observe(new CreateObservation.Accepted(KEY));
            lookup(PgTransaction.Status.SUCCESS);

            Payment result = lookup(PgTransaction.Status.FAILED);

            assertThat(result.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(storedOrder().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(stock()).isEqualTo(Quantity.of(8));
            assertThat(jdbcTemplate.queryForObject(
                "select reason from payment_discrepancy where payment_id = ?", String.class, payment.getId()))
                .isEqualTo("RESULT_AFTER_SETTLED");
        }
    }

    private static List<Throwable> runConcurrently(int threads, Runnable call) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    call.run();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).as("모든 worker 가 준비된다").isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("모든 반영이 제한 시간 안에 끝난다").isTrue();
        executor.shutdown();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("worker 가 정리된다").isTrue();
        return List.copyOf(failures);
    }
}
