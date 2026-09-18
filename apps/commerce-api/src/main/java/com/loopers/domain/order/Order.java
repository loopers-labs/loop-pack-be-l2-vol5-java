package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문. 품목의 합산 · 수량 · 합계 · 상태 규칙을 지킨다 (설계 2.3).
 * 품목은 Order 를 통해서만 만들어지며, 요청 순서로 저장하고 식별자 오름차순으로 읽는다 (D-33).
 * MySQL 예약어와 겹치지 않도록 테이블 이름은 orders 로 둔다.
 */
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    private static final int MIN_QUANTITY = 1;
    private static final int MAX_QUANTITY = 999;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    /** 실제로 차감한 금액. 확정 전에는 없다. 할인이 붙으면 합계와 갈라진다 (설계 5.5). */
    @Column(name = "payment_amount")
    private Long paymentAmount;

    /** updatedAt 은 이후 변경으로 덮이므로 결제 시각을 따로 둔다 (설계 5.5). */
    @Column(name = "paid_at")
    private ZonedDateTime paidAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id asc")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    private Order(Long userId, List<OrderItem> items) {
        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        this.items = new ArrayList<>(items);
        this.totalAmount = items.stream().mapToLong(OrderItem::getAmount).sum();
    }

    /**
     * DRAFT 주문을 만든다. 재고 · 포인트는 차감하지 않는다.
     * 같은 상품의 품목은 처음 등장한 위치에 합산하고(ORD-03), 수량은 합산 후 기준으로 확인한다(ORD-02).
     */
    public static Order draft(Long userId, List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new CoreException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }
        Map<Long, OrderLine> merged = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            validateQuantity(line.quantity());
            merged.merge(line.productId(), line, (first, next) ->
                new OrderLine(first.productId(), first.productName(), first.unitPrice(), first.quantity() + next.quantity()));
        }
        List<OrderItem> items = new ArrayList<>();
        for (OrderLine line : merged.values()) {
            validateQuantity(line.quantity());
            items.add(new OrderItem(line.productId(), line.productName(), line.unitPrice(), line.quantity()));
        }
        return new Order(userId, items);
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalAmount() {
        return totalAmount;
    }

    public Long getPaymentAmount() {
        return paymentAmount;
    }

    public ZonedDateTime getPaidAt() {
        return paidAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public boolean isDraft() {
        return status == OrderStatus.DRAFT;
    }

    /** DRAFT 에서만 확정한다(ORD-07). 결제액과 결제 시각을 함께 남긴다. */
    public void confirm(long paymentAmount, ZonedDateTime paidAt) {
        if (!isDraft()) {
            throw new CoreException(OrderErrorCode.ORDER_ALREADY_CONFIRMED);
        }
        this.status = OrderStatus.CONFIRMED;
        this.paymentAmount = paymentAmount;
        this.paidAt = paidAt;
    }

    private static void validateQuantity(int quantity) {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new CoreException(OrderErrorCode.INVALID_QUANTITY);
        }
    }
}
