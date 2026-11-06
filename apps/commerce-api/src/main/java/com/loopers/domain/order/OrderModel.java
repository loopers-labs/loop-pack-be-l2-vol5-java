package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.util.List;

@Entity
@Table(name = "orders")
public class OrderModel extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Column(name = "paid_amount")
    private Long paidAmount;

    @Column(name = "expired_at", nullable = false)
    private ZonedDateTime expiredAt;

    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    private List<OrderItemModel> items;

    protected OrderModel() {
    }

    public OrderModel(Long userId, List<OrderItemModel> items) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자 ID는 비어있을 수 없습니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 비어있을 수 없습니다.");
        }
        this.userId = userId;
        this.items = items;
        this.status = OrderStatus.DRAFT;
        this.totalAmount = items.stream().mapToLong(OrderItemModel::getAmount).sum();
        this.expiredAt = ZonedDateTime.now().plusMinutes(10);
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

    public Long getPaidAmount() {
        return paidAmount;
    }

    public ZonedDateTime getExpiredAt() {
        return expiredAt;
    }

    public List<OrderItemModel> getItems() {
        return items;
    }

    public void requireDraft() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "DRAFT 상태의 주문만 확정할 수 있습니다. (현재: " + status + ")");
        }
        if (ZonedDateTime.now().isAfter(expiredAt)) {
            throw new CoreException(ErrorType.CONFLICT, "만료된 주문입니다. 새 주문을 생성해주세요.");
        }
    }

    public void confirm() {
        requireDraft();
        this.paidAmount = totalAmount;
        this.status = OrderStatus.CONFIRMED;
    }

    public void startPayment() {
        requireDraft();
        this.status = OrderStatus.PAYMENT_PENDING;
    }

    public void confirmPayment() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new CoreException(ErrorType.CONFLICT, "PAYMENT_PENDING 상태의 주문만 확정할 수 있습니다.");
        }
        this.paidAmount = totalAmount;
        this.status = OrderStatus.CONFIRMED;
        if (this.confirmedAt == null) {
            this.confirmedAt = ZonedDateTime.now();
        }
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public void failPayment() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new CoreException(ErrorType.CONFLICT, "PAYMENT_PENDING 상태의 주문만 실패 처리할 수 있습니다.");
        }
        this.status = OrderStatus.PAYMENT_FAILED;
    }

    public void expire() {
        if (status == OrderStatus.DRAFT) {
            this.status = OrderStatus.EXPIRED;
        }
    }
}
