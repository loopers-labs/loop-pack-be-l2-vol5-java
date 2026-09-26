package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class OrderItem {

    private Long productId;

    private String productName;

    private long unitPrice;

    private int quantity;

    private OrderItem(Long productId, String productName, long unitPrice, int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 수량은 양수여야 합니다.");
        }
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public static OrderItem create(Long productId, String productName, long unitPrice, int quantity) {
        return new OrderItem(productId, productName, unitPrice, quantity);
    }

    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public long getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public long getAmount() { return unitPrice * quantity; }

    void addQuantity(int additionalQuantity) {
        if (additionalQuantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 수량은 양수여야 합니다.");
        }
        quantity += additionalQuantity;
    }
}
