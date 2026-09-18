package com.loopers.domain.order;

import com.loopers.domain.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "order_item", uniqueConstraints = @UniqueConstraint(columnNames = {"order_id", "product_id"}))
public class OrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "product_name_snapshot", nullable = false, length = 100, updatable = false)
    private String productName;

    @Column(name = "unit_price_snapshot", nullable = false, updatable = false)
    private long unitPrice;

    @Column(name = "quantity", nullable = false, updatable = false)
    private int quantity;

    protected OrderItem() {
    }

    public OrderItem(Product product, int quantity) {
        if (product == null || quantity <= 0) {
            throw new OrderException(OrderException.Reason.INVALID_ITEMS);
        }
        this.product = product;
        this.productName = product.getName();
        this.unitPrice = product.getPrice();
        this.quantity = quantity;
    }

    void attachTo(Order order) {
        this.order = order;
    }

    public Long getProductId() {
        return product.getId();
    }

    public String getProductName() {
        return productName;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public long subtotal() {
        if (quantity <= 0) {
            throw new IllegalStateException("Stored order quantity must be positive");
        }
        try {
            return Math.multiplyExact(unitPrice, quantity);
        } catch (ArithmeticException exception) {
            throw new OrderException(OrderException.Reason.AMOUNT_LIMIT_EXCEEDED);
        }
    }
}
