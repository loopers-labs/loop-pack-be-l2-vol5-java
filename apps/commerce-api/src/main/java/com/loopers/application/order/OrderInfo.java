package com.loopers.application.order;

import com.loopers.domain.order.Order;
import java.util.List;

public record OrderInfo(long orderId, long userId, String status, List<Item> items,
                        long totalAmount, Long paidAmount, String paymentResult) {
    public record Item(long productId, int quantity, long unitPrice, long lineAmount) {}
    public static OrderInfo from(Order order) {
        return new OrderInfo(order.getId(), order.getUserId(), order.getStatus().name(), order.getItems().stream()
            .map(item -> new Item(item.getProductId(), item.getQuantity(), item.getUnitPrice(), item.getLineAmount())).toList(),
            order.getTotalAmount(), order.getPaidAmount(), order.getPaymentResult());
    }
}
