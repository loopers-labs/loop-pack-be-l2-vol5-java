package com.loopers.infrastructure.persistence.mall.entity;

import com.loopers.domain.mall.model.StockAdjustment;
import org.springframework.stereotype.Component;

@Component
public class StockAdjustmentEntityMapper {
    public StockAdjustmentJpaEntity toNewEntity(StockAdjustment adjustment) {
        StockAdjustmentJpaEntity entity = new StockAdjustmentJpaEntity();
        entity.orderId = adjustment.getOrderId();
        entity.state = adjustment.getState().name();
        entity.createdAt = adjustment.getCreatedAt();
        adjustment.getItems().forEach(item -> entity.items.add(
                new StockAdjustmentItemJpaEntity(entity, item.productId(), item.quantity())));
        return entity;
    }

    public StockAdjustment toDomain(StockAdjustmentJpaEntity entity) {
        return StockAdjustment.restore(entity.orderId, entity.items.stream()
                        .map(item -> new StockAdjustment.Item(item.productId, item.quantity)).toList(),
                StockAdjustment.State.valueOf(entity.state), entity.version, entity.createdAt);
    }

    public long version(StockAdjustmentJpaEntity entity) {
        return entity.version;
    }

    public void apply(StockAdjustment adjustment, StockAdjustmentJpaEntity entity) {
        entity.state = adjustment.getState().name();
    }
}
