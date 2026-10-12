package com.loopers.order.adapter.out.persistence;

import com.loopers.common.domain.Money;
import com.loopers.order.application.port.out.OrderPort;
import com.loopers.order.domain.OrderItemModel;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.product.domain.ProductSnapshot;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@Transactional
class OrderPersistenceAdapterIntegrationTest {

    @Autowired
    private OrderPort orderPort;

    @Autowired
    private EntityManager entityManager;

    @DisplayName("ORD-01 주문을 저장하면 품목이 함께 저장되고, 다시 읽으면 상품명·단가·수량·합계·상태가 그대로다.")
    @Test
    void savesOrderWithItemsByCascade() {
        // arrange
        OrderModel order = OrderModel.create(
            7L,
            OrderLines.of(List.of(new OrderLines.Line(1L, 5), new OrderLines.Line(2L, 1))),
            Map.of(
                1L, new ProductSnapshot(1L, "에어맥스", Money.of(1_000)),
                2L, new ProductSnapshot(2L, "에어포스", Money.of(2_000))
            )
        );

        // act
        OrderModel saved = orderPort.save(order);
        entityManager.flush();
        entityManager.clear();
        OrderModel reloaded = orderPort.findById(saved.getId()).orElseThrow();

        // assert
        assertThat(reloaded.getUserId()).isEqualTo(7L);
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(reloaded.getTotalAmount()).isEqualTo(Money.of(7_000));
        assertThat(reloaded.getPaidAmount()).isNull();
        assertThat(reloaded.getItems())
            .extracting(OrderItemModel::getProductId, OrderItemModel::getProductName, OrderItemModel::getUnitPrice, OrderItemModel::getQuantity)
            .containsExactlyInAnyOrder(
                tuple(1L, "에어맥스", Money.of(1_000), 5),
                tuple(2L, "에어포스", Money.of(2_000), 1)
            );
        assertThat(reloaded.getItems()).allSatisfy(item -> assertThat(item.getId()).isNotNull());
    }
}
