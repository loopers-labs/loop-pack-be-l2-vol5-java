package com.loopers.application.brand;

import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderService;
import com.loopers.application.product.AdminProductInfo;
import com.loopers.application.product.AdminProductService;
import com.loopers.application.product.ProductQueryException;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.interfaces.api.commerce.CommerceErrors;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
class BrandRemovalConcurrencyTest {
    @Autowired private BrandRepository brands;
    @Autowired private ProductRepository products;
    @Autowired private AdminBrandService removal;
    @Autowired private AdminProductService adminProducts;
    @Autowired private OrderService orders;
    @Autowired private FixtureUserInitializer initializer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    @Autowired private CommerceErrors errors;

    private long brandId;
    private long firstProductId;
    private long secondProductId;
    private long otherBrandId;
    private List<OrderQuantities.Item> orderItems;

    @BeforeEach
    void setUp() {
        initializer.initialize();
        Brand target = brands.save(new Brand("경합 삭제 대상"));
        brandId = target.getId();
        firstProductId = products.save(new Product(target, "첫 상품", 1000, 5)).getId();
        secondProductId = products.save(new Product(target, "둘째 상품", 2000, 7)).getId();
        Brand other = brands.save(new Brand("유지 대상"));
        otherBrandId = other.getId();
        products.save(new Product(other, "다른 상품", 3000, 9));
        jdbc.update("UPDATE user SET point_balance = 10000 WHERE id = 1");
        // 역순 입력도 기존 서비스의 ID 정렬·공통 잠금 경로를 통과한다.
        orderItems = List.of(new OrderQuantities.Item(secondProductId, 1),
            new OrderQuantities.Item(firstProductId, 2));
    }

    @AfterEach
    void tearDown() {
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("W3-BRAND-07 순차 경계: 삭제 커밋 뒤 주문 생성은 거절되며 주문·차감을 남기지 않는다.")
    void deletionBeforeOrderCreationRejectsNewOrder() {
        var before = targetProductRows();
        var preserved = preservedState();

        SequentialResult result = runSequentially(this::deleteBrand, () -> orders.create("alice", orderItems));

        assertSucceeded(result.first());
        assertRejected(result.second(), ProductQueryException.Reason.PRODUCT_NOT_FOUND);
        assertDeleted(2);
        assertProductChanges(before, Map.of());
        assertThat(jdbc.queryForList("SELECT * FROM `order`")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM order_item")).isEmpty();
        assertBalance(10000);
        assertThat(preservedState()).isEqualTo(preserved);
        assertThat(databaseState()).isEqualTo(result.afterFirstCommit());
    }

    @Test
    @DisplayName("W3-BRAND-07 순차 경계: 주문 생성 뒤 삭제는 DRAFT를 보존하고 이후 최초 확정은 거절된다.")
    void orderCreationBeforeDeletionPreservesDraftButCannotConfirm() {
        var before = targetProductRows();
        var preserved = preservedState();

        SequentialResult result = runSequentially(() -> orders.create("alice", orderItems), this::deleteBrand);

        assertSucceeded(result.first());
        assertSucceeded(result.second());
        OrderInfo created = (OrderInfo) result.first().value();
        assertThat(created.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(created.totalAmount()).isEqualTo(4000L);
        assertThat(created.paidAmount()).isNull();
        assertThat(created.confirmedAt()).isNull();
        assertStoredOrder(created);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class)).isEqualTo(2L);
        assertDeleted(2);
        assertProductChanges(before, Map.of());
        assertBalance(10000);
        assertThat(preservedState()).isEqualTo(preserved);
        assertOrderAndPaymentRowsPreserved(result.afterFirstCommit());
        var afterRace = databaseState();

        assertThatThrownBy(() -> orders.confirm("alice", created.orderId()))
            .isExactlyInstanceOf(ProductQueryException.class)
            .satisfies(error -> assertThat(((ProductQueryException) error).getReason())
                .isEqualTo(ProductQueryException.Reason.PRODUCT_NOT_FOUND));

        assertThat(databaseState()).isEqualTo(afterRace);
    }

    @Test
    @DisplayName("W3-BRAND-07 순차 경계: 삭제 커밋 뒤 DRAFT 최초 확정은 거절되어 주문·재고·잔액을 보존한다.")
    void deletionBeforeFirstConfirmationPreservesDraft() {
        OrderInfo draft = orders.create("alice", orderItems);
        var before = targetProductRows();
        var beforeOrders = jdbc.queryForList("SELECT * FROM `order` ORDER BY id");
        var beforeItems = jdbc.queryForList("SELECT * FROM order_item ORDER BY id");
        var beforeUsers = jdbc.queryForList("SELECT * FROM user ORDER BY id");
        var preserved = preservedState();

        SequentialResult result = runSequentially(this::deleteBrand, () -> orders.confirm("alice", draft.orderId()));

        assertSucceeded(result.first());
        assertRejected(result.second(), ProductQueryException.Reason.PRODUCT_NOT_FOUND);
        assertStoredOrder(draft);
        assertThat(jdbc.queryForList("SELECT * FROM `order` ORDER BY id")).isEqualTo(beforeOrders);
        assertThat(jdbc.queryForList("SELECT * FROM order_item ORDER BY id")).isEqualTo(beforeItems);
        assertThat(jdbc.queryForList("SELECT * FROM user ORDER BY id")).isEqualTo(beforeUsers);
        assertDeleted(2);
        assertProductChanges(before, Map.of());
        assertThat(preservedState()).isEqualTo(preserved);
        assertThat(databaseState()).isEqualTo(result.afterFirstCommit());
    }

    @Test
    @DisplayName("W3-BRAND-07 순차 경계: 최초 확정 뒤 삭제는 확정·결제 결과와 한 번의 재고·포인트 차감을 보존한다.")
    void firstConfirmationBeforeDeletionPreservesPaymentAndDeduction() {
        OrderInfo draft = orders.create("alice", orderItems);
        var before = targetProductRows();
        var beforeItems = jdbc.queryForList("SELECT * FROM order_item ORDER BY id");
        var preserved = preservedState();

        SequentialResult result = runSequentially(() -> orders.confirm("alice", draft.orderId()), this::deleteBrand);

        assertSucceeded(result.first());
        assertSucceeded(result.second());
        OrderInfo confirmed = (OrderInfo) result.first().value();
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.items()).isEqualTo(draft.items());
        assertThat(confirmed.totalAmount()).isEqualTo(4000L);
        assertThat(confirmed.paidAmount()).isEqualTo(4000L);
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertStoredOrder(confirmed);
        assertThat(jdbc.queryForList("SELECT * FROM order_item ORDER BY id")).isEqualTo(beforeItems);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Long.class)).isEqualTo(1L);
        assertDeleted(2);
        assertProductChanges(before, Map.of(firstProductId, 3, secondProductId, 6));
        assertBalance(6000);
        assertThat(preservedState()).isEqualTo(preserved);
        assertOrderAndPaymentRowsPreserved(result.afterFirstCommit());
    }

    @Test
    @DisplayName("W3-BRAND-08 순차 경계: 삭제 커밋 뒤 상품 등록은 거절되며 상품을 추가하지 않는다.")
    void deletionBeforeProductRegistrationRejectsNewProduct() {
        var before = targetProductRows();
        var preserved = preservedState();

        SequentialResult result = runSequentially(this::deleteBrand,
            () -> adminProducts.create(UserRole.ADMIN, brandId, "등록 시도 상품", 500, 4));

        assertSucceeded(result.first());
        assertRejected(result.second(), ProductQueryException.Reason.BRAND_NOT_FOUND);
        assertDeleted(2);
        assertProductChanges(before, Map.of());
        assertThat(jdbc.queryForList("SELECT * FROM `order`")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM order_item")).isEmpty();
        assertBalance(10000);
        assertThat(preservedState()).isEqualTo(preserved);
        assertThat(databaseState()).isEqualTo(result.afterFirstCommit());
    }

    @Test
    @DisplayName("W3-BRAND-08 순차 경계: 상품 등록 커밋 뒤 브랜드 삭제는 새 상품까지 함께 삭제한다.")
    void productRegistrationBeforeDeletionIncludesNewProduct() {
        var before = targetProductRows();
        var preserved = preservedState();

        SequentialResult result = runSequentially(
            () -> adminProducts.create(UserRole.ADMIN, brandId, "먼저 등록한 상품", 500, 4), this::deleteBrand);

        assertSucceeded(result.first());
        assertSucceeded(result.second());
        AdminProductInfo created = (AdminProductInfo) result.first().value();
        assertThat(created.deletedAt()).isNull();
        assertDeleted(3);
        assertProductChanges(before, Map.of());
        var createdRow = jdbc.queryForMap("SELECT * FROM product WHERE id = ?", created.productId());
        assertThat(createdRow.get("name")).isEqualTo("먼저 등록한 상품");
        assertThat(((Number) createdRow.get("brand_id")).longValue()).isEqualTo(brandId);
        assertThat(((Number) createdRow.get("price")).longValue()).isEqualTo(500L);
        assertThat(((Number) createdRow.get("stock_quantity")).intValue()).isEqualTo(4);
        assertThat(createdRow.get("created_at")).isNotNull();
        assertThat(createdRow.get("deleted_at")).isEqualTo(deletionTime());
        assertThat(createdRow.get("updated_at")).isEqualTo(createdRow.get("deleted_at"));
        assertThat(jdbc.queryForList("SELECT * FROM `order`")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM order_item")).isEmpty();
        assertBalance(10000);
        assertThat(preservedState()).isEqualTo(preserved);
        assertOrderAndPaymentRowsPreserved(result.afterFirstCommit());
    }

    @Test
    @DisplayName("W3-BRAND-07 경합: 삭제·주문 생성의 worker 시작만 맞추고 각 결과의 DRAFT·재고·잔액을 검증한다.")
    void concurrentDeletionAndOrderCreationHaveOnlyValidCommittedResults() throws Exception {
        var beforeProducts = targetProductRows();
        var beforeUsers = jdbc.queryForList("SELECT * FROM user ORDER BY id");
        var preserved = preservedState();

        var result = runTogether(this::deleteBrand, () -> orders.create("alice", orderItems));

        assertSucceeded(result.get(0));
        assertDeleted(2);
        assertProductChanges(beforeProducts, Map.of());
        assertThat(jdbc.queryForList("SELECT * FROM user ORDER BY id")).isEqualTo(beforeUsers);
        assertThat(preservedState()).isEqualTo(preserved);
        Outcome creation = result.get(1);
        if (creation.failure() == null) {
            OrderInfo draft = (OrderInfo) creation.value();
            assertThat(draft.status()).isEqualTo(OrderStatus.DRAFT);
            assertThat(draft.totalAmount()).isEqualTo(4000L);
            assertThat(draft.paidAmount()).isNull();
            assertThat(draft.confirmedAt()).isNull();
            assertStoredOrder(draft);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Long.class)).isEqualTo(1L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_item", Long.class)).isEqualTo(2L);
            var afterRace = databaseState();

            assertThatThrownBy(() -> orders.confirm("alice", draft.orderId()))
                .isExactlyInstanceOf(ProductQueryException.class)
                .satisfies(error -> assertThat(((ProductQueryException) error).getReason())
                    .isEqualTo(ProductQueryException.Reason.PRODUCT_NOT_FOUND));

            assertThat(databaseState()).isEqualTo(afterRace);
        } else {
            assertRejected(creation, ProductQueryException.Reason.PRODUCT_NOT_FOUND);
            assertThat(jdbc.queryForList("SELECT * FROM `order`")).isEmpty();
            assertThat(jdbc.queryForList("SELECT * FROM order_item")).isEmpty();
        }
    }

    @Test
    @DisplayName("W3-BRAND-07 경합: 삭제·최초 확정의 worker 시작만 맞추고 성공 차감 또는 DRAFT 무변경을 검증한다.")
    void concurrentDeletionAndFirstConfirmationHaveOnlyValidCommittedResults() throws Exception {
        OrderInfo draft = orders.create("alice", orderItems);
        var beforeProducts = targetProductRows();
        var beforeOrders = jdbc.queryForList("SELECT * FROM `order` ORDER BY id");
        var beforeItems = jdbc.queryForList("SELECT * FROM order_item ORDER BY id");
        var beforeUsers = jdbc.queryForList("SELECT * FROM user ORDER BY id");
        var preserved = preservedState();

        var result = runTogether(this::deleteBrand, () -> orders.confirm("alice", draft.orderId()));

        assertSucceeded(result.get(0));
        assertDeleted(2);
        assertThat(jdbc.queryForList("SELECT * FROM order_item ORDER BY id")).isEqualTo(beforeItems);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM `order`", Long.class)).isEqualTo(1L);
        assertThat(preservedState()).isEqualTo(preserved);
        Outcome confirmation = result.get(1);
        if (confirmation.failure() == null) {
            OrderInfo confirmed = (OrderInfo) confirmation.value();
            assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(confirmed.items()).isEqualTo(draft.items());
            assertThat(confirmed.totalAmount()).isEqualTo(4000L);
            assertThat(confirmed.paidAmount()).isEqualTo(4000L);
            assertThat(confirmed.confirmedAt()).isNotNull();
            assertStoredOrder(confirmed);
            assertProductChanges(beforeProducts, Map.of(firstProductId, 3, secondProductId, 6));
            assertBalance(6000);
        } else {
            assertRejected(confirmation, ProductQueryException.Reason.PRODUCT_NOT_FOUND);
            assertStoredOrder(draft);
            assertThat(jdbc.queryForList("SELECT * FROM `order` ORDER BY id")).isEqualTo(beforeOrders);
            assertThat(jdbc.queryForList("SELECT * FROM user ORDER BY id")).isEqualTo(beforeUsers);
            assertProductChanges(beforeProducts, Map.of());
            assertBalance(10000);
        }
    }

    @Test
    @DisplayName("W3-BRAND-08 경합: 삭제·등록의 worker 시작만 맞추고 새 상품의 동반 삭제 또는 등록 거절을 검증한다.")
    void concurrentDeletionAndProductRegistrationLeaveNoActiveProductUnderDeletedBrand() throws Exception {
        var beforeProducts = targetProductRows();
        var beforeUsers = jdbc.queryForList("SELECT * FROM user ORDER BY id");
        var preserved = preservedState();

        var result = runTogether(this::deleteBrand,
            () -> adminProducts.create(UserRole.ADMIN, brandId, "경합 등록 상품", 500, 4));

        assertSucceeded(result.get(0));
        assertProductChanges(beforeProducts, Map.of());
        assertThat(jdbc.queryForList("SELECT * FROM user ORDER BY id")).isEqualTo(beforeUsers);
        assertThat(jdbc.queryForList("SELECT * FROM `order`")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM order_item")).isEmpty();
        assertThat(preservedState()).isEqualTo(preserved);
        Outcome registration = result.get(1);
        if (registration.failure() == null) {
            AdminProductInfo created = (AdminProductInfo) registration.value();
            assertThat(created.deletedAt()).isNull();
            assertDeleted(3);
            var stored = jdbc.queryForMap("SELECT * FROM product WHERE id = ?", created.productId());
            assertThat(stored.get("name")).isEqualTo("경합 등록 상품");
            assertThat(((Number) stored.get("brand_id")).longValue()).isEqualTo(brandId);
            assertThat(((Number) stored.get("price")).longValue()).isEqualTo(500L);
            assertThat(((Number) stored.get("stock_quantity")).intValue()).isEqualTo(4);
            assertThat(stored.get("created_at")).isNotNull();
            assertThat(stored.get("deleted_at")).isEqualTo(deletionTime());
            assertThat(stored.get("updated_at")).isEqualTo(stored.get("deleted_at"));
        } else {
            assertRejected(registration, ProductQueryException.Reason.BRAND_NOT_FOUND);
            assertDeleted(2);
        }
    }

    private Object deleteBrand() {
        removal.delete(UserRole.ADMIN, brandId);
        return null;
    }

    private SequentialResult runSequentially(Callable<?> first, Callable<?> second) {
        // 서비스 프록시 반환 후 커밋된 상태를 읽는다. 이 helper는 경합·락 대기 증거가 아니다.
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        Outcome firstOutcome = invoke(first);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var afterFirstCommit = databaseState();
        Outcome secondOutcome = invoke(second);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return new SequentialResult(firstOutcome, secondOutcome, afterFirstCommit);
    }

    private Outcome invoke(Callable<?> action) {
        try {
            return new Outcome(action.call(), null);
        } catch (Exception exception) {
            return new Outcome(null, exception);
        }
    }

    private List<Outcome> runTogether(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        Future<Outcome> left = null;
        Future<Outcome> right = null;
        try {
            // worker 시작만 맞춘다. 서비스 진입 뒤 잠금·검증·커밋에는 테스트 개입이 없다.
            left = pool.submit(() -> invokeAfterStart(first, ready, start));
            right = pool.submit(() -> invokeAfterStart(second, ready, start));
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

    private Outcome invokeAfterStart(Callable<?> action, CountDownLatch ready, CountDownLatch start) {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        ready.countDown();
        await(start);
        Outcome result = invoke(action);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return result;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청 시작 대기 시간 초과");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private void assertSucceeded(Outcome outcome) {
        assertThat(outcome.failure()).as("업무 실패·락 타임아웃·교착 없이 성공").isNull();
    }

    private void assertRejected(Outcome outcome, ProductQueryException.Reason reason) {
        assertThat(outcome.value()).isNull();
        assertThat(outcome.failure()).isExactlyInstanceOf(ProductQueryException.class);
        assertThat(((ProductQueryException) outcome.failure()).getReason()).isEqualTo(reason);
        String code = reason == ProductQueryException.Reason.BRAND_NOT_FOUND ? "BRAND_NOT_FOUND" : "PRODUCT_NOT_FOUND";
        assertThat(errors.translate(outcome.failure()).status()).isEqualTo(404);
        assertThat(errors.translate(outcome.failure()).code()).isEqualTo(code);
    }

    private void assertDeleted(int expectedProducts) {
        assertThat(deletionTime()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE brand_id = ?", Long.class, brandId))
            .isEqualTo((long) expectedProducts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN brand b ON b.id = p.brand_id "
            + "WHERE b.id = ? AND p.deleted_at = b.deleted_at AND p.updated_at = p.deleted_at", Long.class, brandId))
            .isEqualTo((long) expectedProducts);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product p JOIN brand b ON b.id = p.brand_id "
            + "WHERE b.deleted_at IS NOT NULL AND p.deleted_at IS NULL", Long.class)).isZero();
    }

    private Object deletionTime() {
        return jdbc.queryForMap("SELECT deleted_at FROM brand WHERE id = ?", brandId).get("deleted_at");
    }

    private void assertProductChanges(List<Map<String, Object>> before, Map<Long, Integer> expectedStocks) {
        for (Map<String, Object> row : before) {
            long productId = ((Number) row.get("id")).longValue();
            var expected = new LinkedHashMap<>(row);
            expected.put("deleted_at", deletionTime());
            expected.put("updated_at", deletionTime());
            if (expectedStocks.containsKey(productId)) {
                expected.put("stock_quantity", expectedStocks.get(productId));
            }
            assertThat(jdbc.queryForMap("SELECT * FROM product WHERE id = ?", productId)).isEqualTo(expected);
        }
    }

    private void assertBalance(long balance) {
        assertThat(jdbc.queryForObject("SELECT point_balance FROM user WHERE id = 1", Long.class)).isEqualTo(balance);
    }

    private void assertStoredOrder(OrderInfo expected) {
        // JPA의 UTC 정규화 후에도 생성·확정 시각이 같은 순간인지 모든 응답 필드와 함께 비교한다.
        assertThat(orders.getDetail("alice", expected.orderId())).usingRecursiveComparison()
            .withComparatorForType(Comparator.comparing(ZonedDateTime::toInstant), ZonedDateTime.class)
            .isEqualTo(expected);
    }

    private void assertOrderAndPaymentRowsPreserved(Map<String, List<Map<String, Object>>> before) {
        var after = databaseState();
        assertThat(after.get("orders")).isEqualTo(before.get("orders"));
        assertThat(after.get("items")).isEqualTo(before.get("items"));
        assertThat(after.get("users")).isEqualTo(before.get("users"));
        assertThat(after.get("likes")).isEqualTo(before.get("likes"));
    }

    private List<Map<String, Object>> targetProductRows() {
        return jdbc.queryForList("SELECT * FROM product WHERE brand_id = ? ORDER BY id", brandId);
    }

    private Map<String, List<Map<String, Object>>> preservedState() {
        return Map.of(
            "otherBrand", jdbc.queryForList("SELECT * FROM brand WHERE id = ?", otherBrandId),
            "otherProducts", jdbc.queryForList("SELECT * FROM product WHERE brand_id = ? ORDER BY id", otherBrandId),
            "otherUsers", jdbc.queryForList("SELECT * FROM user WHERE id <> 1 ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id")
        );
    }

    private Map<String, List<Map<String, Object>>> databaseState() {
        return Map.of(
            "brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id")
        );
    }

    private record Outcome(Object value, Exception failure) {
    }

    private record SequentialResult(Outcome first, Outcome second,
                                    Map<String, List<Map<String, Object>>> afterFirstCommit) {
    }
}
