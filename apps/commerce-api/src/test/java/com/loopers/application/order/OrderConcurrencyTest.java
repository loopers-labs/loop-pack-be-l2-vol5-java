package com.loopers.application.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Price;
import com.loopers.support.error.DomainException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConcurrencyTest {

    private static final Instant NOW = Instant.parse("2026-10-04T00:00:00Z");
    private static final long TIMEOUT_SECONDS = 30;
    private static final String SUCCESS = "성공";

    private final OrderFacade orderFacade;
    private final PointFacade pointFacade;
    private final ProductFacade productFacade;
    private final BrandFacade brandFacade;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long brandId;

    @Autowired
    OrderConcurrencyTest(
        OrderFacade orderFacade,
        PointFacade pointFacade,
        ProductFacade productFacade,
        BrandFacade brandFacade,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.orderFacade = orderFacade;
        this.pointFacade = pointFacade;
        this.productFacade = productFacade;
        this.brandFacade = brandFacade;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("PRODUCT-023 · 재고 5 에 1개씩 사는 서로 다른 주문 8건: 확정 5 · 재고 부족 3 · 기술 오류 0 · 최종 재고 0")
    @Test
    void stockRace() throws Exception {
        assertStockRace(8);
    }

    @DisplayName("PRODUCT-023 · 재고 5 에 주문 12건 — 커넥션 풀(10)보다 요청이 많아도: 확정 5 · 재고 부족 7 · 기술 오류 0 · 최종 재고 0")
    @Test
    void stockRaceBeyondPoolSize() throws Exception {
        assertStockRace(12);
    }

    private void assertStockRace(int requests) throws Exception {
        Long productId = product(1_000, 5);
        List<Callable<String>> confirms = new ArrayList<>();
        for (long buyer = 1; buyer <= requests; buyer++) {
            pointFacade.charge(buyer, ChargeAmount.of(10_000), NOW);
            long owner = buyer;
            Long orderId = place(owner, productId, 1);
            confirms.add(() -> {
                orderFacade.confirm(owner, orderId, NOW);
                return SUCCESS;
            });
        }

        List<String> results = runTogether(confirms);

        assertThat(countBy(results)).isEqualTo(Map.of(SUCCESS, 5L, "INSUFFICIENT_STOCK", (long) requests - 5));
        assertThat(quantityOf(productId)).isZero();
        assertThat(5 - confirmedQuantity(productId)).as("초기 재고 − 성공 주문의 품목 수량 합 = 최종 재고")
            .isEqualTo(quantityOf(productId));
        for (long buyer = 1; buyer <= requests; buyer++) {
            assertThat(balance(buyer)).as("구매자 %d 의 잔액 = 초기 잔액 − 성공 결제액", buyer)
                .isEqualTo(10_000 - paidAmount(buyer));
            assertThat(balance(buyer)).as("구매자 %d 의 잔액 = 원장 합", buyer).isEqualTo(ledgerSum(buyer));
        }
    }

    @DisplayName("ORDER-014 · 한 사용자의 잔액 10,000원에 서로 다른 4,000원 주문 3건: 확정 2 · 잔액 부족 1 · 최종 잔액 2,000원 · 거절된 주문의 재고는 유지")
    @Test
    void pointRace() throws Exception {
        long buyer = 1L;
        Long productId = product(4_000, 100);
        pointFacade.charge(buyer, ChargeAmount.of(10_000), NOW);
        List<Long> orderIds = List.of(place(buyer, productId, 1), place(buyer, productId, 1), place(buyer, productId, 1));

        List<String> results = runTogether(orderIds.stream()
            .map(orderId -> (Callable<String>) () -> {
                orderFacade.confirm(buyer, orderId, NOW);
                return SUCCESS;
            })
            .toList());

        assertThat(countBy(results)).isEqualTo(Map.of(SUCCESS, 2L, "INSUFFICIENT_POINT", 1L));
        assertThat(balance(buyer)).isEqualTo(2_000L);
        assertThat(balance(buyer)).as("초기 잔액 + 성공 충전 − 성공 결제 = 최종 잔액")
            .isEqualTo(10_000 - paidAmount(buyer));
        assertThat(balance(buyer)).as("잔액 = 원장 합").isEqualTo(ledgerSum(buyer));
        assertThat(quantityOf(productId)).as("거절된 주문의 재고는 차감되지 않는다").isEqualTo(98);
    }

    @DisplayName("POINT · 잔액 10,000원에서 2,000원 충전과 7,000원 확정을 함께: 둘 다 성공 · 기술 오류 0 · 최종 잔액 5,000원")
    @Test
    void chargeAndPayRace() throws Exception {
        long buyer = 1L;
        Long productId = product(7_000, 100);
        pointFacade.charge(buyer, ChargeAmount.of(10_000), NOW);
        Long orderId = place(buyer, productId, 1);

        List<String> results = runTogether(List.of(
            () -> {
                pointFacade.charge(buyer, ChargeAmount.of(2_000), NOW);
                return SUCCESS;
            },
            () -> {
                orderFacade.confirm(buyer, orderId, NOW);
                return SUCCESS;
            }
        ));

        assertThat(countBy(results)).isEqualTo(Map.of(SUCCESS, 2L));
        assertThat(balance(buyer)).isEqualTo(5_000L);
        assertThat(balance(buyer)).as("잔액 = 원장 합").isEqualTo(ledgerSum(buyer));
    }

    @DisplayName("ORDER-018 · 같은 주문에 확정 10건이 동시에: 확정 1 · ORDER_NOT_DRAFT 9 · 기술 오류 0 · 차감은 한 번")
    @Test
    void sameOrderRace() throws Exception {
        long buyer = 1L;
        Long productId = product(10_000, 10);
        pointFacade.charge(buyer, ChargeAmount.of(50_000), NOW);
        Long orderId = place(buyer, productId, 2);

        List<Callable<String>> confirms = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            confirms.add(() -> {
                orderFacade.confirm(buyer, orderId, NOW);
                return SUCCESS;
            });
        }
        List<String> results = runTogether(confirms);

        assertThat(countBy(results)).isEqualTo(Map.of(SUCCESS, 1L, "ORDER_NOT_DRAFT", 9L));
        assertThat(quantityOf(productId)).isEqualTo(8);
        assertThat(balance(buyer)).isEqualTo(30_000L);
        assertThat(balance(buyer)).as("잔액 = 원장 합").isEqualTo(ledgerSum(buyer));
    }

    private List<String> runTogether(List<Callable<String>> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = requests.stream()
                .map(request -> executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        return "기술 오류 · 시작 신호 시간 초과";
                    }
                    return classify(request);
                }))
                .toList();
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("모든 worker 가 준비된다").isTrue();
            start.countDown();

            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            assertThat(results).as("기술 오류는 업무 거절로 숨기지 않고 0 이어야 한다")
                .noneMatch(result -> result.startsWith("기술 오류"));
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).as("worker 가 정리된다").isTrue();
        }
    }

    private static String classify(Callable<String> request) {
        try {
            return request.call();
        } catch (DomainException e) {
            return e.code();
        } catch (Exception e) {
            return "기술 오류 · " + e.getClass().getSimpleName() + " · " + e.getMessage();
        }
    }

    private static Map<String, Long> countBy(List<String> results) {
        return results.stream().collect(Collectors.groupingBy(result -> result, Collectors.counting()));
    }

    private Long product(long price, int stock) {
        Long productId = productFacade.register(brandId, "상품", Price.of(price)).getId();
        productFacade.adjustStock(productId, Quantity.of(stock));
        return productId;
    }

    private Long place(long buyer, Long productId, int quantity) {
        return orderFacade.place(new OrderCreateCommand(buyer,
            List.of(new OrderCreateCommand.Line(productId, OrderQuantity.of(quantity)))), NOW).getId();
    }

    private int quantityOf(Long productId) {
        Integer quantity = jdbcTemplate.queryForObject("SELECT quantity FROM product WHERE id = ?", Integer.class, productId);
        return quantity == null ? -1 : quantity;
    }

    private int confirmedQuantity(Long productId) {
        Integer sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(i.quantity), 0) FROM order_item i JOIN orders o ON o.id = i.order_id "
                + "WHERE i.product_id = ? AND o.status = 'CONFIRMED'", Integer.class, productId);
        return sum == null ? -1 : sum;
    }

    private long paidAmount(long buyer) {
        Long sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(paid_amount), 0) FROM orders WHERE user_id = ? AND status = 'CONFIRMED'",
            Long.class, buyer);
        return sum == null ? -1 : sum;
    }

    private long balance(long buyer) {
        Long balance = jdbcTemplate.queryForObject("SELECT balance FROM user_point WHERE user_id = ?", Long.class, buyer);
        return balance == null ? -1 : balance;
    }

    private long ledgerSum(long buyer) {
        Long sum = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(CASE WHEN type = 'CHARGE' THEN amount ELSE -amount END), 0) "
                + "FROM point_transaction WHERE user_id = ?", Long.class, buyer);
        return sum == null ? -1 : sum;
    }
}
