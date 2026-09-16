package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.MoneyConverter;
import com.loopers.domain.product.ProductSnapshot;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 주문 (ORD-01·02·05). 품목·합계·상태·결제 결과를 가진다. 주문자는 식별자만 알고, 상품·포인트는 모른다.
 */
@Entity
@Table(
    name = "orders",
    indexes = @Index(name = "idx_orders_user", columnList = "user_id")
)
public class OrderModel extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    @Comment("주문자 식별자 (ADR-01)")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Comment("주문 상태 — DRAFT·CONFIRMED (ORD-02)")
    private OrderStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItemModel> items = new ArrayList<>();

    @Convert(converter = MoneyConverter.class)
    @Column(name = "total_amount", nullable = false)
    @Comment("품목 금액 합계 (ORD-01)")
    private Money totalAmount;

    @Convert(converter = MoneyConverter.class)
    @Column(name = "paid_amount")
    @Comment("결제 금액. 확정 시 합계로 고정, DRAFT는 없음 (ORD-05)")
    private Money paidAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    @Comment("결제 수단 — 확정 시 POINT, DRAFT는 없음 (ORD-05)")
    private PaymentMethod paymentMethod;

    @Column(name = "confirmed_at")
    @Comment("확정 시각. DRAFT는 없음 (ORD-02)")
    private ZonedDateTime confirmedAt;

    protected OrderModel() {}

    private OrderModel(Long userId, List<OrderItemModel> items) {
        this.userId = userId;
        this.status = OrderStatus.DRAFT;
        this.items.addAll(items);
        this.totalAmount = sumOf(items);
    }

    /**
     * ORD-01: 합산된 품목마다 주문 당시 상품명·단가를 복사하고 합계를 계산해 DRAFT로 만든다. 재고·포인트는 차감하지 않는다.
     */
    public static OrderModel create(Long userId, OrderLines lines, Map<Long, ProductSnapshot> snapshots) {
        List<OrderItemModel> items = lines.quantities().entrySet().stream()
            .map(entry -> new OrderItemModel(snapshotOf(snapshots, entry.getKey()), entry.getValue()))
            .toList();
        return new OrderModel(userId, items);
    }

    public boolean isOwnedBy(Long requesterId) {
        return userId.equals(requesterId);
    }

    public boolean isDraft() {
        return status == OrderStatus.DRAFT;
    }

    /**
     * ORD-05: 결제할 금액은 자기 합계다. 조율자가 금액을 정해 넣지 않는다.
     */
    public Money paymentAmount() {
        return totalAmount;
    }

    /**
     * ORD-02·ORD-05: DRAFT만 확정한다. 결제액을 자기 합계로 고정하고 CONFIRMED로 바꾼다.
     */
    public void confirm(ZonedDateTime now) {
        if (!isDraft()) {
            throw new CoreException(ErrorType.CONFLICT, "이미 확정된 주문입니다.");
        }
        this.status = OrderStatus.CONFIRMED;
        this.paidAmount = totalAmount;
        this.paymentMethod = PaymentMethod.POINT;
        this.confirmedAt = now;
    }

    private static ProductSnapshot snapshotOf(Map<Long, ProductSnapshot> snapshots, Long productId) {
        ProductSnapshot snapshot = snapshots.get(productId);
        if (snapshot == null) {
            throw new IllegalArgumentException("주문 품목의 상품 정보가 없습니다: " + productId);
        }
        return snapshot;
    }

    private static Money sumOf(List<OrderItemModel> items) {
        try {
            return items.stream().map(OrderItemModel::amount).reduce(Money.ZERO, Money::plus);
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 금액이 표현할 수 있는 범위를 넘습니다.");
        }
    }

    public Long getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public List<OrderItemModel> getItems() {
        return Collections.unmodifiableList(items);
    }

    public Money getTotalAmount() {
        return totalAmount;
    }

    public Money getPaidAmount() {
        return paidAmount;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }
}
