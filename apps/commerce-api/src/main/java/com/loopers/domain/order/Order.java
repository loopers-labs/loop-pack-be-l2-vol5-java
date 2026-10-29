package com.loopers.domain.order;

import com.loopers.domain.common.InvalidValueException;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.RuleViolationException;
import java.util.List;

public final class Order {
    public enum Status { DRAFT, PAYMENT_PENDING, CONFIRMED, PAYMENT_FAILED }

    public enum PaymentOutcome { SUCCEEDED, FAILED, UNKNOWN }

    public enum PaymentTransition { CHANGED, UNCHANGED, CONFLICT, ANOMALY }

    private final Long id;
    private final long userId;
    private final List<OrderItem> items;
    private final Money total;
    private Status status;

    private Order(Long id, long userId, List<OrderItem> items, Status status) {
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new InvalidValueException("주문 품목은 1~100개여야 합니다.");
        }
        if (items.stream().map(OrderItem::productId).distinct().count() != items.size()) {
            throw new InvalidValueException("중복 상품 품목은 허용하지 않습니다.");
        }
        this.id = id;
        this.userId = userId;
        this.items = List.copyOf(items);
        this.total = items.stream().map(OrderItem::subtotal).reduce(new Money(0), Money::add);
        this.status = status;
    }

    public static Order create(long userId, List<OrderItem> items) {
        return new Order(null, userId, items, Status.DRAFT);
    }

    public static Order restore(long id, long userId, List<OrderItem> items, Status status) {
        return new Order(id, userId, items, status);
    }

    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw new RuleViolationException("이미 확정된 주문입니다.");
        }
    }

    public void startPayment() {
        requireDraft();
        status = Status.PAYMENT_PENDING;
    }

    // 결제 대기만 결과로 바뀐다. 종결된 주문은 늦게 온 반대 결과로 덮어쓰지 않고 충돌로 알린다(설계 23절 상태 전이 표).
    public PaymentTransition applyPaymentOutcome(PaymentOutcome outcome) {
        return switch (status) {
            case DRAFT -> PaymentTransition.ANOMALY;
            case PAYMENT_PENDING -> switch (outcome) {
                case SUCCEEDED -> {
                    status = Status.CONFIRMED;
                    yield PaymentTransition.CHANGED;
                }
                case FAILED -> {
                    status = Status.PAYMENT_FAILED;
                    yield PaymentTransition.CHANGED;
                }
                case UNKNOWN -> PaymentTransition.UNCHANGED;
            };
            case CONFIRMED -> outcome == PaymentOutcome.FAILED ? PaymentTransition.CONFLICT : PaymentTransition.UNCHANGED;
            case PAYMENT_FAILED -> outcome == PaymentOutcome.SUCCEEDED ? PaymentTransition.CONFLICT : PaymentTransition.UNCHANGED;
        };
    }

    public Long getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public List<OrderItem> getItems() {
        return items;
    }

    public Money getTotal() {
        return total;
    }

    public Status getStatus() {
        return status;
    }
}
