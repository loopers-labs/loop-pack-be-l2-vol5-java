package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_item")
public class OrderItem extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private Long quantity;

    @Column(name = "unit_price", nullable = false)
    private Long unitPrice;

    protected OrderItem() {}

    public OrderItem(Long productId, Long quantity, Long unitPrice) {
        if (productId == null) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "주문 품목의 상품은 필수입니다.");
        }
        validateQuantity(quantity);
        validateUnitPrice(unitPrice);
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    private static void validateQuantity(Long quantity) {
        if (quantity == null || quantity <= 0) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "주문 수량은 1 이상이어야 합니다.");
        }
    }

    private static void validateUnitPrice(Long unitPrice) {
        if (unitPrice == null || unitPrice < 0) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "주문 단가는 0 이상이어야 합니다.");
        }
    }

    public Long getProductId() {
        return productId;
    }

    public Long getQuantity() {
        return quantity;
    }

    public Long getUnitPrice() {
        return unitPrice;
    }

    public long getAmount() {
        return Math.multiplyExact(unitPrice, quantity);
    }

    void addQuantity(long quantity) {
        validateQuantity(quantity);
        this.quantity = Math.addExact(this.quantity, quantity);
    }

    void changeUnitPrice(long unitPrice) {
        validateUnitPrice(unitPrice);
        this.unitPrice = unitPrice;
    }
}
