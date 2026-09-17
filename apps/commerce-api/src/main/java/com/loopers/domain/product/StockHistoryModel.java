package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 재고 변경의 원인과 결과를 남기는 기록. 현재 재고의 기준은 Product 가 소유한 Stock 이다.
 */
@Entity
@Table(name = "stock_history")
@Getter
public class StockHistoryModel extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "before_quantity", nullable = false)
    private long beforeQuantity;

    @Column(name = "after_quantity", nullable = false)
    private long afterQuantity;

    @Column(name = "changed_quantity", nullable = false)
    private long changedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockChangeCause cause;

    @Column(name = "order_id")
    private Long orderId;

    protected StockHistoryModel() {}

    private StockHistoryModel(Long productId, StockChange change, StockChangeCause cause, Long orderId) {
        this.productId = productId;
        this.beforeQuantity = change.beforeQuantity();
        this.afterQuantity = change.afterQuantity();
        this.changedQuantity = change.changedQuantity();
        this.cause = cause;
        this.orderId = orderId;
    }

    public static StockHistoryModel changedByAdmin(Long productId, StockChange change) {
        return new StockHistoryModel(productId, change, StockChangeCause.ADMIN_CHANGE, null);
    }

    public static StockHistoryModel deductedByOrder(Long productId, Long orderId, StockChange change) {
        return new StockHistoryModel(productId, change, StockChangeCause.ORDER_DEDUCTION, orderId);
    }
}
