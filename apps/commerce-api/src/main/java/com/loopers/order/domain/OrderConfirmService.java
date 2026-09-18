package com.loopers.order.domain;

import com.loopers.product.domain.Product;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import com.loopers.user.domain.User;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderConfirmService {

    public void confirm(Long requesterId, Order order, List<Product> products, User buyer, ZonedDateTime paidAt) {
        if (!order.isOwnedBy(requesterId)) {
            throw new CoreException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            throw new CoreException(ErrorCode.ORDER_ALREADY_CONFIRMED);
        }

        List<ProductOrderItem> matchedItems = order.getItems().stream()
            .map(item -> new ProductOrderItem(findAvailableProduct(products, item), item))
            .toList();

        matchedItems.forEach(matched ->
            matched.product().getStock().decrease(matched.item().quantity()));
        buyer.getPoint().pay(order.getTotalAmount());
        new PaymentResult(order.getTotalAmount(), paidAt);

        matchedItems.forEach(matched ->
            matched.product().decreaseStock(matched.item().quantity()));
        buyer.pay(order.getTotalAmount());
        order.confirm(order.getTotalAmount(), paidAt);
    }

    private Product findAvailableProduct(List<Product> products, OrderItem item) {
        Product product = products.stream()
            .filter(candidate -> candidate.getId().equals(item.productId()))
            .findFirst()
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_AVAILABLE));
        if (product.isDeleted()) {
            throw new CoreException(ErrorCode.PRODUCT_NOT_AVAILABLE);
        }
        return product;
    }

    private record ProductOrderItem(Product product, OrderItem item) {
    }
}
