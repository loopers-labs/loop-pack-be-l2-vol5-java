package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    private Long userId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private long totalAmount;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    private Order(Long userId, List<OrderItem> items) {
        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        items.forEach(this::addItem);
    }

    public static Order create(Long userId, List<OrderItem> items) {
        return new Order(userId, items);
    }

    private void addItem(OrderItem item) {
        items.stream()
            .filter(existing -> existing.getProductId().equals(item.getProductId()))
            .findFirst()
            .ifPresentOrElse(existing -> existing.addQuantity(item.getQuantity()), () -> items.add(item));
        totalAmount = items.stream().mapToLong(OrderItem::getAmount).sum();
    }

    public Long getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public long getTotalAmount() { return totalAmount; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }

    public void confirm() {
        if (status != OrderStatus.DRAFT) {
            throw new com.loopers.support.error.CoreException(
                com.loopers.support.error.ErrorType.CONFLICT, "DRAFT 주문만 확정할 수 있습니다."
            );
        }
        status = OrderStatus.CONFIRMED;
    }
}
