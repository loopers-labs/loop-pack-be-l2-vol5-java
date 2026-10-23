package com.loopers.domain.order;

import com.loopers.domain.user.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "`order`")
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @OneToMany(mappedBy = "order", cascade = CascadeType.PERSIST)
    @OrderBy("id ASC")
    private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private OrderStatus status = OrderStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method")
    private OrderPaymentMethod paymentMethod;

    @Column(name = "total_amount", nullable = false, updatable = false)
    private long totalAmount;

    @Column(name = "paid_amount")
    private Long paidAmount;

    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private ZonedDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private ZonedDateTime updatedAt;

    protected Order() {
    }

    public static Order create(User user, List<OrderItem> items) {
        if (items == null || items.isEmpty() || items.stream().anyMatch(Objects::isNull)) {
            throw new OrderException(OrderException.Reason.INVALID_ITEMS);
        }
        Order order = new Order();
        order.user = Objects.requireNonNull(user, "user");
        try {
            for (OrderItem item : items) {
                order.totalAmount = Math.addExact(order.totalAmount, item.subtotal());
            }
        } catch (ArithmeticException exception) {
            throw new OrderException(OrderException.Reason.AMOUNT_LIMIT_EXCEEDED);
        }
        order.items.addAll(items);
        order.items.forEach(item -> item.attachTo(order));
        return order;
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return user.getId();
    }

    public void requireOwner(long userId) {
        if (getUserId() != userId) {
            throw new OrderException(OrderException.Reason.ORDER_NOT_FOUND);
        }
    }

    public void requireDraft() {
        if (status == OrderStatus.CONFIRMED) {
            throw new OrderException(OrderException.Reason.ORDER_ALREADY_CONFIRMED);
        }
        if (status != OrderStatus.DRAFT) {
            throw new IllegalStateException("Only a draft order can start payment");
        }
    }

    /**
     * 기존 POINT 확정 경로다. PG 준비·결과 반영과 혼용하지 않는다.
     */
    public void confirm(ZonedDateTime timestamp) {
        requireDraft();
        validatePaymentState(null, OrderItemReservationStatus.NOT_RESERVED, false);
        ZonedDateTime confirmationTime = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MICROS);
        confirmedAt = confirmationTime;
        paidAmount = totalAmount;
        paymentMethod = OrderPaymentMethod.POINT;
        status = OrderStatus.CONFIRMED;
    }

    /**
     * 주문·품목의 준비 상태만 변경한다. 실제 재고 차감·시도·발송 기록과의 공동 저장은 TX A 책임이다.
     */
    public void preparePgPayment() {
        requireDraft();
        validatePaymentState(null, OrderItemReservationStatus.NOT_RESERVED, false);
        items.forEach(OrderItem::holdReservation);
        paymentMethod = OrderPaymentMethod.PG;
        status = OrderStatus.PAYMENT_PENDING;
    }

    /**
     * 검증된 같은 시도의 성공을 적용한다. true는 객체 변경이며 DB 커밋 완료를 뜻하지 않는다.
     */
    public boolean confirmPgPayment(ZonedDateTime timestamp) {
        ZonedDateTime confirmationTime = Objects.requireNonNull(timestamp, "timestamp").truncatedTo(ChronoUnit.MICROS);
        if (status == OrderStatus.CONFIRMED) {
            validatePaymentState(OrderPaymentMethod.PG, OrderItemReservationStatus.CONSUMED, true);
            return false;
        }
        requirePaymentPending();
        validatePaymentState(OrderPaymentMethod.PG, OrderItemReservationStatus.HELD, false);
        items.forEach(OrderItem::consumeReservation);
        paidAmount = totalAmount;
        confirmedAt = confirmationTime;
        status = OrderStatus.CONFIRMED;
        return true;
    }

    /**
     * 검증된 실패의 예약 상태만 반환 완료로 바꾼다. 실제 재고 반환과의 공동 저장은 TX B 책임이다.
     */
    public boolean failPgPayment() {
        if (status == OrderStatus.PAYMENT_FAILED) {
            validatePaymentState(OrderPaymentMethod.PG, OrderItemReservationStatus.RELEASED, false);
            return false;
        }
        requirePaymentPending();
        validatePaymentState(OrderPaymentMethod.PG, OrderItemReservationStatus.HELD, false);
        items.forEach(OrderItem::releaseReservation);
        status = OrderStatus.PAYMENT_FAILED;
        return true;
    }

    public OrderPaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    /** 결과를 관찰하기 전에 저장된 주문·예약·금액의 조합을 검증한다. 상태를 변경하지 않는다. */
    public void validatePgPaymentState() {
        switch (status) {
            case PAYMENT_PENDING -> validatePaymentState(OrderPaymentMethod.PG, OrderItemReservationStatus.HELD, false);
            case CONFIRMED -> validatePaymentState(OrderPaymentMethod.PG, OrderItemReservationStatus.CONSUMED, true);
            case PAYMENT_FAILED -> validatePaymentState(OrderPaymentMethod.PG, OrderItemReservationStatus.RELEASED, false);
            default -> throw new IllegalStateException("PG result requires a prepared order");
        }
    }

    public void validateStoredQuantities() {
        if (items == null || items.isEmpty() || items.stream().anyMatch(item -> item == null || item.getQuantity() <= 0)) {
            throw new IllegalStateException("Stored order must contain positive quantities");
        }
    }

    private void requirePaymentPending() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new IllegalStateException("Only a pending PG order can receive a first result");
        }
    }

    private void validatePaymentState(OrderPaymentMethod expectedMethod, OrderItemReservationStatus expectedReservation,
                                      boolean confirmed) {
        validateStoredQuantities();
        if (paymentMethod != expectedMethod
            || items.stream().anyMatch(item -> item.getReservationStatus() != expectedReservation)) {
            throw new IllegalStateException("Stored payment method or reservations do not match the order state");
        }
        validateStoredAmount();
        if (confirmed ? paidAmount == null || paidAmount != totalAmount || confirmedAt == null
            : paidAmount != null || confirmedAt != null) {
            throw new IllegalStateException("Stored successful payment fields do not match the order state");
        }
    }

    private void validateStoredAmount() {
        long sum = 0;
        try {
            for (OrderItem item : items) {
                if (item.getUnitPrice() < 0) {
                    throw new IllegalStateException("Stored order price must be non-negative");
                }
                sum = Math.addExact(sum, item.subtotal());
            }
        } catch (ArithmeticException | OrderException exception) {
            throw new IllegalStateException("Stored order amount exceeds its supported range", exception);
        }
        if (totalAmount < 0 || totalAmount != sum) {
            throw new IllegalStateException("Stored order total must match its item snapshots");
        }
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
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

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public ZonedDateTime getCreatedAt() {
        return createdAt;
    }

    @PrePersist
    private void prePersist() {
        createdAt = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
        updatedAt = createdAt;
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }
}
