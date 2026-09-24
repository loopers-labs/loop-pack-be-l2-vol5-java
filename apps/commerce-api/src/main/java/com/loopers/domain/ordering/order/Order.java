package com.loopers.domain.ordering.order;

import com.loopers.domain.shared.Money;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;

// 주문 도메인 모델
public final class Order {
    private final Long id;
    private final long userId;
    private OrderStatus status;
    private final List<OrderItem> items;
    private final Money totalAmount;
    private final Instant createdAt;

    private Order(Long id, long userId, OrderStatus status, List<OrderItem> items, Money totalAmount,
                  Instant createdAt) {
        if (userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new DomainException(DomainErrorCode.EMPTY_ORDER_ITEMS);
        }
        this.id = id;
        this.userId = userId;
        this.status = status;
        this.items = List.copyOf(items);
        this.totalAmount = totalAmount;
        this.createdAt = createdAt;
    }

    // 새 주문 생성
    public static Order create(long userId, List<OrderItem> items) {
        Money total = sumAmounts(items);
        return new Order(null, userId, OrderStatus.DRAFT, items, total, null);
    }

    // 저장된 데이터로부터 주문 복원
    public static Order restore(long id, long userId, OrderStatus status, List<OrderItem> items, long totalAmount,
                                Instant createdAt) {
        if (id <= 0 || createdAt == null) {
            throw new IllegalArgumentException("저장된 주문 상태가 올바르지 않습니다.");
        }
        Money computedTotal = sumAmounts(items);
        if (computedTotal.getValue() != totalAmount) {
            throw new IllegalArgumentException("저장된 주문 합계가 올바르지 않습니다.");
        }
        return new Order(id, userId, status, items, computedTotal, createdAt);
    }

    // 확정 가능 여부만 검증하고 상태는 바꾸지 않음
    public void ensureCanConfirm() {
        if (status == OrderStatus.CONFIRMED) {
            throw new DomainException(DomainErrorCode.ORDER_ALREADY_CONFIRMED);
        }
    }

    // DRAFT를 CONFIRMED로 전환
    public void confirm() {
        ensureCanConfirm();
        status = OrderStatus.CONFIRMED;
    }

    // 품목 금액 합산
    private static Money sumAmounts(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new DomainException(DomainErrorCode.EMPTY_ORDER_ITEMS);
        }
        Money total = Money.zero();
        for (OrderItem item : items) {
            total = total.add(item.amountAsMoney());
        }
        return total;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    public long getTotalAmount() {
        return totalAmount.getValue();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
