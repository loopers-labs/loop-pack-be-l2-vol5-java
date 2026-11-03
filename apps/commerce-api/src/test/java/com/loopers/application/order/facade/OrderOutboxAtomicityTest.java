package com.loopers.application.order.facade;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.gateway.OrderConfirmedSerializer;
import com.loopers.domain.order.repository.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * 7주차 설계 3.4 의 로컬 원자성. 확정 · 예약 소비 · Outbox 가 한 TX 로 commit · rollback 되는지 실제 MySQL 에서 확인함.
 * rollback 은 주문을 지우는 것이 아니라 TX 직전 상태로 돌리는 것이라, 직전 상태를 값으로 비교함
 */
@SpringBootTest
class OrderOutboxAtomicityTest {

    private static final Long USER_ID = 1L;
    private static final int INITIAL_STOCK = 10;
    private static final ConfirmCommand COMMAND = new ConfirmCommand("attempt-1", "TEST", "0000-0000-0000-0000");

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @MockitoSpyBean
    private OrderConfirmedSerializer serializer;

    @MockitoSpyBean
    private OrderConfirmedOutboxRepository outboxRepository;

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

    @DisplayName("PG SUCCESS 반영 중 payload 직렬화가 실패하면, 주문 · 결제 시도 · 예약이 반영 전으로 남고 Outbox 는 없으며, 같은 거래 재조회로 한 번씩 확정 · 기록한다.")
    @Test
    void rollsBackAndRecordsOnceOnRetry_whenSerializationFails() {
        // arrange: 결제 시작 → PG 거래 SUCCESS → 첫 결과 적용의 직렬화만 실패시킴
        Product product = persistProduct(1_000L);
        Order order = persistDraft(product, 2);
        orderPaymentFacade.confirm(USER_ID, order.getId(), COMMAND);
        pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
        doThrow(new IllegalStateException("테스트에서 주입한 직렬화 실패"))
            .doCallRealMethod().when(serializer).serialize(any(OrderConfirmed.class));

        // act
        assertThrows(IllegalStateException.class, () -> orderPaymentFacade.reconcile(USER_ID, order.getId()));
        List<Object> afterFailure = List.of(orderStateOf(order.getId()), paymentStateOf(order.getId()), outboxCountOf(order.getId()));
        OrderInfo retried = orderPaymentFacade.reconcile(USER_ID, order.getId());

        // assert
        assertAll(
            () -> assertThat(afterFailure).containsExactly(List.of("PAYMENT_PENDING", "PG"), List.of("PENDING", "RESERVED"), 0L),
            () -> assertThat(retried.status()).isEqualTo("CONFIRMED"),
            () -> assertThat(paymentStateOf(order.getId())).isEqualTo(List.of("SUCCESS", "CONSUMED")),
            () -> assertThat(outboxCountOf(order.getId())).isEqualTo(1L),
            () -> assertThat(stockOf(product.getId())).isEqualTo(INITIAL_STOCK - 2),
            () -> assertThat(pg.createCount()).isEqualTo(1)
        );
    }

    @DisplayName("Outbox insert SQL 이 나간 뒤 commit 전에 실패해도, 확정 · 예약 소비 · Outbox 가 함께 rollback 되고 재조회로 한 번 기록한다.")
    @Test
    void rollsBackFlushedOutbox_whenFailsAfterInsert() {
        // arrange
        Order order = persistDraft(persistProduct(1_000L), 1);
        orderPaymentFacade.confirm(USER_ID, order.getId(), COMMAND);
        pg.complete(onlyTransactionKeyOf(order), "SUCCESS");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            entityManager.flush();
            throw new IllegalStateException("테스트에서 주입한 실패");
        }).doCallRealMethod().when(outboxRepository).save(any(OrderConfirmedOutbox.class));

        // act
        assertThrows(IllegalStateException.class, () -> orderPaymentFacade.reconcile(USER_ID, order.getId()));
        List<Object> afterFailure = List.of(orderStateOf(order.getId()), paymentStateOf(order.getId()), outboxCountOf(order.getId()));
        orderPaymentFacade.reconcile(USER_ID, order.getId());

        // assert
        assertAll(
            () -> assertThat(afterFailure).containsExactly(List.of("PAYMENT_PENDING", "PG"), List.of("PENDING", "RESERVED"), 0L),
            () -> assertThat(orderStateOf(order.getId())).isEqualTo(List.of("CONFIRMED", "PG")),
            () -> assertThat(outboxCountOf(order.getId())).isEqualTo(1L),
            () -> assertThat(pg.createCount()).isEqualTo(1)
        );
    }

    @DisplayName("0원 주문 확정 중 Outbox 기록이 실패하면, 주문은 DRAFT · 재고는 그대로 · 결제 기록과 Outbox 는 없고 PG 를 부르지 않는다.")
    @Test
    void rollsBackZeroAmountConfirmation_whenOutboxFails() {
        // arrange
        Product product = persistProduct(0L);
        Order order = persistDraft(product, 3);
        doThrow(new IllegalStateException("테스트에서 주입한 직렬화 실패"))
            .when(serializer).serialize(any(OrderConfirmed.class));

        // act
        assertThrows(IllegalStateException.class, () -> orderPaymentFacade.confirm(USER_ID, order.getId(), COMMAND));

        // assert
        assertAll(
            () -> assertThat(orderStateOf(order.getId())).isEqualTo(listOf("DRAFT", null)),
            () -> assertThat(stockOf(product.getId())).isEqualTo(INITIAL_STOCK),
            () -> assertThat(paymentCount()).isZero(),
            () -> assertThat(outboxCountOf(order.getId())).isZero(),
            () -> assertThat(pg.createCount()).isZero()
        );
    }

    private Product persistProduct(long price) {
        return transactionTemplate.execute(status -> {
            Product product = new Product(brand, "상품", price);
            product.changeStock(INITIAL_STOCK);
            entityManager.persist(product);
            return product;
        });
    }

    private Order persistDraft(Product product, int quantity) {
        return transactionTemplate.execute(status -> {
            Order order = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), quantity)),
                ZonedDateTime.now());
            entityManager.persist(order);
            return order;
        });
    }

    private String onlyTransactionKeyOf(Order order) {
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(Payment.providerOrderIdOf(order.getId()));
        assertThat(transactions).hasSize(1);
        return transactions.get(0).transactionKey();
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
        return transactionTemplate.execute(status -> ((Number) entityManager.createNativeQuery("select stock from product where id = :id")
            .setParameter("id", productId)
            .getSingleResult()).intValue());
    }

    /** 주문 상태, 결제수단 */
    private List<Object> orderStateOf(Long orderId) {
        return transactionTemplate.execute(status -> {
            Object[] row = (Object[]) entityManager.createNativeQuery("select status, payment_method from orders where id = :id")
                .setParameter("id", orderId)
                .getSingleResult();
            return listOf(row[0], row[1]);
        });
    }

    /** 결제 상태, 예약 상태 */
    private List<Object> paymentStateOf(Long orderId) {
        return transactionTemplate.execute(status -> {
            Object[] row = (Object[]) entityManager.createNativeQuery("select status, reservation_status from payment where order_id = :id")
                .setParameter("id", orderId)
                .getSingleResult();
            return List.of(row[0], row[1]);
        });
    }

    private long paymentCount() {
        return transactionTemplate.execute(status ->
            ((Number) entityManager.createNativeQuery("select count(*) from payment").getSingleResult()).longValue());
    }

    private long outboxCountOf(Long orderId) {
        return transactionTemplate.execute(status ->
            ((Number) entityManager.createNativeQuery("select count(*) from order_confirmed_outbox where order_id = :id")
                .setParameter("id", orderId)
                .getSingleResult()).longValue());
    }
}
