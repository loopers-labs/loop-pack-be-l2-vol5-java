package com.loopers.application.order;

import com.loopers.application.product.AdminProductService;
import com.loopers.application.product.ProductQueryException;
import com.loopers.application.user.PointService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.PointsException;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.domain.user.UserRole;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderConcurrencyIntegrationTest {
    @Autowired private OrderService orders;
    @Autowired private PointService points;
    @Autowired private AdminProductService products;
    @Autowired private BrandRepository brands;
    @Autowired private FixtureUserInitializer users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private PlatformTransactionManager transactionManager;
    private long brandId;

    @BeforeEach
    void fixture() {
        users.initialize();
        brandId = brands.save(new Brand("동시성 브랜드")).getId();
    }

    @AfterEach
    void clean() {
        cleanup.truncateAllTables();
    }

    @Test
    void concurrentConfirmationOfSameOrderDeductsOnceAndReturnsSamePayment() throws Exception {
        long productId = product(5);
        points.charge("alice", 10000);
        long orderId = draft("alice", productId, 2);

        var results = together(() -> orders.confirm("alice", orderId), () -> orders.confirm("alice", orderId));

        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(results.get(0).value()).isEqualTo(results.get(1).value());
        assertThat(orders.getDetail("alice", orderId).status()).isEqualTo(OrderStatus.CONFIRMED);
        assertStock(productId, 3);
        assertThat(points.balance("alice").balance()).isEqualTo(8000);
    }

    @Test
    void secondConfirmationWaitsForFirstCommitAndThenReusesItsResult() throws Exception {
        long productId = product(5);
        points.charge("alice", 10000);
        long orderId = draft("alice", productId, 2);
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                OrderInfo result = orders.confirm("alice", orderId);
                changed.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("첫 확정 커밋 대기 시간 초과");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return result;
            }));
            try {
                assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> {
                    secondStarted.countDown();
                    return orders.confirm("alice", orderId);
                });
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(first.get(5, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
        assertStock(productId, 3);
        assertThat(points.balance("alice").balance()).isEqualTo(8000);
    }

    @Test
    void differentBuyersCompeteForStockWithoutPartialPayment() throws Exception {
        long productId = product(5);
        points.charge("alice", 10000);
        points.charge("bob", 10000);
        long first = draft("alice", productId, 4);
        long second = draft("bob", productId, 4);

        var results = together(() -> orders.confirm("alice", first), () -> orders.confirm("bob", second));

        assertOneSuccessAndFailure(results, ProductStockException.class);
        assertThat(results.stream().filter(value -> value.failure() != null).findFirst().orElseThrow().failure())
            .isInstanceOfSatisfying(ProductStockException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductStockException.Reason.INSUFFICIENT_STOCK));
        assertStock(productId, 1);
        assertThat(points.balance("alice").balance() + points.balance("bob").balance()).isEqualTo(16000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE status='CONFIRMED'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE status='DRAFT' AND paid_amount IS NULL AND confirmed_at IS NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void differentOrdersOfSameBuyerCannotOverspendBalance() throws Exception {
        long productId = product(10);
        points.charge("alice", 5000);
        long first = draft("alice", productId, 3);
        long second = draft("alice", productId, 3);

        var results = together(() -> orders.confirm("alice", first), () -> orders.confirm("alice", second));

        assertOneSuccessAndFailure(results, PointsException.class);
        assertThat(results.stream().filter(value -> value.failure() != null).findFirst().orElseThrow().failure())
            .isInstanceOfSatisfying(PointsException.class,
                error -> assertThat(error.getReason()).isEqualTo(PointsException.Reason.INSUFFICIENT_POINTS));
        assertThat(points.balance("alice").balance()).isEqualTo(2000);
        assertStock(productId, 7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE status='DRAFT'", Long.class)).isEqualTo(1);
    }

    @Test
    void chargingAndConfirmingDoNotLoseEitherBalanceChange() throws Exception {
        long productId = product(5);
        points.charge("alice", 2000);
        long orderId = draft("alice", productId, 1);

        var results = together(() -> orders.confirm("alice", orderId), () -> points.charge("alice", 3000));

        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(points.balance("alice").balance()).isEqualTo(4000);
        assertStock(productId, 4);
    }

    @Test
    void settingStockAndConfirmingHaveOneOfTheTwoSerialResults() throws Exception {
        long productId = product(5);
        points.charge("alice", 10000);
        long orderId = draft("alice", productId, 2);

        var results = together(() -> orders.confirm("alice", orderId), () -> products.changeStock(UserRole.ADMIN, productId, 3));

        assertThat(results).allSatisfy(result -> assertThat(result.failure()).isNull());
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, productId)).isIn(1, 3);
        assertThat(points.balance("alice").balance()).isEqualTo(8000);
        assertThat(orders.getDetail("alice", orderId).status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void deletingProductAndConfirmingPreserveAConsistentOrderAndPayment() throws Exception {
        long productId = product(5);
        points.charge("alice", 10000);
        long orderId = draft("alice", productId, 2);

        var results = together(() -> orders.confirm("alice", orderId), () -> products.delete(UserRole.ADMIN, productId));

        assertThat(results.get(1).failure()).isNull();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM product WHERE id=?", Boolean.class, productId)).isTrue();
        if (results.get(0).failure() == null) {
            assertStock(productId, 3);
            assertThat(points.balance("alice").balance()).isEqualTo(8000);
            assertThat(orders.getDetail("alice", orderId).status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(orders.confirm("alice", orderId)).isEqualTo(results.get(0).value());
        } else {
            assertThat(results.get(0).failure()).isInstanceOf(ProductQueryException.class);
            assertStock(productId, 5);
            assertThat(points.balance("alice").balance()).isEqualTo(10000);
            assertThat(orders.getDetail("alice", orderId).status()).isEqualTo(OrderStatus.DRAFT);
        }
    }

    private long product(int stock) {
        return products.create(UserRole.ADMIN, brandId, "상품", 1000, stock).productId();
    }

    private long draft(String user, long productId, int quantity) {
        return orders.create(user, List.of(new OrderQuantities.Item(productId, quantity))).orderId();
    }

    private void assertStock(long id, int quantity) {
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, id)).isEqualTo(quantity);
    }

    private void assertOneSuccessAndFailure(List<Outcome> outcomes, Class<? extends Exception> failureType) {
        assertThat(outcomes.stream().filter(value -> value.failure() == null).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(value -> value.failure() != null).map(Outcome::failure).toList())
            .singleElement().isInstanceOf(failureType);
    }

    private List<Outcome> together(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(() -> invoke(first, ready, start));
            var right = pool.submit(() -> invoke(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        }
    }

    private Outcome invoke(Callable<?> action, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청 시작 시간 초과");
            }
            return new Outcome(action.call(), null);
        } catch (Exception exception) {
            return new Outcome(null, exception);
        }
    }

    private record Outcome(Object value, Exception failure) {
    }
}
