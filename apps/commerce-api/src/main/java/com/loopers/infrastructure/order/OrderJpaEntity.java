package com.loopers.infrastructure.order;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class OrderJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false, updatable = false)
    long userId;
    @ElementCollection
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "item_index")
    List<OrderItemJpaValue> items = new ArrayList<>();
    @Column(nullable = false)
    long total;
    @Column(nullable = false)
    String status;
    @Column(nullable = false)
    long paidAmount;
    @Column(nullable = false)
    String paymentResult;
    protected OrderJpaEntity() { }
}
