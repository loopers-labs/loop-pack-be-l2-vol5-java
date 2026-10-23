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

    private PaymentMethod paymentMethod;

    private List<OrderItem> items = new ArrayList<>();

    private Order(Long id, Long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                  PaymentResult paymentResult, PaymentMethod paymentMethod, List<OrderItem> items) {
        this.id = id;
        this.userId = userId;
        this.status = status;
        this.totalAmount = totalAmount;
        this.paymentAmount = paymentAmount;
        this.paymentResult = paymentResult;
        this.paymentMethod = paymentMethod;
        this.items = new ArrayList<>(items);
    }

    public static Order create(Long userId, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 하나 이상이어야 합니다.");
        }
        Order order = new Order(null, userId, OrderStatus.DRAFT, 0L, null, null, null, List.of());
        items.forEach(order::addItem);
        return order;
    }

    public static Order reconstitute(Long id, Long userId, OrderStatus status, long totalAmount, Long paymentAmount,
                                     PaymentResult paymentResult, PaymentMethod paymentMethod, List<OrderItem> items) {
        // 결제를 시작한 주문은 결제수단을 저장하므로, 없으면 상태로 추론하지 않고 데이터 정합성 오류로 본다.
        if (status != OrderStatus.DRAFT && paymentMethod == null) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "결제를 시작한 주문에 결제수단이 없습니다.");
        }
        return new Order(id, userId, status, totalAmount, paymentAmount, paymentResult, paymentMethod, items);
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
    public PaymentMethod getPaymentMethod() { return paymentMethod; }

    public void requestPgPayment() {
        validateDraft();
        status = OrderStatus.PAYMENT_PENDING;
        paymentMethod = PaymentMethod.PG;
    }

    public void completePgPayment() {
        validatePaymentPending();
        status = OrderStatus.CONFIRMED;
        paymentAmount = totalAmount;
        paymentResult = PaymentResult.SUCCESS;
    }

    public void failPgPayment() {
        validatePaymentPending();
        status = OrderStatus.PAYMENT_FAILED;
        paymentResult = PaymentResult.FAILED;
    }

    // 미종결 시도는 PAYMENT_PENDING 주문에만 있으므로, 다른 상태의 결과 반영은 업무 충돌이 아니라 불변식이 깨진 경우다.
    private void validatePaymentPending() {
        if (status != OrderStatus.PAYMENT_PENDING) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "결제 대기 중이 아닌 주문에 결제 결과를 반영할 수 없습니다.");
        }
    }

    public void validateDraft() {
        if (status != OrderStatus.DRAFT) {
            throw new CoreException(ErrorType.CONFLICT, "DRAFT 주문만 확정할 수 있습니다.");
        }
    }
}
