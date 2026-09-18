package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class OrderItem {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    protected OrderItem() {
    }

    public OrderItem(Long productId, int quantity, Long unitPrice) {
        if (productId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품은 비어있을 수 없습니다.");
        }
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "수량은 0보다 커야 합니다.");
        }
        if (unitPrice == null || unitPrice <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "단가는 0보다 커야 합니다.");
        }
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public Long getSubtotal() {
        return unitPrice * quantity;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public Long getUnitPrice() {
        return unitPrice;
    }
}
