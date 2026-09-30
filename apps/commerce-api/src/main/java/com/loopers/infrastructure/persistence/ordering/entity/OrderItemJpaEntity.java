package com.loopers.infrastructure.persistence.ordering.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "order_items")
// 주문 품목 JPA 엔티티
public class OrderItemJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private OrderJpaEntity order;
    @Column(name = "product_id", nullable = false)
    private long productId;
    @Column(name = "product_name", nullable = false, length = 100)
    private String productName;
    @Column(name = "unit_price", nullable = false)
    private long unitPrice;
    @Column(nullable = false)
    private int quantity;
    @Column(nullable = false)
    private long amount;

    protected OrderItemJpaEntity() {}

    OrderItemJpaEntity(long productId, String productName, long unitPrice, int quantity, long amount) {
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.amount = amount;
    }

    void assignOrder(OrderJpaEntity order) {
        this.order = order;
    }

    public Long getId() {
        return id;
    }

    public OrderJpaEntity getOrder() {
        return order;
    }

    public long getProductId() {
        return productId;
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

    public long getAmount() {
        return amount;
    }
}
