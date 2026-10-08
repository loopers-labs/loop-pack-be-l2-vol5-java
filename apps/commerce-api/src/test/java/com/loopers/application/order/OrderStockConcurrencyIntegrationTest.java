package com.loopers.application.order;

import com.loopers.application.product.AdminProductService;
import com.loopers.application.user.PointService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.ProductStockException;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderStockConcurrencyIntegrationTest {
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
    @DisplayName("W3-STOCK-RACE-01: 재고 5에 주문 8개가 경쟁하면 확정 5·재고 부족 3이고 성공 주문만 차감한다")
    void eightOrdersCompeteForFiveItemsWithoutPartialPayment() throws Exception {
        long brandId = brands.save(new Brand("재고 경쟁 브랜드")).getId();
        long productId = products.create(UserRole.ADMIN, brandId, "재고 경쟁 상품", 1000, 5).productId();
        long otherBrandId = brands.save(new Brand("다른 브랜드")).getId();
        long otherProductId = products.create(UserRole.ADMIN, otherBrandId, "다른 상품", 2000, 9).productId();
        points.charge("alice", 10000);
        points.charge("bob", 10000);
        orders.create("alice", List.of(new OrderQuantities.Item(otherProductId, 1)));
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String buyer = i % 2 == 0 ? "alice" : "bob";
            long orderId = orders.create(buyer, List.of(new OrderQuantities.Item(productId, 1))).orderId();
            requests.add(new Request(buyer, i % 2 == 0 ? 1L : 2L, orders.getDetail(buyer, orderId)));
        }
        assertThat(requests).extracting(request -> request.draft().orderId()).doesNotHaveDuplicates().hasSize(8);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.draft().status()).isEqualTo(OrderStatus.DRAFT);
            assertThat(request.draft().paidAmount()).isNull();
            assertThat(request.draft().confirmedAt()).isNull();
            assertThat(request.draft().totalAmount()).isEqualTo(1000);
            assertThat(request.draft().items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(productId);
                assertThat(item.quantity()).isEqualTo(1);
            });
        });
        assertThat(AopUtils.isAopProxy(orders)).as("실제 Spring 서비스 프록시 사용").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var before = storedState();
        assertThat(row(before.get("products"), productId).get("stock_quantity")).isEqualTo(5);
        assertThat(row(before.get("users"), 1).get("point_balance")).isEqualTo(10000L);
        assertThat(row(before.get("users"), 2).get("point_balance")).isEqualTo(10000L);

        List<Outcome> results = confirmTogether(requests);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(results).extracting(result -> result.request().draft().orderId())
            .containsExactlyElementsOf(requests.stream().map(request -> request.draft().orderId()).toList());
        var successes = results.stream().filter(result -> result.failure() == null).toList();
        var rejections = results.stream().filter(this::isStockShortage).toList();
        var unexpectedErrors = results.stream()
            .filter(result -> result.failure() != null && !isStockShortage(result)).toList();
        assertThat(unexpectedErrors).as("기술 오류·포인트 부족 등 예상 밖 오류를 품절로 세지 않는다").isEmpty();
        assertThat(successes).hasSize(5);
        assertThat(rejections).hasSize(3);
        assertThat(successes.size() + rejections.size() + unexpectedErrors.size()).isEqualTo(8);

        var after = storedState();
        List<Long> successfulIds = successes.stream().map(result -> result.request().draft().orderId()).toList();
        assertThat(after.get("orders").stream().filter(order -> "CONFIRMED".equals(order.get("status")))
            .map(order -> ((Number) order.get("id")).longValue()).toList())
            .containsExactlyInAnyOrderElementsOf(successfulIds);
        for (Outcome result : results) {
            Request request = result.request();
            long orderId = request.draft().orderId();
            var saved = row(after.get("orders"), orderId);
            assertThat(saved.get("user_id")).isEqualTo(request.userId());
            assertThat(saved.get("total_amount")).isEqualTo(1000L);
            if (result.failure() == null) {
                assertThat(result.confirmed()).isNotNull();
                assertThat(result.confirmed().orderId()).isEqualTo(orderId);
                assertThat(result.confirmed().status()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(result.confirmed().items()).isEqualTo(request.draft().items());
                assertThat(result.confirmed().totalAmount()).isEqualTo(1000);
                assertThat(result.confirmed().paidAmount()).isEqualTo(1000L);
                assertThat(result.confirmed().confirmedAt()).isNotNull();
                assertThat(orders.getDetail(request.buyer(), orderId)).isEqualTo(result.confirmed());
                assertThat(saved.get("status")).isEqualTo("CONFIRMED");
                assertThat(saved.get("paid_amount")).isEqualTo(1000L);
                assertThat(saved.get("confirmed_at")).isNotNull();
            } else {
                assertThat(result.confirmed()).isNull();
                assertThat(orders.getDetail(request.buyer(), orderId)).isEqualTo(request.draft());
                assertThat(saved).as("거절된 주문은 DRAFT 전체 행 보존").isEqualTo(row(before.get("orders"), orderId));
            }
        }
        assertPreservedRows(before, after, productId, successfulIds);

        int soldQuantity = after.get("items").stream()
            .filter(item -> successfulIds.contains(((Number) item.get("order_id")).longValue()))
            .mapToInt(item -> ((Number) item.get("quantity")).intValue()).sum();
        int finalStock = ((Number) row(after.get("products"), productId).get("stock_quantity")).intValue();
        assertThat(soldQuantity).isEqualTo(5);
        assertThat(finalStock).isZero();
        assertThat(5 - soldQuantity).isEqualTo(finalStock);
        long totalPaid = 0;
        long totalBalance = 0;
        for (long userId : List.of(1L, 2L)) {
            long paid = after.get("orders").stream()
                .filter(order -> successfulIds.contains(((Number) order.get("id")).longValue()))
                .filter(order -> ((Number) order.get("user_id")).longValue() == userId)
                .mapToLong(order -> ((Number) order.get("paid_amount")).longValue()).sum();
            long balance = ((Number) row(after.get("users"), userId).get("point_balance")).longValue();
            assertThat(balance).as("구매자 %s의 성공 주문 금액만 차감", userId).isEqualTo(10000 - paid);
            totalPaid += paid;
            totalBalance += balance;
        }
        assertThat(totalPaid).isEqualTo(5000);
        assertThat(totalBalance).isEqualTo(15000);
    }

    private List<Outcome> confirmTogether(List<Request> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(requests.size());
        List<Future<Outcome>> futures = new ArrayList<>();
        workersTerminated = false;
        try {
            for (Request request : requests) {
                futures.add(executor.submit(() -> confirm(request, ready, start)));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("8개 worker가 모두 시작 신호를 기다림").isTrue();
            start.countDown();
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            assertThat(workersTerminated).as("최종 DB 조회·정리 전에 모든 worker 종료").isTrue();
        }
    }

    private Outcome confirm(Request request, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("주문 확정 시작 시간 초과");
            }
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            // 서비스 프록시가 커밋까지 마친 뒤 정상 반환해야 성공으로 기록한다.
            return new Outcome(request, orders.confirm(request.buyer(), request.draft().orderId()), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome(request, null, exception);
        } catch (Exception exception) {
            return new Outcome(request, null, exception);
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    private boolean isStockShortage(Outcome result) {
        return result.failure() != null && result.failure().getClass() == ProductStockException.class
            && ((ProductStockException) result.failure()).getReason() == ProductStockException.Reason.INSUFFICIENT_STOCK;
    }

    private void assertPreservedRows(Map<String, List<Map<String, Object>>> before,
                                     Map<String, List<Map<String, Object>>> after,
                                     long productId, List<Long> successfulIds) {
        for (String table : before.keySet()) {
            assertThat(after.get(table)).as("%s 행 ID와 행 수 보존", table)
                .extracting(value -> value.get("id"))
                .containsExactlyElementsOf(before.get(table).stream().map(value -> value.get("id")).toList());
            for (var original : before.get(table)) {
                long id = ((Number) original.get("id")).longValue();
                var saved = row(after.get(table), id);
                List<String> changedColumns = switch (table) {
                    case "orders" -> successfulIds.contains(id)
                        ? List.of("status", "paid_amount", "confirmed_at", "updated_at") : List.of();
                    case "products" -> id == productId ? List.of("stock_quantity", "updated_at") : List.of();
                    case "users" -> id == 1 || id == 2 ? List.of("point_balance", "updated_at") : List.of();
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

    private record Request(String buyer, long userId, OrderInfo draft) {
    }

    private record Outcome(Request request, OrderInfo confirmed, Exception failure) {
    }
}
