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
import org.hibernate.annotations.ColumnDefault;

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
    // 결제 정보는 payment_records(결제 기록)로 옮겼다. 두 열은 과거 포인트 결제의 이관 원본이라 이관 검증이 끝날 때까지 남기고,
    // 새 주문은 쓰지 않도록 읽기 전용으로 매핑한다. NOT NULL 열이라 새 행은 DB 기본값으로 채운다(설계 22절).
    @ColumnDefault("0")
    @Column(nullable = false, insertable = false, updatable = false)
    long paidAmount;
    @ColumnDefault("'NOT_PAID'")
    @Column(nullable = false, insertable = false, updatable = false)
    String paymentResult;
    protected OrderJpaEntity() { }
}
