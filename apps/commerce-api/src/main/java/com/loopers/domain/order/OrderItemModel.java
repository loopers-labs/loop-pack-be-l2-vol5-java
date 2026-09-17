package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 주문 당시의 상품·수량·단가를 보관한다.
 * 상품 정보가 바뀌어도 이미 저장된 수량과 단가는 변경되지 않는다.
 */
@Entity
@Table(name = "order_item")
@Getter
public class OrderItemModel extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private long quantity;

    @Embedded
    @AttributeOverride(name = "won", column = @Column(name = "unit_price", nullable = false))
    private Money unitPrice;

    protected OrderItemModel() {}

    private OrderItemModel(Long productId, long quantity, Money unitPrice) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public static OrderItemModel of(Long productId, long quantity, Money unitPrice) {
        if (quantity < 1L) {
            throw new CoreException(ErrorType.INVALID_ORDER_QUANTITY);
        }
        return new OrderItemModel(productId, quantity, unitPrice);
    }

    public Money calculateAmount() {
        return unitPrice.multiply(quantity);
    }
}
