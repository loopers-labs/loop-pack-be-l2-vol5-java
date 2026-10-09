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
import jakarta.persistence.Version;

import java.time.Duration;
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
    /** 주문서는 생성 후 이 기간 동안만 확정할 수 있음 (설계 2.3, D-41) */
    private static final Duration VALID_DURATION = Duration.ofMinutes(30);

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    /** 이 시각부터 확정할 수 없음. 만료 상태는 따로 두지 않고 확정 때 비교함 (설계 2.3, D-41) */
    @Column(name = "expires_at", nullable = false)
    private ZonedDateTime expiresAt;

    /** 실제로 차감한 금액. 확정 전에는 없다. 할인이 붙으면 합계와 갈라진다 (설계 5.5). */
    @Column(name = "payment_amount")
    private Long paymentAmount;

    /** updatedAt 은 이후 변경으로 덮이므로 결제 시각을 따로 둔다 (설계 5.5). */
    @Column(name = "paid_at")
    private ZonedDateTime paidAt;

    /** 같은 주문의 확정이 겹치면 나중 commit 이 충돌하고, 재시도에서 CONFIRMED 를 읽어 기존 상태 오류로 끝남 (3주차 설계 4.1) */
    @Version
    private Long version;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id asc")
    private List<OrderItem> items = new ArrayList<>();

    protected Order() {}

    private Order(Long userId, List<OrderItem> items, ZonedDateTime createdAt) {
        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        this.items = new ArrayList<>(items);
        this.totalAmount = items.stream().mapToLong(OrderItem::getAmount).sum();
        this.expiresAt = createdAt.plus(VALID_DURATION);
    }

    /**
     * DRAFT 주문을 만든다. 재고 · 포인트는 차감하지 않는다.
     * 같은 상품의 품목은 처음 등장한 위치에 합산하고(ORD-03), 수량은 합산 후 기준으로 확인한다(ORD-02).
     * 만료 시각은 생성 시각 + 30분으로 정함(ORD-08).
     */
    public static Order draft(Long userId, List<OrderLine> lines, ZonedDateTime createdAt) {
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
        return new Order(userId, items, createdAt);
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

    public ZonedDateTime getExpiresAt() {
        return expiresAt;
    }

    public List<OrderItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    public boolean isDraft() {
        return status == OrderStatus.DRAFT;
    }

    /**
     * DRAFT 가 아니면(ORD-07), 만료되었으면(ORD-08) 거절함. 상품 · 포인트를 다루기 전에 부를 수 있도록 따로 둠 (설계 5.3).
     * 현재 시각이 만료 시각 이상이면 만료이며, 정확히 30분이 된 순간부터 확정할 수 없음
     */
    public void validateConfirmable(ZonedDateTime now) {
        if (!isDraft()) {
            throw new CoreException(OrderErrorCode.ORDER_ALREADY_CONFIRMED);
        }
        if (!now.isBefore(expiresAt)) {
            throw new CoreException(OrderErrorCode.ORDER_EXPIRED);
        }
    }

    /** 확정 가능한지 같은 검사를 거친 뒤 결제액과 결제 시각을 함께 남김 (ORD-12) */
    public void confirm(long paymentAmount, ZonedDateTime paidAt) {
        validateConfirmable(paidAt);
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
