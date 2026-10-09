package com.loopers.infrastructure.persistence.ordering.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
class OrderRepositoryIntegrationTest {
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private EntityManager entityManager;

    @DisplayName("주문과 품목을 저장하고 영속성 컨텍스트를 비워도 스냅샷과 합계를 보존한다")
    @Test
    @Transactional
    void savesOrder_withItemsAndTotalAmount() {
        List<OrderItem> items = List.of(
            OrderItem.create(1L, "상품1", 1_000L, 2),
            OrderItem.create(2L, "상품2", 3_000L, 1)
        );
        Order order = orderRepository.save(Order.create(1L, items));
        entityManager.flush();
        entityManager.clear();

        Order restored = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(restored.getUserId()).isEqualTo(1L);
        assertThat(restored.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(restored.getTotalAmount()).isEqualTo(5_000L);
        assertThat(restored.getItems()).hasSize(2);
        assertThat(restored.getItems().get(0).getProductName()).isEqualTo("상품1");
        assertThat(restored.getItems().get(0).getAmount()).isEqualTo(2_000L);
        assertThat(restored.getItems().get(1).getAmount()).isEqualTo(3_000L);
    }

    @DisplayName("없는 주문은 빈 결과를 반환한다")
    @Test
    @Transactional
    void returnsEmpty_whenOrderDoesNotExist() {
        assertThat(orderRepository.findById(999L)).isEmpty();
    }

    @DisplayName("확정된 주문을 저장하면 주문 기록이 cascade로 함께 저장되고 복원된다")
    @Test
    @Transactional
    void savesConfirmedOrder_withCascadedOrderRecord() {
        Order order = orderRepository.save(Order.create(1L, List.of(OrderItem.create(1L, "상품", 1_000L, 2))));
        order.confirm();
        Order confirmed = orderRepository.save(order);
        entityManager.flush();
        entityManager.clear();

        Order restored = orderRepository.findById(confirmed.getId()).orElseThrow();

        OrderRecord record = restored.getRecord().orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(record.getUserId()).isEqualTo(1L);
        assertThat(record.getAmount()).isEqualTo(2_000L);
        assertThat(record.getStatus()).isEqualTo(OrderRecordStatus.PAID);
    }
}
