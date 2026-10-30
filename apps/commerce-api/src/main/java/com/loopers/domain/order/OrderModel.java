package com.loopers.domain.order;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class OrderModel extends BaseEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @ElementCollection
    @CollectionTable(name = "order_item", joinColumns = @JoinColumn(name = "order_id"))
    @OrderColumn(name = "item_order")
    private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "paid_amount")
    private Long paidAmount;

    // DRAFT는 비어 있다. 4주차까지 확정한 주문은 POINT, 5주차부터 시작한 결제는 PG다
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    // 처음 PG 성공으로 확정한 시각(UTC). 확정 전이거나, 이 컬럼이 생기기 전에 포인트로 확정한 주문은 비어 있고 소급해 채우지 않는다
    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    protected OrderModel() {
    }

    public OrderModel(Long userId, List<OrderItem> items) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "사용자는 비어있을 수 없습니다.");
        }
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 비어있을 수 없습니다.");
        }
        this.userId = userId;
        this.items = new ArrayList<>(items);
        this.status = OrderStatus.DRAFT;
        this.totalAmount = items.stream().mapToLong(OrderItem::getSubtotal).sum();
    }

    // 포인트로 즉시 확정한다. 4주차까지의 결제 사실이며, 5주차의 확정 흐름은 아래 PG 전이를 쓴다
    public void confirm(Long paidAmount) {
        if (this.status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "이미 처리된 주문입니다.");
        }
        this.status = OrderStatus.CONFIRMED;
        this.paidAmount = paidAmount;
        this.paymentMethod = PaymentMethod.POINT;
    }

    // 재고를 확보하고 PG 결제를 시작했다. 결과는 아직 모른다
    public void startPgPayment() {
        if (this.status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "이미 처리된 주문입니다.");
        }
        this.status = OrderStatus.PAYMENT_PENDING;
        this.paymentMethod = PaymentMethod.PG;
    }

    // PG 거래의 성공을 확인했다. 확정 시각은 지금이다
    public void completePgPayment(Long paidAmount) {
        completePgPayment(paidAmount, Instant.now());
    }

    // 결제 대기에서만 들어올 수 있어서 확정 시각은 한 번만 정해진다. 시각은 호출자(주입한 Clock)가 정한다
    public void completePgPayment(Long paidAmount, Instant confirmedAt) {
        requirePaymentPending();
        this.status = OrderStatus.CONFIRMED;
        this.paidAmount = paidAmount;
        // DB에 저장되는 정밀도(밀리초)로 맞춰, 이벤트에 담은 값과 나중에 읽은 값이 같게 한다
        this.confirmedAt = confirmedAt.truncatedTo(ChronoUnit.MILLIS);
    }

    // PG 거래의 확정 실패를 확인했다
    public void failPgPayment() {
        requirePaymentPending();
        this.status = OrderStatus.PAYMENT_FAILED;
    }

    private void requirePaymentPending() {
        if (this.status != OrderStatus.PAYMENT_PENDING) {
            throw new CoreException(ErrorType.CONFLICT, "결제 결과를 기다리는 주문이 아닙니다.");
        }
    }

    public Long getUserId() {
        return userId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Long getTotalAmount() {
        return totalAmount;
    }

    public Long getPaidAmount() {
        return paidAmount;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    /**
     * 결제수단. 결제수단 컬럼이 생기기 전(4주차까지)에 확정한 주문은 저장된 값이 비어 있다.
     * 그때의 결제는 모두 포인트였고, PG 주문은 결제를 시작하는 순간 PG로 채워지므로, 확정된 주문의 빈 값은 POINT로 본다.
     * DRAFT는 아직 결제수단이 없어 비어 있는 그대로다. 저장된 값을 바꾸지 않고 읽을 때만 보정한다.
     */
    public PaymentMethod getPaymentMethod() {
        if (paymentMethod == null && status == OrderStatus.CONFIRMED) {
            return PaymentMethod.POINT;
        }
        return paymentMethod;
    }
}
