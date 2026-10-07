package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public record OrderItem(Long productId, String productName, long unitPrice, int quantity) {

    public OrderItem {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 수량은 양수여야 합니다.");
        }
    }

    public static OrderItem create(Long productId, String productName, long unitPrice, int quantity) {
        return new OrderItem(productId, productName, unitPrice, quantity);
    }

    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public long getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public long getAmount() { return unitPrice * quantity; }

    OrderItem addQuantity(int additionalQuantity) {
        if (additionalQuantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 수량은 양수여야 합니다.");
        }
        try {
            return create(productId, productName, unitPrice, Math.addExact(quantity, additionalQuantity));
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorType.BAD_REQUEST, "합산 주문 수량이 저장 가능한 범위를 초과했습니다.");
        }
    }
}
