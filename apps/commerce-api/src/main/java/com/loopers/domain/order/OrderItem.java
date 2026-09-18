package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.Getter;

@Getter
public final class OrderItem {
    private final long productId;
    private final int quantity;
    private final long unitPrice;
    private final long lineAmount;

    private OrderItem(long productId, int quantity, long unitPrice, long lineAmount) {
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.lineAmount = lineAmount;
    }
    public static OrderItem create(long productId, int quantity, long unitPrice) {
        if (productId <= 0 || quantity <= 0 || unitPrice <= 0) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        try {
            return new OrderItem(productId, quantity, unitPrice, Math.multiplyExact(unitPrice, (long) quantity));
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorType.ORDER_AMOUNT_OVERFLOW);
        }
    }
    public static OrderItem restore(long productId, int quantity, long unitPrice, long lineAmount) {
        return new OrderItem(productId, quantity, unitPrice, lineAmount);
    }
}
