package com.loopers.application.order;

import com.loopers.application.product.AdminProductService;
import com.loopers.application.user.PointService;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.OrderQuantities;
import com.loopers.domain.order.OrderStatus;
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
class OrderPointConcurrencyIntegrationTest {
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
    @DisplayName("W3-POINT-RACE-01: 잔액 10000에 4000원 주문 3개가 경쟁하면 확정 2·잔액 부족 1이다")
    void threeOrdersCompeteForOneBalanceWithoutDeductingRejectedStock() throws Exception {
        points.charge("alice", 10000);
        points.charge("bob", 7000);
        long otherBrandId = brands.save(new Brand("다른 구매자의 브랜드")).getId();
        long otherProductId = products.create(UserRole.ADMIN, otherBrandId, "다른 구매자의 상품", 2000, 9).productId();
        long otherOrderId = orders.create("bob", List.of(new OrderQuantities.Item(otherProductId, 1))).orderId();
        OrderInfo otherDraft = orders.getDetail("bob", otherOrderId);
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            // 공통 브랜드·상품의 잠금이 사용자 잔액 경쟁을 대신 막지 않도록 대상을 분리한다.
            long brandId = brands.save(new Brand("포인트 경쟁 브랜드 " + i)).getId();
            long productId = products.create(UserRole.ADMIN, brandId, "포인트 경쟁 상품 " + i, 4000, 5).productId();
            long orderId = orders.create("alice", List.of(new OrderQuantities.Item(productId, 1))).orderId();
            requests.add(new Request(brandId, productId, orders.getDetail("alice", orderId)));
        }
        assertThat(requests).extracting(request -> request.draft().orderId()).doesNotHaveDuplicates().hasSize(3);
        assertThat(requests).extracting(Request::brandId).doesNotHaveDuplicates().hasSize(3);
        assertThat(requests).extracting(Request::productId).doesNotHaveDuplicates().hasSize(3);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.draft().status()).isEqualTo(OrderStatus.DRAFT);
            assertThat(request.draft().paidAmount()).isNull();
            assertThat(request.draft().confirmedAt()).isNull();
            assertThat(request.draft().totalAmount()).isEqualTo(4000);
            assertThat(request.draft().items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(request.productId());
                assertThat(item.unitPrice()).isEqualTo(4000);
                assertThat(item.quantity()).isEqualTo(1);
            });
        });
        assertThat(AopUtils.isAopProxy(orders)).as("실제 Spring 서비스 프록시 사용").isTrue();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var before = storedState();
        assertThat(row(before.get("users"), 1).get("point_balance")).isEqualTo(10000L);
        assertThat(row(before.get("users"), 2).get("point_balance")).isEqualTo(7000L);
        for (Request request : requests) {
            var product = row(before.get("products"), request.productId());
            assertThat(product.get("brand_id")).isEqualTo(request.brandId());
            assertThat(product.get("stock_quantity")).isEqualTo(5);
        }

        List<Outcome> results = confirmTogether(requests);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(results).extracting(result -> result.request().draft().orderId())
            .containsExactlyElementsOf(requests.stream().map(request -> request.draft().orderId()).toList());
        var successes = results.stream().filter(result -> result.failure() == null).toList();
        var rejections = results.stream().filter(this::isPointShortage).toList();
        var unexpectedErrors = results.stream()
            .filter(result -> result.failure() != null && !isPointShortage(result)).toList();
        assertThat(unexpectedErrors).as("기술 오류·재고 부족 등 예상 밖 오류를 잔액 부족으로 세지 않는다").isEmpty();
        assertThat(successes).hasSize(2);
        assertThat(rejections).hasSize(1);
        assertThat(successes.size() + rejections.size() + unexpectedErrors.size()).isEqualTo(3);

        var after = storedState();
        List<Long> successfulIds = successes.stream().map(result -> result.request().draft().orderId()).toList();
        List<Long> successfulProductIds = successes.stream().map(result -> result.request().productId()).toList();
        assertThat(after.get("orders").stream().filter(order -> "CONFIRMED".equals(order.get("status")))
            .map(order -> ((Number) order.get("id")).longValue()).toList())
            .containsExactlyInAnyOrderElementsOf(successfulIds);
        for (Outcome result : results) {
            Request request = result.request();
            long orderId = request.draft().orderId();
            var savedOrder = row(after.get("orders"), orderId);
            var savedProduct = row(after.get("products"), request.productId());
            assertThat(savedOrder.get("user_id")).isEqualTo(1L);
            assertThat(savedOrder.get("total_amount")).isEqualTo(4000L);
            if (result.failure() == null) {
                assertThat(result.confirmed()).isNotNull();
                assertThat(result.confirmed().orderId()).isEqualTo(orderId);
                assertThat(result.confirmed().status()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(result.confirmed().items()).isEqualTo(request.draft().items());
                assertThat(result.confirmed().totalAmount()).isEqualTo(4000);
                assertThat(result.confirmed().paidAmount()).isEqualTo(4000L);
                assertThat(result.confirmed().confirmedAt()).isNotNull();
                assertThat(orders.getDetail("alice", orderId)).isEqualTo(result.confirmed());
                assertThat(savedOrder.get("status")).isEqualTo("CONFIRMED");
                assertThat(savedOrder.get("paid_amount")).isEqualTo(4000L);
                assertThat(savedOrder.get("confirmed_at")).isNotNull();
                assertThat(savedProduct.get("stock_quantity")).isEqualTo(4);
            } else {
                assertThat(result.confirmed()).isNull();
                assertThat(orders.getDetail("alice", orderId)).isEqualTo(request.draft());
                assertThat(savedOrder).as("잔액 부족 주문은 DRAFT 전체 행 보존")
                    .isEqualTo(row(before.get("orders"), orderId));
                assertThat(savedProduct).as("잔액 부족 주문의 상품은 재고와 감사 시각을 포함한 전체 행 보존")
                    .isEqualTo(row(before.get("products"), request.productId()));
                assertThat(savedProduct.get("stock_quantity")).isEqualTo(5);
            }
            int soldQuantity = after.get("items").stream()
                .filter(item -> successfulIds.contains(((Number) item.get("order_id")).longValue()))
                .filter(item -> ((Number) item.get("product_id")).longValue() == request.productId())
                .mapToInt(item -> ((Number) item.get("quantity")).intValue()).sum();
            int initialStock = ((Number) row(before.get("products"), request.productId()).get("stock_quantity")).intValue();
            int finalStock = ((Number) savedProduct.get("stock_quantity")).intValue();
            assertThat(initialStock - soldQuantity).as("상품 %s의 성공 품목 수량만 차감", request.productId())
                .isEqualTo(finalStock);
        }
        assertPreservedRows(before, after, successfulIds, successfulProductIds);
        int totalSoldQuantity = after.get("items").stream()
            .filter(item -> successfulIds.contains(((Number) item.get("order_id")).longValue()))
            .mapToInt(item -> ((Number) item.get("quantity")).intValue()).sum();
        long totalPaid = after.get("orders").stream()
            .filter(order -> successfulIds.contains(((Number) order.get("id")).longValue()))
            .mapToLong(order -> ((Number) order.get("paid_amount")).longValue()).sum();
        long initialBalance = ((Number) row(before.get("users"), 1).get("point_balance")).longValue();
        long finalBalance = ((Number) row(after.get("users"), 1).get("point_balance")).longValue();
        assertThat(totalSoldQuantity).isEqualTo(2);
        assertThat(totalPaid).isEqualTo(8000);
        assertThat(finalBalance).isEqualTo(2000);
        assertThat(initialBalance - totalPaid).isEqualTo(finalBalance);
        assertThat(row(after.get("users"), 2)).as("다른 구매자의 잔액과 전체 행 보존")
            .isEqualTo(row(before.get("users"), 2));
        assertThat(orders.getDetail("bob", otherOrderId)).isEqualTo(otherDraft);
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
            assertThat(ready.await(5, TimeUnit.SECONDS)).as("3개 worker가 모두 시작 신호를 기다림").isTrue();
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
            try {
                workersTerminated = executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            }
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
            // 외부 트랜잭션 없이 서비스 프록시가 커밋까지 마친 정상 반환만 성공으로 기록한다.
            return new Outcome(request, orders.confirm("alice", request.draft().orderId()), null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome(request, null, exception);
        } catch (Exception exception) {
            return new Outcome(request, null, exception);
        } finally {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    private boolean isPointShortage(Outcome result) {
        return result.failure() != null && result.failure().getClass() == PointsException.class
            && ((PointsException) result.failure()).getReason() == PointsException.Reason.INSUFFICIENT_POINTS;
    }

    private void assertPreservedRows(Map<String, List<Map<String, Object>>> before,
                                     Map<String, List<Map<String, Object>>> after,
                                     List<Long> successfulIds, List<Long> successfulProductIds) {
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
                    case "products" -> successfulProductIds.contains(id)
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

    private record Request(long brandId, long productId, OrderInfo draft) {
    }

    private record Outcome(Request request, OrderInfo confirmed, Exception failure) {
    }
}
