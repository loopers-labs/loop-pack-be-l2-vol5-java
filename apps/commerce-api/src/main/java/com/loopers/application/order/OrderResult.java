package com.loopers.application.order;

import com.loopers.domain.order.Order;
import java.util.List;

public record OrderResult(long id, long userId, List<Item> items, long total, String status, long paidAmount, String paymentResult) {
    public record Item(long productId, int quantity, long unitPrice) { }
    static OrderResult from(Order order) {
        return new OrderResult(order.getId(), order.getUserId(), order.getItems().stream()
            .map(i -> new Item(i.productId().value(), i.quantity().value(), i.unitPrice().value())).toList(),
            order.getTotal().value(), order.getStatus().name(), order.getPaidAmount().value(), order.getPaymentResult());
    }
}
