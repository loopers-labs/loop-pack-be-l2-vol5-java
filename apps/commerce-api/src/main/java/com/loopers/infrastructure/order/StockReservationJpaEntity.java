package com.loopers.infrastructure.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.order.StockReservation;
import com.loopers.domain.order.StockReservationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "stock_reservations", uniqueConstraints =
        @UniqueConstraint(name = "uk_reservations_order_product", columnNames = {"order_id", "product_id"}))
public class StockReservationJpaEntity extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private long orderId;
    @Column(nullable = false, updatable = false)
    private long productId;
    @Column(nullable = false, updatable = false)
    private int quantity;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StockReservationStatus status = StockReservationStatus.HELD;

    protected StockReservationJpaEntity() {
    }

    public StockReservationJpaEntity(StockReservation reservation) {
        orderId = reservation.orderId();
        productId = reservation.productId();
        quantity = reservation.quantity();
    }

    public StockReservation toDomain() {
        return new StockReservation(orderId, productId, quantity);
    }
}
