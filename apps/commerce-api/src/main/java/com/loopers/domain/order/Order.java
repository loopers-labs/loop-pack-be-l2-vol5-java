package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Order {

    private final Long id;

    private Long userId;

    private OrderStatus status;

    private long totalAmount;

    private Long paymentAmount;

    private PaymentResult paymentResult;

    private List<OrderItem> items = new ArrayList<>();

    private Order(Long id, Long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                  PaymentResult paymentResult, List<OrderItem> items) {
        this.id = id;
        this.userId = userId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.paymentAmount = paymentAmount;
        this.paymentResult = paymentResult;
        this.items = new ArrayList<>(items);
    }

    public static Order create(Long userId, List<OrderItem> items) {
        Order order = new Order(null, userId, OrderStatus.DRAFT, 0L, null, null, List.of());
        items.forEach(order::addItem);
        return order;
    }

    public static Order reconstitute(Long id, Long userId, OrderStatus status, long totalAmount,
                                     Long paymentAmount, PaymentResult paymentResult, List<OrderItem> items) {
        return new Order(id, userId, status, totalAmount, paymentAmount, paymentResult, items);
    }

    private void addItem(OrderItem item) {
        items.stream()
            .filter(existing -> existing.getProductId().equals(item.getProductId()))
            .findFirst()
            .ifPresentOrElse(
                existing -> items.set(items.indexOf(existing), existing.addQuantity(item.getQuantity())),
                () -> items.add(item)
            );
        try {
            totalAmount = items.stream().mapToLong(OrderItem::getAmount).reduce(0L, Math::addExact);
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 총액이 저장 가능한 범위를 초과했습니다.");
        }
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public OrderStatus getStatus() { return status; }
    public long getTotalAmount() { return totalAmount; }
    public Long getPaymentAmount() { return paymentAmount; }
    public PaymentResult getPaymentResult() { return paymentResult; }
    public List<OrderItem> getItems() { return Collections.unmodifiableList(items); }

    public void validateDraft() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "DRAFT 주문만 확정할 수 있습니다.");
        }
    }

    public void confirm() {
        validateDraft();
        status = OrderStatus.CONFIRMED;
        this.paymentAmount = totalAmount;
        this.paymentResult = PaymentResult.SUCCESS;
    }
}
