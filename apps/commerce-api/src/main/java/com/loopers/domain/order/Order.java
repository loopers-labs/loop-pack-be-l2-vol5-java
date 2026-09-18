package com.loopers.domain.order;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.domain.common.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Order {

    private final Long id;
    private final Long userId;
    private final List<OrderItem> items;
    private final Money totalAmount;
    private final Instant placedAt;

    private OrderStatus status;
    private Money paidAmount;
    private Instant confirmedAt;

    private Order(Long id, Long userId, List<OrderItem> items, Money totalAmount, Instant placedAt,
                  OrderStatus status, Money paidAmount, Instant confirmedAt) {
        this.id = id;
        this.userId = userId;
        this.items = items;
        this.totalAmount = totalAmount;
        this.placedAt = placedAt;
        this.status = status;
        this.paidAmount = paidAmount;
        this.confirmedAt = confirmedAt;
    }

    public static Order draft(Long userId, List<OrderItem> items, Instant placedAt) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 는 필수입니다");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("주문은 품목을 1개 이상 가져야 합니다");
        }
        List<OrderItem> merged = merge(items);
        return new Order(null, userId, merged, totalOf(merged), placedAt, OrderStatus.DRAFT, null, null);
    }

    public static Order restore(Long id, Long userId, List<OrderItem> items, Money totalAmount,
                                Instant placedAt, OrderStatus status, Money paidAmount, Instant confirmedAt) {
        return new Order(id, userId, List.copyOf(items), totalAmount, placedAt, status, paidAmount, confirmedAt);
    }

    public void confirm(Instant confirmedAt) {
        if (status != OrderStatus.DRAFT) {
            throw new DomainException(DomainError.ORDER_NOT_DRAFT);
        }
        this.status = OrderStatus.CONFIRMED;
        this.paidAmount = totalAmount;
        this.confirmedAt = confirmedAt;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    private static List<OrderItem> merge(List<OrderItem> items) {
        Map<Long, OrderItem> byProduct = new LinkedHashMap<>();
        for (OrderItem item : items) {
            byProduct.merge(item.productId(), item, OrderItem::mergeWith);
        }
        return List.copyOf(new ArrayList<>(byProduct.values()));
    }

    private static Money totalOf(List<OrderItem> items) {
        return items.stream().map(OrderItem::lineTotal).reduce(Money.ZERO, Money::plus);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Money getPaidAmount() {
        return paidAmount;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }
}
