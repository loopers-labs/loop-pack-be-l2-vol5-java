package com.loopers.application.order;

import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.Product;
import com.loopers.support.pg.TestPgServer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import com.loopers.application.order.facade.OrderPaymentFacade;

/** TX A · TX B 가 각각 통째로 commit · rollback 되는지 확인함. 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 다시 읽음 */
@SpringBootTest
class OrderConfirmTransactionTest {

    private static final Long USER_ID = 1L;
    private static final int INITIAL_STOCK = 10;
    private static final ConfirmCommand COMMAND = new ConfirmCommand("attempt-1", "TEST", "0000-0000-0000-0000");

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @MockitoSpyBean
    private OrderService orderService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();
    private Product first;
    private Product second;
    private Order order;

    @BeforeEach
    void setUp() {
        pg.reset();
        transactionTemplate.executeWithoutResult(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            first = persistProduct(brand, "첫 번째 상품", 1_000L);
            second = persistProduct(brand, "두 번째 상품", 2_000L);
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

    @DisplayName("여러 품목의 주문으로 결제를 시작하면, 재고 확보 · 결제 시도 기록 · PAYMENT_PENDING 이 함께 반영된다.")
    @Test
    void appliesReservationAttemptAndPendingTogether() {
        // act
        orderPaymentFacade.confirm(USER_ID, order.getId(), COMMAND);

        // assert
        assertAll(
            () -> assertThat(stockOf(first.getId())).isEqualTo(INITIAL_STOCK - 2),
            () -> assertThat(stockOf(second.getId())).isEqualTo(INITIAL_STOCK - 1),
            () -> assertThat(orderStateOf(order.getId())).isEqualTo(List.of("PAYMENT_PENDING", "PG", 0L)),
            () -> assertThat(paymentStateOf(order.getId())).isEqualTo(List.of("PENDING", "RESERVED", 4_000L)),
            () -> assertThat(pg.createCount()).isEqualTo(1)
        );
    }

    @DisplayName("TX A 의 재고 · 결제 시도 · 주문 SQL 이 모두 나간 뒤 실패하면, 모두 변경 전과 같고 PG 를 부르지 않는다.")
    @Test
    void rollsBackStartAndDoesNotCallPg_whenFailsAfterChangesAreFlushed() {
        // arrange: 마지막 단계(PAYMENT_PENDING 변경)를 실제로 실행하고, 모든 변경을 DB 로 보낸 뒤 실패시킴
        AtomicReference<List<Object>> seenInsideTransaction = new AtomicReference<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            seenInsideTransaction.set(List.of(
                stockInCurrentTransaction(first.getId()),
                stockInCurrentTransaction(second.getId()),
                orderStateInCurrentTransaction(order.getId()),
                paymentCountInCurrentTransaction()
            ));
            throw new IllegalStateException("테스트에서 주입한 실패");
        }).when(orderService).startPayment(any(Order.class));

        // act
        assertThrows(IllegalStateException.class, () -> orderPaymentFacade.confirm(USER_ID, order.getId(), COMMAND));

        // assert
        assertAll(
            () -> assertThat(seenInsideTransaction.get()).containsExactly(
                INITIAL_STOCK - 2, INITIAL_STOCK - 1, List.of("PAYMENT_PENDING", "PG", 0L), 1L
            ),
            () -> assertThat(stockOf(first.getId())).isEqualTo(INITIAL_STOCK),
            () -> assertThat(stockOf(second.getId())).isEqualTo(INITIAL_STOCK),
            () -> assertThat(orderStateOf(order.getId())).isEqualTo(listOf("DRAFT", null, 0L)),
            () -> assertThat(paymentCount()).isZero(),
            () -> assertThat(pg.createCount()).isZero()
        );
    }

    @DisplayName("TX B 에서 반환 · 실패 반영 SQL 이 나간 뒤 실패하면, 결제 시도 · 예약 · 주문이 반영 전으로 남고 create 는 다시 보내지 않는다.")
    @Test
    void rollsBackSettlement_whenFailsAfterReleaseIsFlushed() {
        // arrange: 효과 0건 거절을 받아 반환까지 실제로 실행하고, DB 로 보낸 뒤 실패시킴
        pg.willRejectBeforeCreate();
        AtomicReference<List<Object>> seenInsideTransaction = new AtomicReference<>();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            seenInsideTransaction.set(List.of(
                stockInCurrentTransaction(first.getId()),
                orderStateInCurrentTransaction(order.getId()),
                paymentStateInCurrentTransaction(order.getId())
            ));
            throw new IllegalStateException("테스트에서 주입한 실패");
        }).when(orderService).failPayment(any(Order.class));

        // act
        assertThrows(IllegalStateException.class, () -> orderPaymentFacade.confirm(USER_ID, order.getId(), COMMAND));

        // assert
        assertAll(
            () -> assertThat(seenInsideTransaction.get()).containsExactly(
                INITIAL_STOCK, List.of("PAYMENT_FAILED", "PG", 0L), List.of("REJECTED", "RELEASED", 4_000L)
            ),
            () -> assertThat(stockOf(first.getId())).isEqualTo(INITIAL_STOCK - 2),
            () -> assertThat(stockOf(second.getId())).isEqualTo(INITIAL_STOCK - 1),
            () -> assertThat(orderStateOf(order.getId())).isEqualTo(List.of("PAYMENT_PENDING", "PG", 0L)),
            () -> assertThat(paymentStateOf(order.getId())).isEqualTo(List.of("REQUESTED", "RESERVED", 4_000L)),
            () -> assertThat(pg.createCount()).isEqualTo(1)
        );
    }

    @DisplayName("외부 결제 성공 뒤 내부 확정 저장이 실패하면, 내부는 반영 전으로 남고 같은 거래를 다시 조회해 한 번 확정하며 create 는 다시 보내지 않는다.")
    @Test
    void confirmsOnceByLookingUpAgain_whenInternalSaveFailsAfterExternalSuccess() {
        // arrange: 결제 시작 → PG 거래 SUCCESS → 첫 결과 확인의 확정 저장만 실패시킴
        orderPaymentFacade.confirm(USER_ID, order.getId(), COMMAND);
        String transactionKey = pg.transactionsOf(Payment.providerOrderIdOf(order.getId())).get(0).transactionKey();
        pg.complete(transactionKey, "SUCCESS");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            throw new IllegalStateException("테스트에서 주입한 실패");
        }).doCallRealMethod().when(orderService).confirmPaid(any(Order.class), anyLong());

        // act
        assertThrows(IllegalStateException.class, () -> orderPaymentFacade.reconcile(USER_ID, order.getId()));
        List<Object> afterFailure = List.of(orderStateOf(order.getId()), paymentStateOf(order.getId()));
        OrderInfo retried = orderPaymentFacade.reconcile(USER_ID, order.getId());

        // assert
        assertAll(
            () -> assertThat(afterFailure).containsExactly(List.of("PAYMENT_PENDING", "PG", 0L), List.of("PENDING", "RESERVED", 4_000L)),
            () -> assertThat(retried.status()).isEqualTo("CONFIRMED"),
            () -> assertThat(orderStateOf(order.getId())).isEqualTo(List.of("CONFIRMED", "PG", 4_000L)),
            () -> assertThat(paymentStateOf(order.getId())).isEqualTo(List.of("SUCCESS", "CONSUMED", 4_000L)),
            () -> assertThat(stockOf(first.getId())).isEqualTo(INITIAL_STOCK - 2),
            () -> assertThat(pg.createCount()).isEqualTo(1)
        );
    }

    private Product persistProduct(Brand brand, String name, long price) {
        Product product = new Product(brand, name, price);
        product.changeStock(INITIAL_STOCK);
        entityManager.persist(product);
        return product;
    }

    private static List<Object> listOf(Object... values) {
        List<Object> list = new ArrayList<>();
        for (Object value : values) {
            list.add(value);
        }
        return list;
    }

    /** 서비스 트랜잭션이 끝난 뒤 새 트랜잭션에서 DB 를 다시 읽음 */
    private int stockOf(Long productId) {
        return transactionTemplate.execute(status -> stockInCurrentTransaction(productId));
    }

    private List<Object> orderStateOf(Long orderId) {
        return transactionTemplate.execute(status -> orderStateInCurrentTransaction(orderId));
    }

    private List<Object> paymentStateOf(Long orderId) {
        return transactionTemplate.execute(status -> paymentStateInCurrentTransaction(orderId));
    }

    private long paymentCount() {
        Long count = transactionTemplate.execute(status -> paymentCountInCurrentTransaction());
        return count == null ? 0L : count;
    }

    private int stockInCurrentTransaction(Long productId) {
        return ((Number) entityManager.createNativeQuery("select stock from product where id = :id")
            .setParameter("id", productId)
            .getSingleResult()).intValue();
    }

    /** 주문 상태, 결제수단, 결제액(없으면 0) */
    private List<Object> orderStateInCurrentTransaction(Long orderId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("select status, payment_method, payment_amount from orders where id = :id")
            .setParameter("id", orderId)
            .getSingleResult();
        long paymentAmount = row[2] == null ? 0L : ((Number) row[2]).longValue();
        return listOf(row[0], row[1], paymentAmount);
    }

    /** 결제 상태, 예약 상태, 금액 */
    private List<Object> paymentStateInCurrentTransaction(Long orderId) {
        Object[] row = (Object[]) entityManager.createNativeQuery("select status, reservation_status, amount from payment where order_id = :id")
            .setParameter("id", orderId)
            .getSingleResult();
        return List.of(row[0], row[1], ((Number) row[2]).longValue());
    }

    private long paymentCountInCurrentTransaction() {
        return ((Number) entityManager.createNativeQuery("select count(*) from payment").getSingleResult()).longValue();
    }
}
