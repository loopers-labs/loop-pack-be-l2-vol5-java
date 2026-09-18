package com.loopers.application.order;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmation;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderFacade {

    private final OrderService orderService;
    private final OrderConfirmation orderConfirmation;
    private final ProductService productService;

    @Transactional
    public Order place(OrderCreateCommand command, Instant now) {
        List<OrderItem> items = command.lines().stream()
            .map(line -> {
                Product product = productService.get(line.productId());
                return OrderItem.of(
                    product.getId(), product.getName(), product.getPrice(), line.quantity());
            })
            .toList();

        return orderService.place(Order.draft(command.userId(), items, now));
    }

    @Transactional
    public Order confirm(Long userId, Long orderId, Instant now) {
        return orderConfirmation.confirm(userId, orderId, now);
    }

    @Transactional(readOnly = true)
    public Order get(Long userId, Long orderId) {
        return orderService.getOwned(userId, orderId);
    }

    @Transactional(readOnly = true)
    public List<Order> findMine(Long userId) {
        return orderService.findMine(userId);
    }

    @Transactional(readOnly = true)
    public PageWindow<Order> findPageForAdmin(Long userId, OrderStatus status, PageNumber page, PageSize size) {
        return PageWindow.of(
            orderService.findPage(userId, status, page.offsetWith(size), PageWindow.limitOf(size)), size);
    }

    @Transactional(readOnly = true)
    public Order getForAdmin(Long orderId) {
        return orderService.get(orderId);
    }
}
