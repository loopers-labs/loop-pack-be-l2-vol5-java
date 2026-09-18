package com.loopers.application.order;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class ConfirmOrderFacade {
    private final IdentifyUser users;
    private final OrderRepository orders;
    private final ProductRepository products;
    private final PointBalanceRepository points;

    public OrderInfo confirm(Long userId, long orderId) {
        long owner = users.require(userId);
        Order order = orders.findById(orderId).filter(value -> value.getUserId() == owner)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        if (order.getStatus() == OrderStatus.CONFIRMED) { return OrderInfo.from(order); }
        for (OrderItem item : order.getItems()) {
            Product product = products.findById(item.getProductId()).orElseThrow(() -> new CoreException(ErrorType.PRODUCT_NOT_FOUND));
            product.deductStock(item.getQuantity());
            products.save(product);
        }
        PointBalance balance = points.findByUserId(owner).orElseGet(() -> PointBalance.empty(owner));
        balance.deduct(order.getTotalAmount());
        points.save(balance);
        order.confirm();
        return OrderInfo.from(orders.save(order));
    }
}
