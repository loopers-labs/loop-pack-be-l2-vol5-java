package com.loopers.order.application;

import com.loopers.order.domain.Order;
import com.loopers.order.domain.OrderItem;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.order.domain.PaymentGateway.ApprovalRequest;
import com.loopers.order.domain.PaymentStatus;
import com.loopers.order.domain.StockReservationStatus;
import com.loopers.order.infrastructure.outbox.OrderConfirmedOutboxRepository;
import com.loopers.product.domain.Product;
import com.loopers.product.domain.Stock;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.Point;
import com.loopers.user.domain.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@DisplayName("OrderConfirmed Outbox와 결제 TXB")
class OrderPaymentOutboxTransactionIntegrationTest {

    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-11-04T09:00:00+09:00");

    @Autowired private OrderPaymentTransactionService transactionService;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private OrderConfirmedOutboxRepository outboxRepository;

    @Test
    @DisplayName("Outbox insert가 실패하면 결제 성공·주문 확정·예약 소비를 포함한 TXB를 롤백한다")
    void rollsBackTransactionBWhenOutboxInsertFails() {
        Scenario scenario = createScenario();
        OrderPaymentTransactionService.DispatchPlan plan = transactionService.prepare(
            scenario.buyerId(),
            scenario.orderId(),
            "payment-" + UUID.randomUUID(),
            "attempt-" + UUID.randomUUID()
        );
        long reservedStock = readStockQuantity(scenario.firstProductId());
        doThrow(new DataIntegrityViolationException("simulated outbox insert failure"))
            .when(outboxRepository).saveAndFlush(any());

        assertThatThrownBy(() -> transactionService.recordResult(
            scenario.orderId(), approved(plan.request())))
            .isInstanceOf(DataIntegrityViolationException.class);

        entityManager.clear();
        Order rolledBack = entityManager.find(Order.class, scenario.orderId());
        Stock stock = entityManager.find(Stock.class, scenario.firstProductId());

        assertThat(rolledBack.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(rolledBack.getPayment().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(rolledBack.getStockReservationStatus()).isEqualTo(StockReservationStatus.RESERVED);
        assertThat(stock.quantity()).isEqualTo(reservedStock);
        verify(outboxRepository).saveAndFlush(any());
    }

    private Scenario createScenario() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return transaction.execute(status -> {
            Product product = new Product(1L, "outbox rollback " + UUID.randomUUID(), 2_000L);
            entityManager.persist(product);
            entityManager.flush();
            entityManager.persist(new Stock(product.getId(), 5));
            User buyer = new User();
            entityManager.persist(buyer);
            entityManager.flush();
            entityManager.persist(new Point(buyer.getId(), 20_000L));
            Order order = new Order(buyer.getId(), List.of(OrderItem.of(product, 2)));
            entityManager.persist(order);
            entityManager.flush();
            return new Scenario(buyer.getId(), order.getId(), product.getId());
        });
    }

    private long readStockQuantity(Long productId) {
        entityManager.clear();
        return entityManager.find(Stock.class, productId).quantity();
    }

    private static PaymentGateway.Approved approved(ApprovalRequest request) {
        return new PaymentGateway.Approved(
            request.paymentKey(), request.providerOrderId(), request.amount(), APPROVED_AT);
    }

    private record Scenario(Long buyerId, Long orderId, Long firstProductId) {
    }
}
