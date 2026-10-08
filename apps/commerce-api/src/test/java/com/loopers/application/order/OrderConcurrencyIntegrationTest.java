package com.loopers.application.order;

import com.loopers.application.product.AdminProductService;
import com.loopers.application.product.ProductQueryException;
import com.loopers.application.user.PointService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.PointsException;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.domain.user.UserRole;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    @DisplayName("W3 재확정 거절: 동일 주문의 동시 확정은 한 번만 성공하고 다른 요청은 거절된다.")
    void concurrentConfirmationOfSameOrderSucceedsOnceAndRejectsDuplicate() throws Exception {
        long productId = product(5);
        points.charge("alice", 10000);
        long orderId = draft("alice", productId, 2);
        OrderInfo draft = orders.getDetail("alice", orderId);
        var originalItems = jdbc.queryForList("SELECT * FROM order_item ORDER BY id");

        var results = together(() -> orders.confirm("alice", orderId), () -> orders.confirm("alice", orderId));

        assertOneSuccessAndFailure(results, OrderException.class);
        assertThat(results.stream().filter(result -> result.failure() != null).findFirst().orElseThrow().failure())
            .isExactlyInstanceOf(OrderException.class)
            .satisfies(error -> assertThat(((OrderException) error).getReason())
                .isEqualTo(OrderException.Reason.ORDER_ALREADY_CONFIRMED));
        OrderInfo confirmed = (OrderInfo) results.stream().filter(result -> result.failure() == null)
            .findFirst().orElseThrow().value();
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.items()).isEqualTo(draft.items());
        assertThat(confirmed.totalAmount()).isEqualTo(draft.totalAmount());
        assertThat(confirmed.paidAmount()).isEqualTo(2000L);
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(orders.getDetail("alice", orderId)).isEqualTo(confirmed);
        assertThat(jdbc.queryForList("SELECT * FROM order_item ORDER BY id")).isEqualTo(originalItems);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order` WHERE status='CONFIRMED'", Long.class)).isEqualTo(1L);
        assertStock(productId, 3);
        assertThat(points.balance("alice").balance()).isEqualTo(8000);
    }

    @Test
    @DisplayName("W3 순차 경계: 확정 커밋 후 재요청은 거절하고 최초 확정 시각·스냅샷·전체 저장값을 보존한다.")
    void confirmationRetryAfterCommitIsRejectedWithoutChangingFirstResult() {
        long productId = product(5);
        points.charge("alice", 10000);
        long orderId = draft("alice", productId, 2);
        OrderInfo confirmed = orders.confirm("alice", orderId);
        var beforeRetry = storedState();

        // 잠금 내부의 테스트 장벽은 제거한다. 실제 경합은 위의 시작만 맞춘 테스트가 담당한다.
        assertAlreadyConfirmed(orderId);

        assertThat(orders.getDetail("alice", orderId)).isEqualTo(confirmed);
        assertStock(productId, 3);
        assertThat(points.balance("alice").balance()).isEqualTo(8000);
        assertThat(storedState()).isEqualTo(beforeRetry);
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
            assertThat(orders.getDetail("alice", orderId)).isEqualTo(results.get(0).value());
            var beforeRetry = storedState();

            assertAlreadyConfirmed(orderId);

            assertThat(storedState()).isEqualTo(beforeRetry);
        } else {
            assertThat(results.get(0).failure()).isInstanceOfSatisfying(ProductQueryException.class,
                error -> assertThat(error.getReason()).isEqualTo(ProductQueryException.Reason.PRODUCT_NOT_FOUND));
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

    private void assertAlreadyConfirmed(long orderId) {
        assertThatThrownBy(() -> orders.confirm("alice", orderId))
            .isExactlyInstanceOf(OrderException.class)
            .satisfies(error -> assertThat(((OrderException) error).getReason())
                .isEqualTo(OrderException.Reason.ORDER_ALREADY_CONFIRMED));
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of(
            "brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id")
        );
    }

    private void assertOneSuccessAndFailure(List<Outcome> outcomes, Class<? extends Exception> failureType) {
        assertThat(outcomes.stream().filter(value -> value.failure() == null).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(value -> value.failure() != null).map(Outcome::failure).toList())
            .singleElement().isInstanceOf(failureType);
    }

    private List<Outcome> together(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Future<Outcome> left = null;
        Future<Outcome> right = null;
        try {
            left = pool.submit(() -> invoke(first, ready, start));
            right = pool.submit(() -> invoke(second, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            if (left != null) {
                left.cancel(true);
            }
            if (right != null) {
                right.cancel(true);
            }
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).as("DB 정리 전에 모든 worker 종료").isTrue();
        }
    }

    private Outcome invoke(Callable<?> action, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청 시작 시간 초과");
            }
            return new Outcome(action.call(), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome(null, exception);
        } catch (Exception exception) {
            return new Outcome(null, exception);
        }
    }

    private record Outcome(Object value, Exception failure) {
    }
}
