package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 주문 시점의 수량과 단가를 스냅샷으로 기록한다.
 * 이후 상품 가격이 바뀌어도 이 단가는 영향받지 않는다.
 */
@Getter
@Entity
@Table(name = "order_items")
public class OrderItem extends BaseEntity {

    private Long productId;

    private int quantity;

    private long unitPrice;

    protected OrderItem() {}

    public OrderItem(Long productId, int quantity, long unitPrice) {
        if (productId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목의 상품 식별자는 필수입니다.");
        }
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 수량은 1개 이상이어야 합니다.");
        }
        if (unitPrice < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 단가는 0원 이상이어야 합니다.");
        }

        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long getAmount() {
        return unitPrice * quantity;
    }

    /**
     * 같은 상품이 여러 품목으로 들어왔을 때 수량을 합산한다.
     */
    public OrderItem mergeQuantity(int additionalQuantity) {
        return new OrderItem(productId, quantity + additionalQuantity, unitPrice);
    }
}
