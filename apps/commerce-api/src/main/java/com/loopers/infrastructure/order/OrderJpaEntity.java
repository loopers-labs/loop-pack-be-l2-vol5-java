package com.loopers.infrastructure.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentResult;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "orders")
public class OrderJpaEntity extends BaseEntity {

    private Long userId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private long totalAmount;

    private Long paymentAmount;

    @Enumerated(EnumType.STRING)
    private PaymentResult paymentResult;

    @Getter(AccessLevel.NONE)
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItemJpaEntity> items = new ArrayList<>();

    private OrderJpaEntity(Long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                           PaymentResult paymentResult, List<OrderItemJpaEntity> items) {
        this.userId = userId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.paymentAmount = paymentAmount;
        this.paymentResult = paymentResult;
        this.items.addAll(items);
    }

    public static OrderJpaEntity create(Long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                                        PaymentResult paymentResult, List<OrderItemJpaEntity> items) {
        return new OrderJpaEntity(userId, status, totalAmount, paymentAmount, paymentResult, items);
    }

    public List<OrderItemJpaEntity> getItems() {
        return List.copyOf(items);
    }

    public void updateFrom(com.loopers.domain.order.Order order) {
        status = order.getStatus();
        totalAmount = order.getTotalAmount();
        paymentAmount = order.getPaymentAmount();
        paymentResult = order.getPaymentResult();
    }
}
