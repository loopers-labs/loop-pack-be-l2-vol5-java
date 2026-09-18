package com.loopers.order.domain;

import com.loopers.product.domain.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Embeddable;

@Embeddable
public class OrderItem {

    private Long productId;
    private String productName;
    private int quantity;
    private long unitPrice;

    protected OrderItem() {
    }

    public OrderItem(Long productId, String productName, int quantity, long unitPrice) {
        if (quantity <= 0) {
            throw new CoreException(ErrorCode.INVALID_ORDER_QUANTITY);
        }
        this.productId = productId;
        this.productName = productName;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public static OrderItem of(Product product, int quantity) {
        return new OrderItem(product.getId(), product.getName(), quantity, product.getPrice());
    }

    public Long productId() {
        return productId;
    }

    public String productName() {
        return productName;
    }

    public int quantity() {
        return quantity;
    }

    public long unitPrice() {
        return unitPrice;
    }

    public long amount() {
        return unitPrice * quantity;
    }

    public OrderItem withQuantity(int quantity) {
        return new OrderItem(productId, productName, quantity, unitPrice);
    }
}
