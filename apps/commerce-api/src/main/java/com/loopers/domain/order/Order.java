package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.Getter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Getter
public class Order {
    private final Long id;
    private final long userId;
    private final List<OrderItem> items;
    private final long totalAmount;
    private OrderStatus status;
    private Long paidAmount;
    private String paymentResult;

    private Order(Long id, long userId, List<OrderItem> items, long totalAmount, OrderStatus status, Long paidAmount, String paymentResult) {
        this.id = id;
        this.userId = userId;
        this.items = List.copyOf(items);
        this.totalAmount = totalAmount;
        this.status = status;
        this.paidAmount = paidAmount;
        this.paymentResult = paymentResult;
    }

    public record RequestedItem(long productId, int quantity, long unitPrice) {}

    public static Order create(long userId, List<RequestedItem> requested) {
        if (userId <= 0 || requested == null || requested.isEmpty()) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        Map<Long, RequestedItem> merged = new LinkedHashMap<>();
        for (RequestedItem item : requested) {
            if (item == null || item.productId() <= 0 || item.quantity() <= 0 || item.unitPrice() <= 0) {
                throw new CoreException(ErrorType.INVALID_REQUEST);
            }
            try {
                merged.merge(item.productId(), item, (previous, next) -> new RequestedItem(previous.productId(),
                    Math.addExact(previous.quantity(), next.quantity()), previous.unitPrice()));
            } catch (ArithmeticException exception) {
                throw new CoreException(ErrorType.INVALID_REQUEST);
            }
        }
        List<OrderItem> items = merged.values().stream()
            .map(item -> OrderItem.create(item.productId(), item.quantity(), item.unitPrice())).toList();
        long total = 0;
        try {
            for (OrderItem item : items) { total = Math.addExact(total, item.getLineAmount()); }
        } catch (ArithmeticException exception) {
            throw new CoreException(ErrorType.ORDER_AMOUNT_OVERFLOW);
        }
        return new Order(null, userId, items, total, OrderStatus.DRAFT, null, null);
    }

    public static Order restore(long id, long userId, List<OrderItem> items, long total, OrderStatus status, Long paidAmount, String paymentResult) {
        return new Order(id, userId, items, total, status, paidAmount, paymentResult);
    }

    public void confirm() {
        if (status != OrderStatus.DRAFT) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        paidAmount = totalAmount;
        paymentResult = "SUCCESS";
        status = OrderStatus.CONFIRMED;
    }
}
