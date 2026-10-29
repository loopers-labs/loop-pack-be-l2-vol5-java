package com.loopers.infrastructure.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 주문 종결 UPDATE(finishIfPaymentPending)가 confirmedAt 을 "처음 확정될 때 한 번만" 기록하는지 실제 MySQL 로 확인한다 (6주차 설계 D2·D3).
 */
@SpringBootTest
class OrderRepositoryTest {

    private static final ZonedDateTime AT = ZonedDateTime.parse("2026-09-07T01:00:00.123Z");
    private static final ZonedDateTime LATER = AT.plusSeconds(1);

    private final OrderRepository orderRepository;
    private final EntityManager entityManager;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    OrderRepositoryTest(OrderRepository orderRepository, EntityManager entityManager, DatabaseCleanUp databaseCleanUp) {
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Long savePaymentPendingOrder() {
        Order order = new Order(1L, List.of(new OrderItem(7L, 2, 1000L)));
        order.startPayment();
        Long orderId = orderRepository.save(order).getId();
        entityManager.flush();
        entityManager.clear();
        return orderId;
    }

    private Order reload(Long orderId) {
        entityManager.clear();
        return orderRepository.findById(orderId).orElseThrow();
    }

    @DisplayName("PAYMENT_PENDING 주문을 종결할 때, ")
    @Nested
    class FinishIfPaymentPending {
        @DisplayName("CONFIRMED 로 바꾸면 1행이고 confirmedAt·updatedAt 이 넘긴 시각과 같다.")
        @Transactional
        @Test
        void recordsConfirmedAt_whenConfirmedFirst() {
            Long orderId = savePaymentPendingOrder();

            int rows = orderRepository.finishIfPaymentPending(orderId, Order.OrderStatus.CONFIRMED, 2000L, AT);

            Order order = reload(orderId);
            assertThat(rows).isEqualTo(1);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
            assertThat(order.getPaidAmount()).isEqualTo(2000L);
            assertThat(order.getConfirmedAt().toInstant()).isEqualTo(AT.toInstant());
            assertThat(order.getUpdatedAt().toInstant()).isEqualTo(AT.toInstant());
        }

        @DisplayName("이미 CONFIRMED 인 주문을 다시 종결하면 0행이고 confirmedAt 은 처음 값 그대로다 (중복 callback 이 덮어쓰지 못함).")
        @Transactional
        @Test
        void keepsFirstConfirmedAt_whenConfirmedAgain() {
            Long orderId = savePaymentPendingOrder();
            orderRepository.finishIfPaymentPending(orderId, Order.OrderStatus.CONFIRMED, 2000L, AT);

            int rows = orderRepository.finishIfPaymentPending(orderId, Order.OrderStatus.CONFIRMED, 2000L, LATER);

            Order order = reload(orderId);
            assertThat(rows).isZero();
            assertThat(order.getConfirmedAt().toInstant()).isEqualTo(AT.toInstant());
            assertThat(order.getUpdatedAt().toInstant()).isEqualTo(AT.toInstant());
        }

        @DisplayName("PAYMENT_FAILED 로 바꾸면 updatedAt 만 기록되고 confirmedAt 은 null 이다.")
        @Transactional
        @Test
        void leavesConfirmedAtNull_whenFailed() {
            Long orderId = savePaymentPendingOrder();

            int rows = orderRepository.finishIfPaymentPending(orderId, Order.OrderStatus.PAYMENT_FAILED, 0L, AT);

            Order order = reload(orderId);
            assertThat(rows).isEqualTo(1);
            assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_FAILED);
            assertThat(order.getConfirmedAt()).isNull();
            assertThat(order.getUpdatedAt().toInstant()).isEqualTo(AT.toInstant());
        }
    }
}
