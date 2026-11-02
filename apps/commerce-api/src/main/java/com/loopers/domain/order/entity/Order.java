package com.loopers.domain.order.entity;

import com.loopers.domain.order.enums.OrderErrorCode;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.vo.OrderLine;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.loopers.domain.order.enums.PaymentMethod;

/** 품목은 Order 를 통해서만 만들고, 요청 순서로 저장해 식별자 순으로 읽음 (D-33). 테이블 이름은 예약어를 피해 orders */
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    private static final int MIN_QUANTITY = 1;
    private static final int MAX_QUANTITY = 999;
    private static final Duration VALID_DURATION = Duration.ofMinutes(30);

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    /** 만료 상태를 따로 두지 않고 확정 때 비교함 (D-41) */
    @Column(name = "expires_at", nullable = false)
    private ZonedDateTime expiresAt;

    /** 할인이 붙으면 합계와 갈라짐 (설계 5.5) */
    @Column(name = "payment_amount")
    private Long paymentAmount;

    /** updatedAt 은 이후 변경으로 덮이므로 따로 둠 (설계 5.5) */
    @Column(name = "paid_at")
    private ZonedDateTime paidAt;

    /** 결제 시각과 갈라질 수 있어 따로 둠. 주문 확정 이벤트의 확정 시각 (6주차 D-71) */
    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    /** DRAFT 에는 없음. 5주차 전 확정 주문은 POINT */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private PaymentMethod paymentMethod;

    /** 같은 주문의 변경이 겹치면 나중 commit 이 충돌함 (3주차 설계 4.1) */
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

    /** 같은 상품은 처음 등장한 위치에 합산하고 수량은 합산 뒤 확인함 (ORD-02, ORD-03, ORD-08) */
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

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
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

    /** 상품을 다루기 전에 부를 수 있도록 따로 둠. 정확히 30분이 된 순간부터 만료 (ORD-07, ORD-08, 설계 5.3) */
    public void validateConfirmable(ZonedDateTime now) {
        if (!isDraft()) {
            throw new CoreException(OrderErrorCode.ORDER_ALREADY_CONFIRMED);
        }
        if (!now.isBefore(expiresAt)) {
            throw new CoreException(OrderErrorCode.ORDER_EXPIRED);
        }
    }

    /** 5주차 전 POINT 확정 주문 재현용. 운영 경로에서는 부르지 않음 */
    public void confirmWithPoint(long paymentAmount, ZonedDateTime paidAt) {
        validateConfirmable(paidAt);
        this.status = OrderStatus.CONFIRMED;
        this.paymentMethod = PaymentMethod.POINT;
        this.paymentAmount = paymentAmount;
        markConfirmedAt(paidAt);
    }

    /** 이후로는 만료로 끝내지 않음 (5주차 설계 2.1) */
    public void startPayment(ZonedDateTime now) {
        validateConfirmable(now);
        this.status = OrderStatus.PAYMENT_PENDING;
        this.paymentMethod = PaymentMethod.PG;
    }

    /** 0원 주문은 PG 없이 바로 확정함 (D-68) */
    public void confirmWithoutPayment(ZonedDateTime now) {
        validateConfirmable(now);
        if (totalAmount != 0) {
            throw new IllegalStateException("결제할 금액이 있는 주문: " + totalAmount);
        }
        this.status = OrderStatus.CONFIRMED;
        this.paymentMethod = PaymentMethod.NONE;
        this.paymentAmount = 0L;
        markConfirmedAt(now);
    }

    /** 결제 대기가 아니면 결제 시도와 주문이 어긋난 것이라 상태 오류로 막음 */
    public void confirmPaid(long paymentAmount, ZonedDateTime paidAt) {
        requirePaymentPending();
        this.status = OrderStatus.CONFIRMED;
        this.paymentAmount = paymentAmount;
        markConfirmedAt(paidAt);
    }

    /** 재고 반환은 호출자가 같은 TX 에서 함 */
    public void failPayment() {
        requirePaymentPending();
        this.status = OrderStatus.PAYMENT_FAILED;
    }

    /** DB(DATETIME(6))와 같은 마이크로초로 잘라, 이벤트에 담는 메모리 값과 저장 값이 같게 함 (6주차 D-82) */
    private void markConfirmedAt(ZonedDateTime at) {
        ZonedDateTime stored = at.truncatedTo(ChronoUnit.MICROS);
        this.paidAt = stored;
        this.confirmedAt = stored;
    }

    private void requirePaymentPending() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new IllegalStateException("결제 대기 주문이 아님: " + status);
        }
    }

    private static void validateQuantity(int quantity) {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new CoreException(OrderErrorCode.INVALID_QUANTITY);
        }
    }
}
