package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import java.util.List;

public final class Order {
    public enum Status { DRAFT, CONFIRMED }
    private final Long id;
    private final long userId;
    private final List<OrderItem> items;
    private final Money total;
    private Status status;
    private Money paidAmount;
    private String paymentResult;

    private Order(Long id, long userId, List<OrderItem> items, Status status, Money paidAmount, String paymentResult) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new com.loopers.domain.common.InvalidValueException("주문 품목은 1~100개여야 합니다.");
        }
        if (items.stream().map(OrderItem::productId).distinct().count() != items.size()) {
            throw new com.loopers.domain.common.InvalidValueException("중복 상품 품목은 허용하지 않습니다.");
        }
        this.id = id;
        this.userId = userId;
        this.items = List.copyOf(items);
        this.total = items.stream().map(OrderItem::subtotal).reduce(new Money(0), Money::add);
        this.status = status;
        this.paidAmount = paidAmount;
        this.paymentResult = paymentResult;
    }
    public static Order create(long userId, List<OrderItem> items) {
        return new Order(null, userId, items, Status.DRAFT, new Money(0), "NOT_PAID");
    }
    public static Order restore(long id, long userId, List<OrderItem> items, Status status, Money paidAmount, String result) {
        return new Order(id, userId, items, status, paidAmount, result);
    }
    public void requireDraft() {
        if (status != Status.DRAFT) { throw new com.loopers.domain.common.RuleViolationException("이미 확정된 주문입니다."); }
    }
    public void confirm() {
        requireDraft();
        status = Status.CONFIRMED;
        paidAmount = total;
        paymentResult = "SUCCESS";
    }
    public Long getId() { return id; }
    public long getUserId() { return userId; }
    public List<OrderItem> getItems() { return items; }
    public Money getTotal() { return total; }
    public Status getStatus() { return status; }
    public Money getPaidAmount() { return paidAmount; }
    public String getPaymentResult() { return paymentResult; }
}
