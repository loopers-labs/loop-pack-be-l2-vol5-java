package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class OrderModel extends BaseEntity {
    @Column(nullable = false)
    private Long userId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;
    @Column(nullable = false)
    private long totalAmount;
    @Column(nullable = false)
    private long paidAmount;
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();

    protected OrderModel() {}

    public OrderModel(Long userId, List<OrderItem> items) {
        if (userId == null || userId <= 0 || items == null || items.isEmpty()) {
            throw new IllegalArgumentException("주문 정보가 올바르지 않습니다.");
        }
        this.userId = userId;
        this.items.addAll(items);
        this.status = OrderStatus.DRAFT;
        this.totalAmount = items.stream().mapToLong(OrderItem::getTotalPrice).reduce(0L, Math::addExact);
    }

    public Long getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public long getTotalAmount() { return totalAmount; }
    public long getPaidAmount() { return paidAmount; }
    public List<OrderItem> getItems() { return List.copyOf(items); }
    public boolean isOwnedBy(Long userId) { return this.userId.equals(userId); }

    public void validateConfirmable() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "확정할 수 없는 주문 상태입니다.");
        }
    }

    public void confirm() {
        validateConfirmable();
        paidAmount = totalAmount;
        status = OrderStatus.CONFIRMED;
    }
}
