package com.loopers.domain.order;

import com.loopers.domain.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "`order`")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status = OrderStatus.DRAFT;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private long totalAmount;

    @Column(name = "paid_amount")
    private Long paidAmount;

    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected Order() {
    }

    public static Order create(User user, List<OrderItem> items) {
        if (items == null || items.isEmpty() || items.stream().anyMatch(Objects::isNull)) {
            throw new OrderException(OrderException.Reason.INVALID_ITEMS);
        }
        Order order = new Order();
        order.user = Objects.requireNonNull(user, "user");
        try {
            for (OrderItem item : items) {
                order.totalAmount = Math.addExact(order.totalAmount, item.subtotal());
            }
        } catch (ArithmeticException exception) {
            throw new OrderException(OrderException.Reason.AMOUNT_LIMIT_EXCEEDED);
        }
        order.items.addAll(items);
        order.items.forEach(item -> item.attachTo(order));
        return order;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return user.getId();
    }

    public void requireOwner(long userId) {
        if (getUserId() != userId) {
            throw new OrderException(OrderException.Reason.ORDER_NOT_FOUND);
        }
    }

    public void confirm(ZonedDateTime timestamp) {
        if (status == OrderStatus.CONFIRMED) {
            return;
        }
        validateStoredQuantities();
        confirmedAt = Objects.requireNonNull(timestamp, "timestamp");
        paidAmount = totalAmount;
        status = OrderStatus.CONFIRMED;
    }

    public void validateStoredQuantities() {
        if (items.isEmpty() || items.stream().anyMatch(item -> item.getQuantity() <= 0)) {
            throw new IllegalStateException("Stored order must contain positive quantities");
        }
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public Long getPaidAmount() {
        return paidAmount;
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    private void prePersist() {
        createdAt = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
