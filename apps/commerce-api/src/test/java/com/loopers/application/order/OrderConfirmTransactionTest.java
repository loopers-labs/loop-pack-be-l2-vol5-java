package com.loopers.application.order;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.point.Point;
import com.loopers.domain.product.Product;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * 주문 확정의 재고 · 포인트 · 확정 결과가 함께 commit 되거나 rollback 되는지 실제 DB 로 확인함 (ORD-12, 3주차 설계 3, 6.3).
 * 테스트 메서드를 트랜잭션으로 감싸지 않고, 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 다시 읽음
 */
@SpringBootTest
class OrderConfirmTransactionTest {

    private static final Long USER_ID = 1L;
    private static final long INITIAL_BALANCE = 10_000L;
    private static final int INITIAL_STOCK = 10;

    @Autowired
    private OrderFacade orderFacade;

    @MockitoSpyBean
    private OrderService orderService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Product first;
    private Product second;
    private Order order;

    @BeforeEach
    void setUp() {
        transactionTemplate.executeWithoutResult(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            first = persistProduct(brand, "첫 번째 상품", 1_000L);
            second = persistProduct(brand, "두 번째 상품", 2_000L);
            Point point = new Point(USER_ID);
            point.charge(INITIAL_BALANCE);
            entityManager.persist(point);
            order = Order.draft(USER_ID, List.of(
                new OrderLine(first.getId(), first.getName(), first.getPrice(), 2),
                new OrderLine(second.getId(), second.getName(), second.getPrice(), 1)
            ), ZonedDateTime.now());
            entityManager.persist(order);
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("여러 품목의 주문을 확정하면, 재고 · 잔액 차감과 CONFIRMED · 결제 결과가 함께 반영된다.")
    @Test
    void appliesStockPointAndConfirmationTogether() {
        // act
        orderFacade.confirmOrder(USER_ID, order.getId());

        // assert
        assertAll(
            () -> assertThat(stockOf(first.getId())).isEqualTo(INITIAL_STOCK - 2),
            () -> assertThat(stockOf(second.getId())).isEqualTo(INITIAL_STOCK - 1),
            () -> assertThat(balanceOf(USER_ID)).isEqualTo(INITIAL_BALANCE - 4_000L),
            () -> assertThat(orderStateOf(order.getId())).isEqualTo(List.of("CONFIRMED", 4_000L, true))
        );
    }

    @DisplayName("재고 · 잔액 · 확정 결과 SQL 이 모두 나간 뒤 실패하면, DRAFT · 재고 · 잔액 · 결제 결과가 변경 전과 같다.")
    @Test
    void rollsBackEverything_whenFailsAfterChangesAreFlushed() {
        // arrange: 마지막 단계(확정 결과 저장)를 실제로 실행하고, 모든 변경을 DB 로 보낸 뒤 실패시킴
        AtomicReference<List<Object>> seenInsideTransaction = new AtomicReference<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            seenInsideTransaction.set(List.of(
                stockInCurrentTransaction(first.getId()),
                stockInCurrentTransaction(second.getId()),
                balanceInCurrentTransaction(USER_ID),
                orderStateInCurrentTransaction(order.getId())
            ));
            throw new IllegalStateException("테스트에서 주입한 실패");
        }).when(orderService).confirm(any(Order.class), anyLong());

        // act
        assertThrows(IllegalStateException.class, () -> orderFacade.confirmOrder(USER_ID, order.getId()));

        // assert
        assertAll(
            () -> assertThat(seenInsideTransaction.get()).containsExactly(
                INITIAL_STOCK - 2, INITIAL_STOCK - 1, INITIAL_BALANCE - 4_000L, List.of("CONFIRMED", 4_000L, true)
            ),
            () -> assertThat(stockOf(first.getId())).isEqualTo(INITIAL_STOCK),
            () -> assertThat(stockOf(second.getId())).isEqualTo(INITIAL_STOCK),
            () -> assertThat(balanceOf(USER_ID)).isEqualTo(INITIAL_BALANCE),
            () -> assertThat(orderStateOf(order.getId())).isEqualTo(List.of("DRAFT", 0L, false))
        );
    }

    private Product persistProduct(Brand brand, String name, long price) {
        Product product = new Product(brand, name, price);
        product.changeStock(INITIAL_STOCK);
        entityManager.persist(product);
        return product;
    }

    /** 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽음 */
    private int stockOf(Long productId) {
        return transactionTemplate.execute(status -> stockInCurrentTransaction(productId));
    }

    private long balanceOf(Long userId) {
        return transactionTemplate.execute(status -> balanceInCurrentTransaction(userId));
    }

    private List<Object> orderStateOf(Long orderId) {
        return transactionTemplate.execute(status -> orderStateInCurrentTransaction(orderId));
    }

    private int stockInCurrentTransaction(Long productId) {
        return ((Number) entityManager.createNativeQuery("select stock from product where id = :id")
            .setParameter("id", productId)
            .getSingleResult()).intValue();
    }

    private long balanceInCurrentTransaction(Long userId) {
        return ((Number) entityManager.createNativeQuery("select balance from points where user_id = :userId")
            .setParameter("userId", userId)
            .getSingleResult()).longValue();
    }

    /** 주문 상태, 결제액(없으면 0), 결제 시각이 있는가 */
    private List<Object> orderStateInCurrentTransaction(Long orderId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("select status, payment_amount, paid_at from orders where id = :id")
            .setParameter("id", orderId)
            .getSingleResult();
        long paymentAmount = row[1] == null ? 0L : ((Number) row[1]).longValue();
        return List.of(row[0], paymentAmount, row[2] != null);
    }
}
