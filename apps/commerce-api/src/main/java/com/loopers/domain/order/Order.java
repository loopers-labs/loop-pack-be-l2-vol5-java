package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    public enum OrderStatus {
        DRAFT, CONFIRMED
    }

    private Long userId;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id")
    private List<OrderItem> items = new ArrayList<>();

    /**
     * 확정 시점에 기록되는 결제액. 확정 전에는 0 이다.
     */
    private long paidAmount;

    protected Order() {}

    public Order(Long userId, List<OrderItem> items) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문의 사용자 식별자는 필수입니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 하나 이상이어야 합니다.");
        }

        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        this.items = mergeSameProducts(items);
        this.paidAmount = 0L;
    }

    /**
     * 결제액은 생성 시점 단가로 계산하며, 확정 시에도 재계산하지 않는다.
     */
    public long getTotalAmount() {
        return items.stream()
            .mapToLong(OrderItem::getAmount)
            .sum();
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    /**
     * 확정에 실패하면 예외만 던지고 상태를 바꾸지 않아 DRAFT 로 남는다.
     */
    public void confirm() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "이미 확정된 주문입니다.");
        }
        this.paidAmount = getTotalAmount();
        this.status = OrderStatus.CONFIRMED;
    }

    /**
     * 같은 상품이 여러 품목으로 들어오면 하나로 합산한다.
     */
    private List<OrderItem> mergeSameProducts(List<OrderItem> items) {
        Map<Long, OrderItem> merged = new LinkedHashMap<>();
        for (OrderItem item : items) {
            merged.merge(item.getProductId(), item,
                (existing, added) -> existing.mergeQuantity(added.getQuantity()));
        }
        return new ArrayList<>(merged.values());
    }
}
