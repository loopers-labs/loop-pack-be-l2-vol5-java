package com.loopers.infrastructure.persistence.ordering.entity;

import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderRecord;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
// 주문 엔티티와 도메인 모델 변환
public class OrderEntityMapper {
    // 엔티티를 도메인으로 변환
    public Order toDomain(OrderJpaEntity entity) {
        List<OrderItem> items = entity.getItems().stream()
            .map(item -> OrderItem.restore(item.getProductId(), item.getProductName(), item.getUnitPrice(),
                item.getQuantity(), item.getAmount()))
            .toList();
        OrderRecord record = entity.getRecord() == null ? null : toRecordDomain(entity.getRecord());
        return Order.restore(entity.getId(), entity.getUserId(), entity.getStatus(), items, entity.getTotalAmount(),
            entity.getCreatedAt(), record);
    }

    // 도메인을 신규 엔티티로 변환
    public OrderJpaEntity toNewEntity(Order order) {
        OrderJpaEntity entity = new OrderJpaEntity(order.getUserId(), order.getStatus(), order.getTotalAmount());
        for (OrderItem item : order.getItems()) {
            entity.addItem(new OrderItemJpaEntity(item.getProductId(), item.getProductName(), item.getUnitPrice(),
                item.getQuantity(), item.getAmount()));
        }
        order.getRecord().ifPresent(record -> entity.assignRecord(toNewRecordEntity(record)));
        return entity;
    }

    // 도메인 상태를 기존 엔티티에 반영
    public void apply(Order order, OrderJpaEntity entity) {
        entity.apply(order.getStatus());
        if (entity.getRecord() == null) {
            order.getRecord().ifPresent(record -> entity.assignRecord(toNewRecordEntity(record)));
        }
    }

    // 주문 기록 엔티티를 도메인으로 변환
    private OrderRecord toRecordDomain(OrderRecordJpaEntity entity) {
        return OrderRecord.restore(entity.getId(), entity.getUserId(), entity.getAmount(), entity.getStatus(),
            entity.getCreatedAt());
    }

    // 주문 기록 도메인을 신규 엔티티로 변환
    private OrderRecordJpaEntity toNewRecordEntity(OrderRecord record) {
        return new OrderRecordJpaEntity(record.getUserId(), record.getAmount(), record.getStatus());
    }
}
