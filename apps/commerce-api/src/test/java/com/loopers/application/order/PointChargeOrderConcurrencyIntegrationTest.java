package com.loopers.application.order;

import com.loopers.application.product.AdminProductService;
import com.loopers.application.user.BalanceInfo;
import com.loopers.application.user.PointService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductStockException;
import com.loopers.domain.user.PointsException;
import com.loopers.domain.user.UserRole;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PointChargeOrderConcurrencyIntegrationTest {
    @Autowired private OrderService orders;
    @Autowired private PointService points;
    @Autowired private AdminProductService products;
    @Autowired private BrandRepository brands;
    @Autowired private FixtureUserInitializer users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private boolean workersTerminated = true;

    @BeforeEach
    void setUp() {
        users.initialize();
    }

    @AfterEach
    void tearDown() {
        assertThat(workersTerminated).as("worker가 종료되지 않으면 DB를 정리하지 않는다").isTrue();
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("W3-CHARGE-ORDER-01: 잔액 10000에 충전 2000과 결제 7000이 경쟁하면 모두 성공하고 5000이 남는다")
    void chargingAndConfirmingPreserveBothChangesAndUnrelatedRows() throws Exception {
        points.charge("alice", 10000);
        points.charge("bob", 7000);
        long firstBrandId = brands.save(new Brand("주문 첫 브랜드")).getId();
        long firstProductId = products.create(UserRole.ADMIN, firstBrandId, "첫 상품", 2000, 5).productId();
        long secondBrandId = brands.save(new Brand("주문 두 번째 브랜드")).getId();
        long secondProductId = products.create(UserRole.ADMIN, secondBrandId, "두 번째 상품", 3000, 4).productId();
        long otherBrandId = brands.save(new Brand("다른 구매자의 브랜드")).getId();
        long otherProductId = products.create(UserRole.ADMIN, otherBrandId, "다른 구매자의 상품", 1500, 9).productId();
        long otherOrderId = orders.create("bob", List.of(new OrderQuantities.Item(otherProductId, 1))).orderId();
        OrderInfo otherDraft = orders.getDetail("bob", otherOrderId);
        long orderId = orders.create("alice", List.of(new OrderQuantities.Item(firstProductId, 2),
            new OrderQuantities.Item(secondProductId, 1))).orderId();
        OrderInfo draft = orders.getDetail("alice", orderId);
        assertThat(draft.status()).isEqualTo(OrderStatus.DRAFT);
        assertThat(draft.totalAmount()).isEqualTo(7000);
        assertThat(draft.paidAmount()).isNull();
        assertThat(draft.confirmedAt()).isNull();
        assertThat(draft.items()).containsExactlyInAnyOrder(
            new OrderInfo.ItemInfo(firstProductId, "첫 상품", 2000, 2, 4000),
            new OrderInfo.ItemInfo(secondProductId, "두 번째 상품", 3000, 1, 3000));
        assertThat(AopUtils.isAopProxy(points)).as("실제 포인트 서비스 프록시 사용").isTrue();
        assertThat(AopUtils.isAopProxy(orders)).as("실제 주문 서비스 프록시 사용").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var before = storedState();
        assertThat(row(before.get("users"), 1).get("point_balance")).isEqualTo(10000L);
        assertThat(row(before.get("users"), 2).get("point_balance")).isEqualTo(7000L);
        assertThat(row(before.get("products"), firstProductId).get("stock_quantity")).isEqualTo(5);
        assertThat(row(before.get("products"), secondProductId).get("stock_quantity")).isEqualTo(4);

        ConcurrentResults results = chargeAndConfirmTogether(orderId);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        List<Outcome<?>> outcomes = List.of(results.charge(), results.confirmation());
        assertThat(outcomes).extracting(Outcome::operation).containsExactly("charge", "confirm");
        long successes = outcomes.stream().filter(outcome -> outcome.failure() == null).count();
        long businessRejections = outcomes.stream().filter(this::isBusinessRejection).count();
        var unexpectedErrors = outcomes.stream()
            .filter(outcome -> outcome.failure() != null && !isBusinessRejection(outcome)).toList();
        assertThat(unexpectedErrors).as("기술 오류·예상 밖 오류를 성공이나 업무 거절로 숨기지 않는다").isEmpty();
        assertThat(businessRejections).as("재고와 초기 잔액이 충분하므로 두 요청 모두 허용된다").isZero();
        assertThat(successes).isEqualTo(2);
        assertThat(successes + businessRejections + unexpectedErrors.size()).isEqualTo(2);
        assertThat(results.charge().value()).isNotNull();
        // 충전이 먼저면 12000, 결제가 먼저면 5000을 반환한다. 최종 잔액과 응답 시점의 잔액은 구분한다.
        assertThat(results.charge().value().balance()).isIn(12000L, 5000L);
        OrderInfo confirmed = results.confirmation().value();
        assertThat(confirmed).isNotNull();
        assertThat(confirmed.orderId()).isEqualTo(orderId);
        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(confirmed.totalAmount()).isEqualTo(7000);
        assertThat(confirmed.paidAmount()).isEqualTo(7000L);
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(confirmed.items()).isEqualTo(draft.items());
        assertThat(orders.getDetail("alice", orderId)).isEqualTo(confirmed);

        var after = storedState();
        assertThat(after.get("orders").stream().filter(order -> "CONFIRMED".equals(order.get("status")))
            .map(order -> ((Number) order.get("id")).longValue()).toList()).containsExactly(orderId);
        var savedOrder = row(after.get("orders"), orderId);
        assertThat(savedOrder.get("user_id")).isEqualTo(1L);
        assertThat(savedOrder.get("status")).isEqualTo("CONFIRMED");
        assertThat(savedOrder.get("total_amount")).isEqualTo(7000L);
        assertThat(savedOrder.get("paid_amount")).isEqualTo(7000L);
        assertThat(savedOrder.get("confirmed_at")).isNotNull();
        List<Long> orderedProductIds = List.of(firstProductId, secondProductId);
        for (long productId : orderedProductIds) {
            int soldQuantity = after.get("items").stream()
                .filter(item -> ((Number) item.get("order_id")).longValue() == orderId)
                .filter(item -> ((Number) item.get("product_id")).longValue() == productId)
                .mapToInt(item -> ((Number) item.get("quantity")).intValue()).sum();
            int initialStock = ((Number) row(before.get("products"), productId).get("stock_quantity")).intValue();
            int finalStock = ((Number) row(after.get("products"), productId).get("stock_quantity")).intValue();
            assertThat(soldQuantity).isEqualTo(productId == firstProductId ? 2 : 1);
            assertThat(initialStock - soldQuantity).as("상품 %s의 확정 품목 수량만 차감", productId).isEqualTo(finalStock);
            assertThat(finalStock).isEqualTo(3);
        }
        long storedPaid = ((Number) savedOrder.get("paid_amount")).longValue();
        long initialBalance = ((Number) row(before.get("users"), 1).get("point_balance")).longValue();
        long finalBalance = ((Number) row(after.get("users"), 1).get("point_balance")).longValue();
        long successfulCharge = results.charge().failure() == null ? 2000 : 0;
        assertThat(successfulCharge).isEqualTo(2000);
        assertThat(finalBalance).isEqualTo(5000);
        assertThat(initialBalance + successfulCharge - storedPaid).isEqualTo(finalBalance);
        assertThat(points.balance("alice").balance()).isEqualTo(finalBalance);
        assertPreservedRows(before, after, orderId, orderedProductIds);
        assertThat(row(after.get("users"), 2)).as("다른 구매자의 잔액과 전체 행 보존")
            .isEqualTo(row(before.get("users"), 2));
        assertThat(orders.getDetail("bob", otherOrderId)).isEqualTo(otherDraft);
    }

    private ConcurrentResults chargeAndConfirmTogether(long orderId) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<Outcome<BalanceInfo>> charge = null;
        Future<Outcome<OrderInfo>> confirmation = null;
        workersTerminated = false;
        try {
            charge = executor.submit(() -> invoke("charge", () -> points.charge("alice", 2000), ready, start));
            confirmation = executor.submit(() -> invoke("confirm", () -> orders.confirm("alice", orderId), ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("충전·확정 worker가 모두 시작 신호를 기다림").isTrue();
            start.countDown();
            return new ConcurrentResults(charge.get(10, TimeUnit.SECONDS), confirmation.get(10, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            if (charge != null) {
                charge.cancel(true);
            }
            if (confirmation != null) {
                confirmation.cancel(true);
            }
            executor.shutdownNow();
            try {
                workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            }
            assertThat(workersTerminated).as("최종 DB 조회·정리 전에 모든 worker 종료").isTrue();
        }
    }

    private <T> Outcome<T> invoke(String operation, Callable<T> action, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("충전·확정 시작 시간 초과");
            }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // 외부 트랜잭션 없이 각 서비스 프록시가 커밋까지 마친 정상 반환만 성공으로 기록한다.
            return new Outcome<>(operation, action.call(), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome<>(operation, null, exception);
        } catch (Exception exception) {
            return new Outcome<>(operation, null, exception);
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    private boolean isBusinessRejection(Outcome<?> outcome) {
        return outcome.failure() instanceof PointsException pointsFailure
            && pointsFailure.getReason() == PointsException.Reason.INSUFFICIENT_POINTS
            || outcome.failure() instanceof ProductStockException stockFailure
            && stockFailure.getReason() == ProductStockException.Reason.INSUFFICIENT_STOCK;
    }

    private void assertPreservedRows(Map<String, List<Map<String, Object>>> before,
                                     Map<String, List<Map<String, Object>>> after,
                                     long orderId, List<Long> orderedProductIds) {
        for (String table : before.keySet()) {
            assertThat(after.get(table)).as("%s 행 ID와 행 수 보존", table)
                .extracting(value -> value.get("id"))
                .containsExactlyElementsOf(before.get(table).stream().map(value -> value.get("id")).toList());
            for (var original : before.get(table)) {
                long id = ((Number) original.get("id")).longValue();
                var saved = row(after.get(table), id);
                List<String> changedColumns = switch (table) {
                    case "orders" -> id == orderId
                        ? List.of("status", "paid_amount", "confirmed_at", "updated_at") : List.of();
                    case "products" -> orderedProductIds.contains(id)
                        ? List.of("stock_quantity", "updated_at") : List.of();
                    case "users" -> id == 1 ? List.of("point_balance", "updated_at") : List.of();
                    default -> List.of();
                };
                assertThat(without(saved, changedColumns)).as("%s id=%s 허용된 변경 외 값 보존", table, id)
                    .isEqualTo(without(original, changedColumns));
            }
        }
    }

    private Map<String, Object> without(Map<String, Object> row, List<String> columns) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        columns.forEach(copy::remove);
        return copy;
    }

    private Map<String, Object> row(List<Map<String, Object>> rows, long id) {
        return rows.stream().filter(value -> ((Number) value.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    private Map<String, List<Map<String, Object>>> storedState() {
        return Map.of("orders", jdbc.queryForList("SELECT * FROM `order` ORDER BY id"),
            "items", jdbc.queryForList("SELECT * FROM order_item ORDER BY id"),
            "products", jdbc.queryForList("SELECT * FROM product ORDER BY id"),
            "users", jdbc.queryForList("SELECT * FROM user ORDER BY id"),
            "brands", jdbc.queryForList("SELECT * FROM brand ORDER BY id"),
            "likes", jdbc.queryForList("SELECT * FROM `like` ORDER BY id"));
    }

    private record Outcome<T>(String operation, T value, Exception failure) {
    }

    private record ConcurrentResults(Outcome<BalanceInfo> charge, Outcome<OrderInfo> confirmation) {
    }
}
