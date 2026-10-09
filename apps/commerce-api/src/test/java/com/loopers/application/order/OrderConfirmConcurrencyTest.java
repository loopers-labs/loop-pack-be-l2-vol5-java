package com.loopers.application.order;

import com.loopers.application.point.PointChargeRetrier;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Product;
import com.loopers.support.ConcurrentRunner;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import static com.loopers.support.ConcurrentRunner.EXHAUSTED;
import static com.loopers.support.ConcurrentRunner.SUCCESS;
import static com.loopers.support.ConcurrentRunner.TECHNICAL_PREFIX;
import static com.loopers.support.ConcurrentRunner.outcomeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 Retrier · Facade · repository · MySQL 을 통과하는 동시 확정 · 충전을 확인함 (3주차 설계 4, 6.4).
 * 작업의 시작만 맞추고, 모든 작업이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽어 요청 결과와 함께 비교함
 */
@SpringBootTest
class OrderConfirmConcurrencyTest {

    private static final String OUT_OF_STOCK = "OUT_OF_STOCK";
    private static final String INSUFFICIENT_POINT = "INSUFFICIENT_POINT";
    private static final String ORDER_ALREADY_CONFIRMED = "ORDER_ALREADY_CONFIRMED";

    @Autowired
    private OrderConfirmRetrier orderConfirmRetrier;

    @Autowired
    private PointChargeRetrier pointChargeRetrier;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Brand brand;

    @BeforeEach
    void setUp() {
        brand = transactionTemplate.execute(status -> {
            Brand saved = new Brand("브랜드", null);
            entityManager.persist(saved);
            return saved;
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("재고 5 인 상품을 서로 다른 구매자 8 명이 1 개씩 확정하면, 확정 5 · 재고 부족 3 · 기술 오류 0 이고 최종 재고는 0 이다.")
    @Test
    void confirmsFiveAndRejectsThree_whenEightBuyersCompeteForStockOfFive() throws Exception {
        // arrange
        int initialStock = 5;
        int buyers = 8;
        long initialBalance = 10_000L;
        Product product = persistProduct(initialStock, 1_000L);
        List<Order> orders = new ArrayList<>();
        List<Callable<String>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= buyers; userId++) {
            persistPoint(userId, initialBalance);
            Order order = persistDraft(userId, product, 1);
            orders.add(order);
            long buyer = userId;
            tasks.add(outcomeOf(() -> orderConfirmRetrier.confirmOrder(buyer, order.getId())));
        }

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        long succeeded = countOf(outcomes, SUCCESS);
        assertAll(
            () -> assertThat(succeeded).isEqualTo(5),
            () -> assertThat(countOf(outcomes, OUT_OF_STOCK)).isEqualTo(3),
            () -> assertThat(countOf(outcomes, EXHAUSTED)).isZero(),
            () -> assertThat(technicalErrors(outcomes)).isEmpty(),
            () -> assertThat(stockOf(product.getId())).isZero(),
            // 수량: 초기 재고 - 성공 주문의 품목 수량 합 = 최종 재고
            () -> assertThat(initialStock - succeeded).isEqualTo(stockOf(product.getId())),
            () -> {
                for (int i = 0; i < buyers; i++) {
                    boolean confirmed = SUCCESS.equals(outcomes.get(i));
                    long userId = i + 1L;
                    assertThat(statusOf(orders.get(i).getId())).isEqualTo(confirmed ? "CONFIRMED" : "DRAFT");
                    assertThat(balanceOf(userId)).isEqualTo(confirmed ? initialBalance - 1_000L : initialBalance);
                }
            }
        );
    }

    @DisplayName("잔액 10,000 원인 사용자가 서로 다른 상품의 4,000 원 주문 3 개를 동시에 확정하면, 확정 2 · 잔액 부족 1 이고 최종 잔액은 2,000 원이다.")
    @Test
    void confirmsTwoAndRejectsOne_whenThreeOrdersCompeteForSameBalance() throws Exception {
        // arrange
        long userId = 1L;
        long initialBalance = 10_000L;
        int initialStock = 10;
        persistPoint(userId, initialBalance);
        List<Product> products = new ArrayList<>();
        List<Order> orders = new ArrayList<>();
        List<Callable<String>> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Product product = persistProduct(initialStock, 4_000L);
            Order order = persistDraft(userId, product, 1);
            products.add(product);
            orders.add(order);
            tasks.add(outcomeOf(() -> orderConfirmRetrier.confirmOrder(userId, order.getId())));
        }

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        long succeeded = countOf(outcomes, SUCCESS);
        assertAll(
            () -> assertThat(succeeded).isEqualTo(2),
            () -> assertThat(countOf(outcomes, INSUFFICIENT_POINT)).isEqualTo(1),
            () -> assertThat(countOf(outcomes, EXHAUSTED)).isZero(),
            () -> assertThat(technicalErrors(outcomes)).isEmpty(),
            // 잔액: 초기 잔액 - 성공 결제액 합 = 최종 잔액
            () -> assertThat(balanceOf(userId)).isEqualTo(initialBalance - 4_000L * succeeded).isEqualTo(2_000L),
            () -> {
                for (int i = 0; i < orders.size(); i++) {
                    boolean confirmed = SUCCESS.equals(outcomes.get(i));
                    assertThat(statusOf(orders.get(i).getId())).isEqualTo(confirmed ? "CONFIRMED" : "DRAFT");
                    assertThat(stockOf(products.get(i).getId())).isEqualTo(confirmed ? initialStock - 1 : initialStock);
                }
            }
        );
    }

    @DisplayName("잔액 10,000 원에서 2,000 원 충전과 7,000 원 확정을 동시에 하면, 둘 다 성공하고 최종 잔액은 5,000 원이다.")
    @Test
    void appliesBothChargeAndPayment_whenTheyRunConcurrently() throws Exception {
        // arrange
        long userId = 1L;
        persistPoint(userId, 10_000L);
        Product product = persistProduct(10, 7_000L);
        Order order = persistDraft(userId, product, 1);
        List<Callable<String>> tasks = List.of(
            outcomeOf(() -> pointChargeRetrier.charge(userId, 2_000L)),
            outcomeOf(() -> orderConfirmRetrier.confirmOrder(userId, order.getId()))
        );

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsExactly(SUCCESS, SUCCESS),
            // 잔액: 초기 잔액 + 성공 충전액 합 - 성공 결제액 합 = 최종 잔액
            () -> assertThat(balanceOf(userId)).isEqualTo(10_000L + 2_000L - 7_000L),
            () -> assertThat(statusOf(order.getId())).isEqualTo("CONFIRMED"),
            () -> assertThat(stockOf(product.getId())).isEqualTo(9)
        );
    }

    @DisplayName("같은 주문을 동시에 두 번 확정하면, 한 번만 성공하고 다른 하나는 ORDER_ALREADY_CONFIRMED 이며 재고 · 잔액은 한 번만 차감된다.")
    @Test
    void confirmsOnlyOnce_whenSameOrderIsConfirmedTwiceConcurrently() throws Exception {
        // arrange
        long userId = 1L;
        persistPoint(userId, 10_000L);
        Product product = persistProduct(10, 1_000L);
        Order order = persistDraft(userId, product, 1);
        List<Callable<String>> tasks = List.of(
            outcomeOf(() -> orderConfirmRetrier.confirmOrder(userId, order.getId())),
            outcomeOf(() -> orderConfirmRetrier.confirmOrder(userId, order.getId()))
        );

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsExactlyInAnyOrder(SUCCESS, ORDER_ALREADY_CONFIRMED),
            () -> assertThat(statusOf(order.getId())).isEqualTo("CONFIRMED"),
            () -> assertThat(stockOf(product.getId())).isEqualTo(9),
            () -> assertThat(balanceOf(userId)).isEqualTo(9_000L)
        );
    }

    private Product persistProduct(int stock, long price) {
        return transactionTemplate.execute(status -> {
            Product product = new Product(entityManager.find(Brand.class, brand.getId()), "상품", price);
            product.changeStock(stock);
            entityManager.persist(product);
            return product;
        });
    }

    private void persistPoint(Long userId, long balance) {
        transactionTemplate.executeWithoutResult(status -> {
            Point point = new Point(userId);
            point.charge(balance);
            entityManager.persist(point);
        });
    }

    private Order persistDraft(Long userId, Product product, int quantity) {
        return transactionTemplate.execute(status -> {
            Order order = Order.draft(userId, List.of(
                new OrderLine(product.getId(), product.getName(), product.getPrice(), quantity)
            ), ZonedDateTime.now());
            entityManager.persist(order);
            return order;
        });
    }

    private static long countOf(List<String> outcomes, String outcome) {
        return Collections.frequency(outcomes, outcome);
    }

    private static List<String> technicalErrors(List<String> outcomes) {
        return outcomes.stream().filter(outcome -> outcome.startsWith(TECHNICAL_PREFIX)).toList();
    }

    private int stockOf(Long productId) {
        return ((Number) query("select stock from product where id = :id", productId)).intValue();
    }

    private long balanceOf(Long userId) {
        return ((Number) query("select balance from points where user_id = :id", userId)).longValue();
    }

    private String statusOf(Long orderId) {
        return (String) query("select status from orders where id = :id", orderId);
    }

    /** 모든 작업이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽음 */
    private Object query(String sql, Long id) {
        return transactionTemplate.execute(status ->
            entityManager.createNativeQuery(sql).setParameter("id", id).getSingleResult()
        );
    }
}
