package com.loopers.order.application;

import com.loopers.support.fixture.TestEntities;

import com.loopers.brand.domain.Brand;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderStatus;
import com.loopers.product.domain.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.application.PointUseCase;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 요청이 어느 순서로 처리되더라도 지켜져야 하는 최종 DB 상태와 업무 결과를 검증한다. */
@SpringBootTest
@Import(MySqlTestContainersConfig.class)
class OrderConcurrencyTest {

    private static final int REQUESTS = 8;

    @Autowired private OrderUseCase useCase;
    @Autowired private PointUseCase pointUseCase;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("[동시성] 재고 5개에 주문 8건을 동시에 확정하면 성공 5·재고 부족 3·최종 재고 0이다.")
    @Test
    void preservesStockAndOrdersUnderConcurrentConfirmation() throws Exception {
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 1_000L, 5);
        List<Purchase> purchases = new ArrayList<>();
        for (int index = 0; index < REQUESTS; index++) {
            User buyer = fixture.userWithPoint(1_000L);
            Order order = fixture.draftOrder(buyer, fixture.item(product, 1));
            purchases.add(new Purchase(order.getId(), buyer.getId()));
        }

        List<Outcome> outcomes = runTogether(purchases.stream()
            .<Callable<Outcome>>map(purchase -> () -> confirm(purchase))
            .toList());

        long successes = outcomes.stream().filter(Outcome::success).count();
        long stockRejections = outcomes.stream()
            .filter(result -> result.rejection() == ErrorCode.INSUFFICIENT_STOCK).count();
        List<Outcome> otherRejections = outcomes.stream()
            .filter(result -> result.rejection() != null && result.rejection() != ErrorCode.INSUFFICIENT_STOCK)
            .toList();
        List<Outcome> technicalErrors = outcomes.stream().filter(result -> result.technical() != null).toList();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Product savedProduct = entityManager.find(Product.class, product.getId());
            long confirmed = outcomes.stream()
                .map(result -> entityManager.find(Order.class, result.orderId()))
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED)
                .count();
            assertAll(
                () -> assertThat(outcomes).hasSize(REQUESTS),
                () -> assertThat(successes).isEqualTo(5),
                () -> assertThat(stockRejections).isEqualTo(3),
                () -> assertThat(otherRejections).isEmpty(),
                () -> assertThat(technicalErrors).isEmpty(),
                () -> assertThat(successes + stockRejections + otherRejections.size() + technicalErrors.size())
                    .isEqualTo(REQUESTS),
                () -> assertThat(TestEntities.stockQuantity(entityManager, savedProduct.getId())).isZero(),
                () -> assertThat(5 - successes).isEqualTo((long) TestEntities.stockQuantity(entityManager, savedProduct.getId())),
                () -> assertThat(confirmed).isEqualTo(successes)
            );
            for (Outcome outcome : outcomes) {
                Order savedOrder = entityManager.find(Order.class, outcome.orderId());
                User savedBuyer = entityManager.find(User.class, outcome.buyerId());
                if (outcome.success()) {
                    assertAll(
                        () -> assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                        () -> assertThat(savedOrder.getPaymentResult().amount()).isEqualTo(1_000L),
                        () -> assertThat(TestEntities.pointBalance(entityManager, savedBuyer.getId())).isZero()
                    );
                } else if (outcome.rejection() == ErrorCode.INSUFFICIENT_STOCK) {
                    assertAll(
                        () -> assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.DRAFT),
                        () -> assertThat(savedOrder.getPaymentResult()).isNull(),
                        () -> assertThat(TestEntities.pointBalance(entityManager, savedBuyer.getId())).isEqualTo(1_000L)
                    );
                }
            }
        });
    }

    @DisplayName("[동시성] 같은 DRAFT 주문을 두 번 동시에 확정하면 한 건만 성공하고 재고·포인트는 한 번만 차감된다.")
    @Test
    void confirmsSameOrderOnlyOnce() throws Exception {
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 1_000L, 5);
        User buyer = fixture.userWithPoint(5_000L);
        Order order = fixture.draftOrder(buyer, fixture.item(product, 1));
        Purchase purchase = new Purchase(order.getId(), buyer.getId());

        List<Outcome> outcomes = runTogether(List.of(
            () -> confirm(purchase),
            () -> confirm(purchase)
        ));
        long successes = outcomes.stream().filter(Outcome::success).count();
        long alreadyConfirmed = outcomes.stream()
            .filter(result -> result.rejection() == ErrorCode.ORDER_ALREADY_CONFIRMED).count();
        List<Outcome> otherRejections = outcomes.stream()
            .filter(result -> result.rejection() != null && result.rejection() != ErrorCode.ORDER_ALREADY_CONFIRMED)
            .toList();
        List<Outcome> technicalErrors = outcomes.stream().filter(result -> result.technical() != null).toList();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Order savedOrder = entityManager.find(Order.class, order.getId());
            int stock = TestEntities.stockQuantity(entityManager, product.getId());
            long balance = TestEntities.pointBalance(entityManager, buyer.getId());
            assertAll(
                () -> assertThat(outcomes).hasSize(2),
                () -> assertThat(successes).isEqualTo(1),
                () -> assertThat(alreadyConfirmed).isEqualTo(1),
                () -> assertThat(otherRejections).isEmpty(),
                () -> assertThat(technicalErrors).isEmpty(),
                () -> assertThat(successes + alreadyConfirmed + otherRejections.size() + technicalErrors.size())
                    .isEqualTo(2),
                () -> assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(savedOrder.getPaymentResult().amount()).isEqualTo(1_000L),
                () -> assertThat(stock).isEqualTo(4),
                () -> assertThat(balance).isEqualTo(4_000L)
            );
        });
    }

    @DisplayName("[동시성] 한 고객의 잔액 10000원·충분한 재고에서 서로 다른 4000원 DRAFT 주문 3건을 동시에 확정하면 "
        + "성공 2·잔액 부족 1·기술 오류 0·최종 잔액 2000원이다.")
    @Test
    void preservesPointAndOrdersUnderConcurrentConfirmation() throws Exception {
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 4_000L, 10);
        User buyer = fixture.userWithPoint(10_000L);
        List<Purchase> purchases = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Order order = fixture.draftOrder(buyer, fixture.item(product, 1));
            purchases.add(new Purchase(order.getId(), buyer.getId()));
        }

        List<Outcome> outcomes = runTogether(purchases.stream()
            .<Callable<Outcome>>map(purchase -> () -> confirm(purchase))
            .toList());
        long successes = outcomes.stream().filter(Outcome::success).count();
        long pointRejections = outcomes.stream()
            .filter(result -> result.rejection() == ErrorCode.INSUFFICIENT_POINT).count();
        List<Outcome> otherRejections = outcomes.stream()
            .filter(result -> result.rejection() != null && result.rejection() != ErrorCode.INSUFFICIENT_POINT)
            .toList();
        List<Outcome> technicalErrors = outcomes.stream().filter(result -> result.technical() != null).toList();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            long balance = TestEntities.pointBalance(entityManager, buyer.getId());
            int stock = TestEntities.stockQuantity(entityManager, product.getId());
            long confirmed = purchases.stream()
                .map(purchase -> entityManager.find(Order.class, purchase.orderId()))
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED)
                .count();
            assertAll(
                () -> assertThat(outcomes).hasSize(3),
                () -> assertThat(successes).isEqualTo(2),
                () -> assertThat(pointRejections).isEqualTo(1),
                () -> assertThat(otherRejections).isEmpty(),
                () -> assertThat(technicalErrors).isEmpty(),
                () -> assertThat(successes + pointRejections + otherRejections.size() + technicalErrors.size())
                    .isEqualTo(3),
                () -> assertThat(confirmed).isEqualTo(successes),
                () -> assertThat(balance).isEqualTo(2_000L),
                () -> assertThat(balance).isEqualTo(10_000L - 4_000L * successes),
                () -> assertThat(stock).isEqualTo(8),
                () -> assertThat((long) stock).isEqualTo(10 - successes)
            );
            for (Outcome outcome : outcomes) {
                Order savedOrder = entityManager.find(Order.class, outcome.orderId());
                if (outcome.success()) {
                    assertAll(
                        () -> assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                        () -> assertThat(savedOrder.getPaymentResult().amount()).isEqualTo(4_000L)
                    );
                } else if (outcome.rejection() == ErrorCode.INSUFFICIENT_POINT) {
                    assertAll(
                        () -> assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.DRAFT),
                        () -> assertThat(savedOrder.getPaymentResult()).isNull()
                    );
                }
            }
        });
    }

    @DisplayName("[동시성] 2000원 충전과 7000원 결제가 모두 성공하면 최종 잔액은 5000원이다.")
    @Test
    void preservesPointUnderConcurrentChargeAndConfirmation() throws Exception {
        Brand brand = fixture.brand("Nike");
        Product product = fixture.product(brand, "Air", 7_000L, 1);
        User buyer = fixture.userWithPoint(10_000L);
        Order order = fixture.draftOrder(buyer, fixture.item(product, 1));

        List<OperationOutcome> outcomes = runTogether(List.of(
            () -> charge(buyer.getId(), 2_000L),
            () -> confirmOperation(buyer.getId(), order.getId())
        ));
        long successes = outcomes.stream().filter(OperationOutcome::success).count();
        long businessRejections = outcomes.stream().filter(result -> result.rejection() != null).count();
        long technicalErrors = outcomes.stream().filter(result -> result.technical() != null).count();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Order savedOrder = entityManager.find(Order.class, order.getId());
            long balance = TestEntities.pointBalance(entityManager, buyer.getId());
            assertAll(
                () -> assertThat(outcomes).hasSize(2),
                () -> assertThat(successes).isEqualTo(2),
                () -> assertThat(businessRejections).isZero(),
                () -> assertThat(technicalErrors).isZero(),
                () -> assertThat(successes + businessRejections + technicalErrors).isEqualTo(2),
                () -> assertThat(balance).isEqualTo(5_000L),
                () -> assertThat(balance).isEqualTo(10_000L + 2_000L - 7_000L),
                () -> assertThat(savedOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(savedOrder.getPaymentResult().amount()).isEqualTo(7_000L),
                () -> assertThat(TestEntities.stockQuantity(entityManager, product.getId()))
                    .isZero()
            );
        });
    }

    private Outcome confirm(Purchase purchase) {
        try {
            useCase.confirm(purchase.buyerId(), purchase.orderId());
            return new Outcome(purchase.orderId(), purchase.buyerId(), true, null, null);
        } catch (CoreException exception) {
            return new Outcome(purchase.orderId(), purchase.buyerId(), false, exception.getErrorCode(), null);
        } catch (RuntimeException exception) {
            return new Outcome(purchase.orderId(), purchase.buyerId(), false, null, exception);
        }
    }

    private OperationOutcome charge(Long buyerId, long amount) {
        try {
            pointUseCase.charge(buyerId, amount);
            return new OperationOutcome(true, null, null);
        } catch (CoreException exception) {
            return new OperationOutcome(false, exception.getErrorCode(), null);
        } catch (RuntimeException exception) {
            return new OperationOutcome(false, null, exception);
        }
    }

    private OperationOutcome confirmOperation(Long buyerId, Long orderId) {
        Outcome outcome = confirm(new Purchase(orderId, buyerId));
        return new OperationOutcome(outcome.success(), outcome.rejection(), outcome.technical());
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(workers.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to start operation");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> future : futures) {
                outcomes.add(future.get(20, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private record Purchase(Long orderId, Long buyerId) {
    }

    private record Outcome(Long orderId, Long buyerId, boolean success, ErrorCode rejection, Exception technical) {
    }

    private record OperationOutcome(boolean success, ErrorCode rejection, Exception technical) {
    }
}
