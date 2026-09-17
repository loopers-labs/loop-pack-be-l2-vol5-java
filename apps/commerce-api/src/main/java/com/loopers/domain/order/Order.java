package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.error.DomainErrorType;
import com.loopers.domain.error.DomainException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItem> items = new ArrayList<>();

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    // 확정 전(DRAFT)에는 비어 있다
    @Column(name = "paid_amount")
    private Long paidAmount;

    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    protected Order() {}

    public Order(Long userId, List<OrderItem> items) {
        if (userId == null) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "주문자는 필수입니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new DomainException(DomainErrorType.INVALID_VALUE, "주문 품목은 1개 이상이어야 합니다.");
        }
        this.userId = userId;
        this.items = mergeSameProduct(items);
        this.status = OrderStatus.DRAFT;
        this.totalAmount = calculateTotalAmount();
    }

    // 같은 상품 품목은 수량을 합쳐 한 품목으로 저장한다(T-3)
    private static List<OrderItem> mergeSameProduct(List<OrderItem> items) {
        Map<Long, OrderItem> merged = new LinkedHashMap<>();
        for (OrderItem item : items) {
            merged.merge(item.getProductId(), item, (existing, duplicate) -> {
                existing.addQuantity(duplicate.getQuantity());
                return existing;
            });
        }
        return new ArrayList<>(merged.values());
    }

    private long calculateTotalAmount() {
        return items.stream()
            .mapToLong(OrderItem::getAmount)
            .reduce(0L, Math::addExact);
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public List<Long> getProductIds() {
        return items.stream().map(OrderItem::getProductId).toList();
    }

    public Long getTotalAmount() {
        return totalAmount;
    }

    public Long getPaidAmount() {
        return paidAmount;
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /**
     * 확정 시점 상품 가격(상품 ID → 단가)으로 품목 단가와 합계를 다시 계산하고 결제액을 기록한다(T-1, T-2).
     * 가격이 없는 품목은 삭제 등으로 주문할 수 없는 상품이므로 확정을 거절한다(ORD-002).
     */
    public void confirm(Map<Long, Long> currentPrices) {
        if (status != OrderStatus.DRAFT) {
            throw new DomainException(DomainErrorType.CONFLICT, "확정 전(DRAFT) 주문만 확정할 수 있습니다.");
        }
        for (OrderItem item : items) {
            if (!currentPrices.containsKey(item.getProductId())) {
                throw new DomainException(DomainErrorType.CONFLICT, "[productId = " + item.getProductId() + "] 주문할 수 없는 상품이 포함되어 있습니다.");
            }
        }
        items.forEach(item -> item.changeUnitPrice(currentPrices.get(item.getProductId())));
        this.totalAmount = calculateTotalAmount();
        this.paidAmount = totalAmount;
        this.status = OrderStatus.CONFIRMED;
        this.confirmedAt = ZonedDateTime.now();
    }
}
