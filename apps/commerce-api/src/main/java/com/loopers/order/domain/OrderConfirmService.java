package com.loopers.order.domain;

import com.loopers.product.domain.Product;
import com.loopers.product.domain.Stock;
import com.loopers.user.domain.Point;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;

import java.time.ZonedDateTime;
import java.util.List;

public class OrderConfirmService {

    public void confirm(Long requesterId, Order order, List<Product> products, List<Stock> stocks, Point point, ZonedDateTime paidAt) {
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
            findStock(stocks, matched.item()).decrease(matched.item().quantity()));
        point.pay(order.getTotalAmount());
        new PaymentResult(order.getTotalAmount(), paidAt);

        order.confirm(order.getTotalAmount(), paidAt);
    }

    private Stock findStock(List<Stock> stocks, OrderItem item) {
        return stocks.stream().filter(stock -> stock.getProductId().equals(item.productId())).findFirst()
            .orElseThrow(() -> new CoreException(ErrorCode.PRODUCT_NOT_AVAILABLE));
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
