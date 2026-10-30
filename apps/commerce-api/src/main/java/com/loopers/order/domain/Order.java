package com.loopers.order.domain;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Table;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Version;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    private Long buyerId;
    @ElementCollection(fetch = FetchType.EAGER)
    private List<OrderItem> items;
    private OrderStatus status;
    @Column(name = "confirmed_at")
    private ZonedDateTime confirmedAt;
    @Enumerated(EnumType.STRING)
    private StockReservationStatus stockReservationStatus;
    @Embedded
    private PaymentResult paymentResult;
    @OneToOne(cascade = CascadeType.ALL, orphanRemoval = true)
    private Payment payment;
    @Version
    private Long version;

    protected Order() {
    }

    public Order(Long buyerId, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorCode.EMPTY_ORDER_ITEMS);
        }
        this.buyerId = buyerId;
        this.items = mergeItems(items);
        this.status = OrderStatus.DRAFT;
    }

    public Long getBuyerId() {
        return buyerId;
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }

    public long getTotalAmount() {
        return items.stream().mapToLong(OrderItem::amount).sum();
    }

    public OrderStatus getStatus() {
        return status;
    }

    public ZonedDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public StockReservationStatus getStockReservationStatus() {
        return stockReservationStatus;
    }

    public PaymentResult getPaymentResult() {
        return paymentResult;
    }

    public Payment getPayment() {
        return payment;
    }

    public void setPayment(Payment payment) {
        this.payment = payment;
    }

    public boolean isOwnedBy(Long userId) {
        return buyerId.equals(userId);
    }

    public void changeItemQuantity(Long requesterId, Long productId, int quantity) {
        if (!isOwnedBy(requesterId)) {
            throw new CoreException(ErrorCode.ORDER_NOT_FOUND);
        }
        ensureDraft();

        for (int index = 0; index < items.size(); index++) {
            OrderItem item = items.get(index);
            if (item.productId().equals(productId)) {
                items.set(index, item.withQuantity(quantity));
                return;
            }
        }
        throw new CoreException(ErrorCode.PRODUCT_NOT_FOUND);
    }

    public void confirm(long paymentAmount, ZonedDateTime paidAt) {
        confirm(paymentAmount, paidAt, paidAt);
    }

    public void confirm(long paymentAmount, ZonedDateTime paidAt, ZonedDateTime confirmedAt) {
        ensureDraft();
        PaymentResult result = new PaymentResult(paymentAmount, paidAt);
        this.paymentResult = result;
        this.status = OrderStatus.CONFIRMED;
        this.confirmedAt = confirmedAt.withZoneSameInstant(ZoneOffset.UTC);
    }

    public void cancel() {
        ensureDraft();
        this.status = OrderStatus.CANCELED;
    }

    public void reserveStock() {
        ensureDraft();
        if (stockReservationStatus != null) {
            throw new CoreException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        }
        stockReservationStatus = StockReservationStatus.RESERVED;
    }

    public void consumeStockReservation() {
        if (stockReservationStatus != StockReservationStatus.RESERVED) {
            throw new CoreException(ErrorCode.INTERNAL_ERROR);
        }
        stockReservationStatus = StockReservationStatus.CONSUMED;
    }

    public void releaseStockReservation() {
        if (stockReservationStatus != StockReservationStatus.RESERVED) {
            throw new CoreException(ErrorCode.INTERNAL_ERROR);
        }
        stockReservationStatus = null;
    }

    private static List<OrderItem> mergeItems(List<OrderItem> items) {
        Map<Long, OrderItem> merged = new LinkedHashMap<>();
        for (OrderItem item : items) {
            merged.merge(
                item.productId(),
                item,
                (existing, duplicated) -> existing.withQuantity(
                    existing.quantity() + duplicated.quantity()
                )
            );
        }
        return new ArrayList<>(merged.values());
    }

    private void ensureDraft() {
        if (status == OrderStatus.CONFIRMED) {
            throw new CoreException(ErrorCode.ORDER_ALREADY_CONFIRMED);
        }
    }
}
