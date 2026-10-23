package com.loopers.application.order;

import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.Product;
import com.loopers.support.ConcurrentRunner;
import com.loopers.support.pg.TestPgServer;
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

import static com.loopers.support.ConcurrentRunner.SUCCESS;
import static com.loopers.support.ConcurrentRunner.TECHNICAL_PREFIX;
import static com.loopers.support.ConcurrentRunner.outcomeOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import com.loopers.application.order.facade.OrderPaymentFacade;

/** 동시 결제 시작. 모든 작업이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽어 요청 결과 · PG 수신 횟수와 비교함 */
@SpringBootTest
class OrderConfirmConcurrencyTest {

    private static final String OUT_OF_STOCK = "OUT_OF_STOCK";
    private static final String PAYMENT_ATTEMPT_CONFLICT = "PAYMENT_ATTEMPT_CONFLICT";

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();
    private Brand brand;

    @BeforeEach
    void setUp() {
        pg.reset();
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

    private static ConfirmCommand command(String attemptKey) {
        return new ConfirmCommand(attemptKey, "TEST", "0000-0000-0000-0000");
    }

    @DisplayName("재고 5 인 상품을 서로 다른 구매자 8 명이 1 개씩 확정하면, 결제 시작 5 · 재고 부족 3 · 기술 오류 0 이고 PG 생성도 5 번이다.")
    @Test
    void startsFiveAndRejectsThree_whenEightBuyersCompeteForStockOfFive() throws Exception {
        // arrange
        int initialStock = 5;
        int buyers = 8;
        Product product = persistProduct(initialStock, 1_000L);
        List<Order> orders = new ArrayList<>();
        List<Callable<String>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= buyers; userId++) {
            Order order = persistDraft(userId, product, 1);
            orders.add(order);
            long buyer = userId;
            tasks.add(outcomeOf(() -> orderPaymentFacade.confirm(buyer, order.getId(), command("attempt-" + buyer))));
        }

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        long started = countOf(outcomes, SUCCESS);
        assertAll(
            () -> assertThat(started).isEqualTo(5),
            () -> assertThat(countOf(outcomes, OUT_OF_STOCK)).isEqualTo(3),
            () -> assertThat(technicalErrors(outcomes)).isEmpty(),
            () -> assertThat(stockOf(product.getId())).isZero(),
            // 수량: 초기 재고 - 확보한 주문의 품목 수량 합 = 가용 재고
            () -> assertThat(initialStock - started).isEqualTo(stockOf(product.getId())),
            () -> assertThat(pg.createCount()).isEqualTo(5),
            () -> assertThat(countOf("select count(*) from payment")).isEqualTo(5),
            () -> {
                for (int i = 0; i < buyers; i++) {
                    boolean reserved = SUCCESS.equals(outcomes.get(i));
                    assertThat(statusOf(orders.get(i).getId())).isEqualTo(reserved ? "PAYMENT_PENDING" : "DRAFT");
                }
            }
        );
    }

    @DisplayName("같은 주문 · 같은 시도 키로 동시에 확정하면, 둘 다 성공 응답이지만 결제 시도 · 재고 확보 · 실제 PG 생성은 한 번이다.")
    @Test
    void sendsCreateOnce_whenSameAttemptIsConfirmedConcurrently() throws Exception {
        // arrange
        long userId = 1L;
        Product product = persistProduct(10, 1_000L);
        Order order = persistDraft(userId, product, 1);
        List<Callable<String>> tasks = List.of(
            outcomeOf(() -> orderPaymentFacade.confirm(userId, order.getId(), command("attempt-1"))),
            outcomeOf(() -> orderPaymentFacade.confirm(userId, order.getId(), command("attempt-1")))
        );

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsExactly(SUCCESS, SUCCESS),
            () -> assertThat(statusOf(order.getId())).isEqualTo("PAYMENT_PENDING"),
            () -> assertThat(stockOf(product.getId())).isEqualTo(9),
            () -> assertThat(countOf("select count(*) from payment")).isEqualTo(1),
            () -> assertThat(pg.createCount()).isEqualTo(1)
        );
    }

    @DisplayName("같은 주문에 다른 시도 키로 동시에 확정하면, 하나만 시작하고 다른 하나는 PAYMENT_ATTEMPT_CONFLICT 이며 PG 생성은 한 번이다.")
    @Test
    void startsOnlyOne_whenDifferentAttemptsCompeteForSameOrder() throws Exception {
        // arrange
        long userId = 1L;
        Product product = persistProduct(10, 1_000L);
        Order order = persistDraft(userId, product, 1);
        List<Callable<String>> tasks = List.of(
            outcomeOf(() -> orderPaymentFacade.confirm(userId, order.getId(), command("attempt-1"))),
            outcomeOf(() -> orderPaymentFacade.confirm(userId, order.getId(), command("attempt-2")))
        );

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsExactlyInAnyOrder(SUCCESS, PAYMENT_ATTEMPT_CONFLICT),
            () -> assertThat(stockOf(product.getId())).isEqualTo(9),
            () -> assertThat(countOf("select count(*) from payment")).isEqualTo(1),
            () -> assertThat(pg.createCount()).isEqualTo(1)
        );
    }

    @DisplayName("콜백과 reconcile 이 동시에 FAILED 를 확인해도, 하나만 반영되어 재고는 한 번만 반환되고 기술 오류는 0 이다.")
    @Test
    void releasesOnce_whenCallbackAndReconcileRunConcurrently() throws Exception {
        // arrange
        long userId = 1L;
        Product product = persistProduct(10, 1_000L);
        Order order = persistDraft(userId, product, 2);
        orderPaymentFacade.confirm(userId, order.getId(), command("attempt-1"));
        String providerOrderId = Payment.providerOrderIdOf(order.getId());
        pg.complete(pg.transactionsOf(providerOrderId).get(0).transactionKey(), "FAILED");
        List<Callable<String>> tasks = List.of(
            outcomeOf(() -> orderPaymentFacade.handleCallback(providerOrderId)),
            outcomeOf(() -> orderPaymentFacade.reconcile(userId, order.getId()))
        );

        // act
        List<String> outcomes = ConcurrentRunner.run(tasks);

        // assert
        assertAll(
            () -> assertThat(outcomes).containsExactly(SUCCESS, SUCCESS),
            () -> assertThat(statusOf(order.getId())).isEqualTo("PAYMENT_FAILED"),
            () -> assertThat(stockOf(product.getId())).isEqualTo(10),
            () -> assertThat(pg.createCount()).isEqualTo(1)
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

    private String statusOf(Long orderId) {
        return (String) query("select status from orders where id = :id", orderId);
    }

    private long countOf(String sql) {
        return ((Number) transactionTemplate.execute(status -> entityManager.createNativeQuery(sql).getSingleResult())).longValue();
    }

    /** 모든 작업이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽음 */
    private Object query(String sql, Long id) {
        return transactionTemplate.execute(status ->
            entityManager.createNativeQuery(sql).setParameter("id", id).getSingleResult()
        );
    }
}
