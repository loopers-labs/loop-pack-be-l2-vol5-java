package com.loopers.application.order.listener;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.gateway.OrderEventPublisher;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.pg.TestPgServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** AFTER_COMMIT listener 가 언제 포트를 부르고, 포트의 예외가 어디까지 번지는지 (6주차 설계 3.3 · 3.4) */
@SpringBootTest
class OrderConfirmedListenerTest {

    private static final Long USER_ID = 1L;
    private static final OrderConfirmed EVENT = new OrderConfirmed("order-confirmed:v1:999", 999L,
        ZonedDateTime.parse("2026-09-07T01:00:00Z"), List.of(new OrderConfirmed.Item(7L, 1)));

    @MockitoSpyBean
    private OrderEventPublisher orderEventPublisher;

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("트랜잭션 밖에서 낸 확정 사실은 listener 가 실행되지 않아 보내지 않는다.")
    @Test
    void doesNotPublish_outsideTransaction() {
        // act
        applicationEventPublisher.publishEvent(EVENT);

        // assert
        verify(orderEventPublisher, never()).publish(any());
    }

    @DisplayName("트랜잭션이 commit 되면 한 번 보내고, rollback 되면 보내지 않는다.")
    @Test
    void publishesOnlyAfterCommit() {
        // act
        transactionTemplate.executeWithoutResult(status -> {
            applicationEventPublisher.publishEvent(EVENT);
            // commit 전에는 아직 보내지 않음
            verify(orderEventPublisher, never()).publish(any());
        });
        transactionTemplate.executeWithoutResult(status -> {
            applicationEventPublisher.publishEvent(EVENT);
            status.setRollbackOnly();
        });

        // assert
        verify(orderEventPublisher, times(1)).publish(EVENT);
    }

    @DisplayName("포트 자체가 예외를 던져도 이미 commit 된 확정은 정상 응답한다.")
    @Test
    void keepsResponse_whenPublisherThrows() {
        // arrange
        Order order = transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            Product product = new Product(brand, "상품", 1_000L);
            product.changeStock(10);
            entityManager.persist(product);
            Order draft = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), 1)),
                ZonedDateTime.now());
            entityManager.persist(draft);
            return draft;
        });
        pg.reset();
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
        pg.complete(pg.transactionsOf(Payment.providerOrderIdOf(order.getId())).get(0).transactionKey(), "SUCCESS");
        doThrow(new IllegalStateException("주입한 포트 예외")).when(orderEventPublisher).publish(any());

        // act
        OrderInfo result = orderPaymentFacade.reconcile(USER_ID, order.getId());

        // assert
        String status = (String) transactionTemplate.execute(tx ->
            entityManager.createNativeQuery("select status from orders where id = " + order.getId()).getSingleResult());
        assertAll(
            () -> assertThat(result.status()).isEqualTo("CONFIRMED"),
            () -> assertThat(status).isEqualTo("CONFIRMED")
        );
    }
}
